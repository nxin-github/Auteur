package com.auteur.agent;

import com.auteur.llm.ChatRequest;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmToolResult;
import com.auteur.llm.ModelRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Agent Loop 核心。
 *
 * turn(sessionId, userText, emitter):
 *   1) 落 user 消息 → 推 user_saved
 *   2) 重放 (system + 历史) 给 LLM,带上注册的工具
 *   3) LLM 返回:
 *        - 含 tool_calls → 落 assistant 行(带 tool_calls_json)→ 推 assistant_done
 *                       → 逐个执行工具(每个落一行 tool 消息 + 推 tool_call/tool_result)
 *                       → 回到 step 2(让 LLM 看到结果)
 *        - 纯文本     → 落 assistant 终消息 → 推 assistant_done + done,完
 *   4) 兜底:循环超过 MAX_TURNS(8)推 error 终止
 *
 * 持久化全部委托给 AgentMessagePersister,本类只编排流程,不直接持库。
 *
 * 协议守恒:assistant.tool_calls 必须与 tool message 一一对齐,缺一个 LLM 下次重放就 400。
 *   所有可能跳过 tool 执行的分支(取消/超时/审批 cancelled/审批通过后立刻被取消)
 *   都必须给对应 tool_call 补一条 CANCELLED placeholder,见 fillCanceledToolPlaceholders。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLoopService {

    /** Agent 单轮工具调用上限次数;达上限强制收尾。通过 RuntimeConfig 读 DB,默认 8。 */
    private static final int MAX_TURNS_DEFAULT = 8;

    /**
     * Sliding window:重放时只保留最近 K 个 user 消息及其后续 assistant/tool 序列。
     * 更老的消息合并成一段 system summary 占位,避免每轮重放全历史导致 token 爆炸。
     * 切窗边界放在 user 消息开头,保证 assistant.tool_calls 一定与对应 tool 消息成对。
     */
    private static final int K_RECENT_USER_TURNS_DEFAULT = 6;

    /** 重放给 LLM 时单条消息的字符上限。超过则用 surrogate-safe 截断 + 标记尾巴。
     *  这是"控制 token 成本"的最后防线;DB 持久化不再截断,所以原文永远在库里。 */
    private static final int REPLAY_MAX_CHARS_DEFAULT = 32_000;

    /** 审批 future.get 的本地超时,稍大于 ApprovalGate 的 decision-timeout(默认 300s),留 5s 兜底。 */
    private static final long APPROVAL_WAIT_SECONDS_DEFAULT = 305;

    private final AgentSessionRepository sessionRepo;
    private final AgentMessageRepository messageRepo;
    private final AgentMessagePersister persister;
    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final SystemPromptBuilder systemPromptBuilder;
    private final ObjectMapper objectMapper;
    private final ApprovalGate approvalGate;
    private final AgentCancellationRegistry cancellationRegistry;
    private final ModelRegistry modelRegistry;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;

    private int maxTurns() { return runtimeConfig.getInt("auteur.agent.max-turns", MAX_TURNS_DEFAULT); }
    private int kRecentUserTurns() { return runtimeConfig.getInt("auteur.agent.k-recent-user-turns", K_RECENT_USER_TURNS_DEFAULT); }
    private int replayMaxChars() { return runtimeConfig.getInt("auteur.agent.replay-max-chars", REPLAY_MAX_CHARS_DEFAULT); }
    private long approvalWaitSeconds() { return runtimeConfig.getInt("auteur.agent.approval-wait-seconds", (int) APPROVAL_WAIT_SECONDS_DEFAULT); }

    public void turn(Long sessionId, String userText, SseEmitter emitter) {
        Consumer<AgentEvent> sink = ev -> sendEvent(emitter, ev);
        AtomicBoolean cancelSignal = cancellationRegistry.register(sessionId);
        try {
            AgentSession session = sessionRepo.findById(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("session 不存在: " + sessionId));

            AgentMessage userMsg = persister.saveUserMessage(session, userText);
            sink.accept(AgentEvent.of("user_saved", Map.of(
                    "messageId", userMsg.getId(),
                    "seq", userMsg.getSeq(),
                    "content", userText == null ? "" : userText
            )));

            int maxTurns = maxTurns();
            for (int turn = 0; turn < maxTurns; turn++) {
                // 进新一轮 LLM 前检查取消信号 — 已经在跑的 LLM 调用必须等结果落库,不能丢
                if (cancelSignal.get()) {
                    log.info("[Agent] sessionId={} 在新轮 LLM 调用前被取消,正常结束", sessionId);
                    return;
                }
                List<ChatRequest.Message> history = replayMessages(session);
                LlmToolResult result = callLlm(session, history);

                // ⚠️ LLM 已经花了 token,无论 cancel 与否都先落库 — 别让用户回到页面看不到 agent 的回复
                if (hasToolCalls(result)) {
                    AgentMessage assistantRow = persister.saveAssistantWithTools(session, result);
                    sink.accept(AgentEvent.of("assistant_done", Map.of(
                            "messageId", assistantRow.getId(),
                            "seq", assistantRow.getSeq(),
                            "content", result.getContent() == null ? "" : result.getContent(),
                            "hasToolCalls", true
                    )));

                    // LLM 调用期间被取消 → 不执行 tool,但 assistant.tool_calls 已落库,
                    // 必须为每个 tool_call 补一条 CANCELLED placeholder,否则下次重放时
                    // OpenAI 协议要求"assistant.tool_calls ↔ tool message 一对一对齐",缺了 LLM 会崩
                    if (cancelSignal.get()) {
                        log.info("[Agent] sessionId={} LLM 已落库,跳过 {} 个 tool,补 placeholder 保协议完整",
                                sessionId, result.getToolCalls().size());
                        fillCanceledToolPlaceholders(session, result.getToolCalls(), 0, sink);
                        return;
                    }

                    for (int i = 0; i < result.getToolCalls().size(); i++) {
                        if (cancelSignal.get()) {
                            log.info("[Agent] sessionId={} 跳过剩余 {} 个 tool,补 placeholder",
                                    sessionId, result.getToolCalls().size() - i);
                            fillCanceledToolPlaceholders(session, result.getToolCalls(), i, sink);
                            return;
                        }
                        executeAndPersistTool(session, result.getToolCalls().get(i), sink, cancelSignal);
                    }
                    continue;
                }

                // 纯文本回复:落库 → 推 done → 完
                AgentMessage assistantRow = persister.saveAssistantText(session, result);
                sink.accept(AgentEvent.of("assistant_done", Map.of(
                        "messageId", assistantRow.getId(),
                        "seq", assistantRow.getSeq(),
                        "content", result.getContent() == null ? "" : result.getContent(),
                        "hasToolCalls", false
                )));
                sink.accept(AgentEvent.of("done", Map.of("sessionId", sessionId)));
                emitter.complete();
                return;
            }

            // 跑满 maxTurns 仍在调工具:再来一次禁用工具的 LLM 调用,让它给出收尾文本,
            // 否则历史会停在 assistant→tool 截断状态,下一轮重放时模型会迷惑。
            log.warn("[Agent] sessionId={} 达到 max-turns={},强制禁工具收尾", sessionId, maxTurns);
            List<ChatRequest.Message> history = replayMessages(session);
            history.add(ChatRequest.Message.system(
                    "已达到本轮工具调用上限(" + maxTurns + " 次)。请基于已有 tool 结果直接给用户写一段总结/下一步建议,不要再调用任何工具。"));
            LlmToolResult finalResult = llmClient.chatWithTools(
                    LlmCallSpec.builder()
                            .operation("agent.chat.cap")
                            .relatedType("AGENT_SESSION")
                            .relatedId(session.getId())
                            .model(modelRegistry.modelOrDefault(session.getModel(), "agent_default"))
                            .temperature(0.3)
                            .build(),
                    history,
                    List.of() // 禁用工具
            );
            AgentMessage capRow = persister.saveAssistantText(session, finalResult);
            sink.accept(AgentEvent.of("assistant_done", Map.of(
                    "messageId", capRow.getId(),
                    "seq", capRow.getSeq(),
                    "content", finalResult.getContent() == null ? "" : finalResult.getContent(),
                    "hasToolCalls", false
            )));
            sink.accept(AgentEvent.of("error",
                    Map.of("message", "已达到本轮工具调用上限 " + maxTurns + " 次,Agent 强制收尾。如需继续请重新发起。")));
            emitter.complete();
        } catch (Exception e) {
            log.error("[Agent] turn 失败 sessionId={}: {}", sessionId, e.toString(), e);
            sink.accept(AgentEvent.of("error",
                    Map.of("message", e.getMessage() == null ? e.toString() : e.getMessage())));
            emitter.completeWithError(e);
        } finally {
            cancellationRegistry.unregister(sessionId, cancelSignal);
        }
    }

    /**
     * 为未执行的 tool_call 补 CANCELLED placeholder,保护 OpenAI 协议(assistant.tool_calls ↔ tool message 必须一对一)。
     * 也推一条 tool_result 事件给前端,UI 会把对应的 ApprovalCard/loading 状态切到"已取消"。
     */
    private void fillCanceledToolPlaceholders(AgentSession session,
                                              List<ChatRequest.ToolCall> calls,
                                              int startFrom,
                                              Consumer<AgentEvent> sink) {
        for (int i = startFrom; i < calls.size(); i++) {
            ChatRequest.ToolCall call = calls.get(i);
            String name = call.getFunction() == null ? null : call.getFunction().getName();
            String argsJson = call.getFunction() == null ? null : call.getFunction().getArguments();
            String callId = call.getId() == null ? "" : call.getId();
            String resultJson = toJson(Map.of(
                    "canceled", true,
                    "reason", "用户在工具执行前取消了会话,本工具未实际运行。下次会话如需可重新调用。"
            ));
            AgentMessage row = persister.saveToolResult(session, callId, name, argsJson, resultJson, "CANCELLED");
            sink.accept(AgentEvent.of("tool_result", Map.of(
                    "messageId", row.getId(),
                    "seq", row.getSeq(),
                    "id", callId,
                    "name", name == null ? "" : name,
                    "status", "CANCELLED",
                    "resultJson", resultJson
            )));
        }
    }

    private LlmToolResult callLlm(AgentSession session, List<ChatRequest.Message> history) {
        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("agent.chat")
                .relatedType("AGENT_SESSION")
                .relatedId(session.getId())
                .model(modelRegistry.modelOrDefault(session.getModel(), "agent_default"))
                .temperature(0.3)
                .build();
        return llmClient.chatWithTools(spec, history, toolRegistry.definitions());
    }

    private boolean hasToolCalls(LlmToolResult r) {
        return r.getToolCalls() != null && !r.getToolCalls().isEmpty();
    }

    private List<ChatRequest.Message> replayMessages(AgentSession session) {
        List<AgentMessage> rows = messageRepo.findBySessionIdOrderBySeqAsc(session.getId());

        // 找最近 K 个 user 消息;更老的部分折叠成 summary
        List<Integer> userIdx = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if ("user".equals(rows.get(i).getRole())) userIdx.add(i);
        }
        int windowStart = 0;
        List<AgentMessage> folded = List.of();
        int kRecent = kRecentUserTurns();
        if (userIdx.size() > kRecent) {
            windowStart = userIdx.get(userIdx.size() - kRecent);
            folded = rows.subList(0, windowStart);
        }
        List<AgentMessage> windowed = rows.subList(windowStart, rows.size());

        List<ChatRequest.Message> out = new ArrayList<>(windowed.size() + 2);
        out.add(ChatRequest.Message.system(systemPromptBuilder.build()));
        if (!folded.isEmpty()) {
            out.add(ChatRequest.Message.system(buildFoldedSummary(folded)));
            log.info("[Agent] sessionId={} 重放折叠 {} 条历史 → 窗口 {} 条",
                    session.getId(), folded.size(), windowed.size());
        }

        for (AgentMessage row : windowed) {
            switch (row.getRole()) {
                case "user" -> out.add(ChatRequest.Message.user(truncateForReplay(row.getContent())));
                case "assistant" -> {
                    List<ChatRequest.ToolCall> tcs = parseToolCalls(row.getToolCallsJson());
                    out.add(ChatRequest.Message.assistant(truncateForReplay(row.getContent()), tcs));
                }
                case "tool" -> out.add(ChatRequest.Message.tool(
                        row.getToolCallId(),
                        row.getToolName(),
                        truncateForReplay(row.getContent())
                ));
                default -> log.warn("[Agent] 跳过未知 role={} msgId={}", row.getRole(), row.getId());
            }
        }
        return out;
    }

    /**
     * 重放给 LLM 前把单条消息按字符上限截断,surrogate-safe(不切碎 emoji/扩展平面字符)。
     * DB 里持久化的是原文,这里只是给 LLM 的副本节省 token。
     */
    String truncateForReplay(String s) {
        int max = replayMaxChars();
        if (s == null || s.length() <= max) return s;
        int keep = max - 200;
        // 别在 surrogate pair 中间切开,产生孤立 high surrogate
        if (keep > 0 && Character.isHighSurrogate(s.charAt(keep - 1))) keep--;
        if (keep < 0) keep = 0;
        return s.substring(0, keep)
                + "\n\n[...TRUNCATED: 原文共 " + s.length() + " 字符,本次仅给 LLM 前 " + keep + " 字符。如需更多请重新调读工具或问用户...]";
    }

    /**
     * 折叠老消息生成的占位 summary。不调 LLM 二次摘要(避免成本),用结构化拼接:
     *   - 用户问题首句列表(surrogate-safe 截断)
     *   - 期间调用过的工具名 + 状态(OK/ERROR/REJECTED/CANCELLED)分桶聚合
     * 给 LLM 提供"知道之前发生过什么"的最小上下文,如需细节让它向用户问。
     */
    private String buildFoldedSummary(List<AgentMessage> folded) {
        List<String> userQuestions = new ArrayList<>();
        Map<String, Integer> toolCounts = new LinkedHashMap<>();
        for (AgentMessage r : folded) {
            if ("user".equals(r.getRole()) && r.getContent() != null) {
                String q = r.getContent().strip();
                userQuestions.add(safeShorten(q, 60));
            } else if ("tool".equals(r.getRole()) && r.getToolName() != null) {
                String status = r.getToolStatus();
                // OK 不加后缀;非 OK 加 (ERROR)/(REJECTED)/(CANCELLED) 让 LLM 知道这次没成功
                String key = "OK".equals(status) || status == null
                        ? r.getToolName()
                        : r.getToolName() + "(" + status + ")";
                toolCounts.merge(key, 1, Integer::sum);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[已折叠 ").append(folded.size()).append(" 条更早历史以节省 token]\n");
        if (!userQuestions.isEmpty()) {
            sb.append("早期用户问题(摘要):\n");
            for (String q : userQuestions) sb.append("- ").append(q).append("\n");
        }
        if (!toolCounts.isEmpty()) {
            String joined = toolCounts.entrySet().stream()
                    .map(e -> e.getKey() + "×" + e.getValue())
                    .collect(Collectors.joining(", "));
            sb.append("期间调用工具(后缀=非 OK 状态): ").append(joined).append("\n");
        }
        sb.append("如需更早细节请向用户确认或重新调用相关读工具,不要从工具名瞎猜结果。");
        return sb.toString();
    }

    /** 按字符数硬截不切碎 surrogate pair。 */
    private static String safeShorten(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) return s;
        int keep = maxChars;
        if (Character.isHighSurrogate(s.charAt(keep - 1))) keep--;
        return s.substring(0, keep) + "...";
    }

    private List<ChatRequest.ToolCall> parseToolCalls(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ChatRequest.ToolCall.class));
        } catch (JsonProcessingException e) {
            // 历史 assistant 行的 tool_calls 解析失败:静默降级会让重放出现 assistant→tool 错位,
            // 模型行为不可预期。直接抛错让本轮失败,用户可以删掉这条会话或人工修数据。
            throw new IllegalStateException(
                    "tool_calls_json 反序列化失败,无法重放历史:" + e.getMessage(), e);
        }
    }

    private void executeAndPersistTool(AgentSession session, ChatRequest.ToolCall call,
                                       Consumer<AgentEvent> sink, AtomicBoolean cancelSignal) {
        String name = call.getFunction() == null ? null : call.getFunction().getName();
        String argsJson = call.getFunction() == null ? null : call.getFunction().getArguments();
        String callId = call.getId() == null ? "" : call.getId();

        sink.accept(AgentEvent.of("tool_call", Map.of(
                "id", callId,
                "name", name == null ? "" : name,
                "argsJson", argsJson == null ? "" : argsJson
        )));

        ToolHandler handler = toolRegistry.find(name);

        // HITL: WRITE/ACTION 类工具先发审批请求,阻塞等用户决定
        PreviewableHandler.Preview preview = null;
        if (handler != null && handler.risk() != ToolHandler.Risk.READ) {
            // PreviewableHandler 在审批前先算 diff(before/after),让用户看到具体改动
            if (handler instanceof PreviewableHandler ph) {
                try {
                    JsonNode args = parseArgs(argsJson);
                    preview = ph.preview(args);
                } catch (Exception e) {
                    // preview 失败 = 参数有问题/目标不存在;不走审批,直接落 ERROR 让 LLM 重试
                    log.warn("[Agent] preview {} 失败: {}", name, e.toString());
                    String errResult = toJson(Map.of(
                            "error", "preview 失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()),
                            "hint", "检查参数(scriptId/sectionId 是否存在,字段名是否对)后重试。"
                    ));
                    persistAndEmit(session, callId, name, argsJson, errResult, "ERROR", sink);
                    return;
                }
            }

            ApprovalGate.ApprovalDecision decision =
                    waitForApproval(session.getId(), callId, name, argsJson, handler.risk(), preview, sink);

            // 三态:cancelled → CANCELLED placeholder;rejected → REJECTED;approved → 继续执行
            if (decision.cancelled()) {
                String resultJson = toJson(Map.of(
                        "canceled", true,
                        "reason", decision.reason()
                ));
                persistAndEmit(session, callId, name, argsJson, resultJson, "CANCELLED", sink);
                return;
            }
            if (!decision.approved()) {
                String resultJson = toJson(Map.of(
                        "approved", false,
                        "reason", decision.reason(),
                        "hint", "用户拒绝执行此工具,请询问用户原因或建议替代方案,不要重试同一动作。"
                ));
                persistAndEmit(session, callId, name, argsJson, resultJson, "REJECTED", sink);
                return;
            }

            // TOCTOU 防护:审批通过后再调一次 preview,如果 before 变了说明审批期间被人改过 → 放弃写
            if (handler instanceof PreviewableHandler ph2 && preview != null) {
                try {
                    PreviewableHandler.Preview again = ph2.preview(parseArgs(argsJson));
                    if (!Objects.equals(preview.before(), again.before())) {
                        log.warn("[Agent] {} TOCTOU: before 在审批期间变了,放弃写入", name);
                        String stale = toJson(Map.of(
                                "stale", true,
                                "reason", "目标数据在审批期间被其他人修改,本次写入已放弃,避免覆盖他人改动。",
                                "hint", "如仍要改,请重新读取最新内容后再发起一次审批。"
                        ));
                        persistAndEmit(session, callId, name, argsJson, stale, "ERROR", sink);
                        return;
                    }
                } catch (Exception e) {
                    // 二次 preview 失败不阻塞执行(可能是临时性问题),记日志放行
                    log.warn("[Agent] {} TOCTOU 二次 preview 失败,跳过校验放行: {}", name, e.toString());
                }
            }
        }

        // 审批通过和执行之间也可能被 cancel,补 placeholder 保协议
        if (cancelSignal.get()) {
            String resultJson = toJson(Map.of(
                    "canceled", true,
                    "reason", "审批通过后会话被取消,本工具未实际运行。"
            ));
            persistAndEmit(session, callId, name, argsJson, resultJson, "CANCELLED", sink);
            return;
        }

        String resultJson;
        String status;
        if (handler == null) {
            resultJson = toJson(Map.of("error", "未注册的工具: " + name));
            status = "ERROR";
        } else {
            try {
                JsonNode args = parseArgs(argsJson);
                Object out = handler.execute(args);
                resultJson = toJson(out);
                status = "OK";
            } catch (Exception e) {
                log.warn("[Agent] tool {} 执行失败: {}", name, e.toString());
                resultJson = toJson(Map.of(
                        "error", e.getMessage() == null ? e.toString() : e.getMessage()
                ));
                status = "ERROR";
            }
        }
        persistAndEmit(session, callId, name, argsJson, resultJson, status, sink);
    }

    /** 落库 + 推 SSE tool_result 的统一出口,避免三处重复的 Map.of 字面量。 */
    private void persistAndEmit(AgentSession session, String callId, String name, String argsJson,
                                String resultJson, String status, Consumer<AgentEvent> sink) {
        AgentMessage row = persister.saveToolResult(session, callId, name, argsJson, resultJson, status);
        sink.accept(AgentEvent.of("tool_result", Map.of(
                "messageId", row.getId(),
                "seq", row.getSeq(),
                "id", callId,
                "name", name == null ? "" : name,
                "status", status,
                "resultJson", resultJson
        )));
    }

    /**
     * 发 SSE tool_approval_request,阻塞等用户响应(单 future,无轮询)。
     *
     * 工作机制:
     *   - ApprovalGate.register 的 future 自带 60s completeOnTimeout,正常路径下到点会以 rejected 完成。
     *   - AgentCancellationRegistry.cancel 会同步调 ApprovalGate.cancelSession,该 sessionId 的所有挂起 future 立刻 cancelled 完成。
     *   - 因此 future.get(65s) 总会很快返回,不需要 500ms 轮询。
     *
     * preview 可空;非空时把 fieldName/before/after/summary 一并塞进 SSE event,前端用 DiffView 渲染。
     */
    private ApprovalGate.ApprovalDecision waitForApproval(
            Long sessionId, String toolCallId, String toolName, String argsJson,
            ToolHandler.Risk risk, PreviewableHandler.Preview preview,
            Consumer<AgentEvent> sink) {
        var future = approvalGate.register(toolCallId, sessionId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", toolCallId);
        payload.put("name", toolName == null ? "" : toolName);
        payload.put("argsJson", argsJson == null ? "" : argsJson);
        payload.put("risk", risk.name());
        payload.put("timeoutSeconds", 300);
        if (preview != null) {
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("fieldName", preview.fieldName());
            diff.put("before", preview.before() == null ? "" : preview.before());
            diff.put("after", preview.after() == null ? "" : preview.after());
            diff.put("summary", preview.summary());
            payload.put("diff", diff);
        }
        sink.accept(AgentEvent.of("tool_approval_request", payload));

        try {
            return future.get(approvalWaitSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            // 线程被中断:不回填 interrupt 标志(否则下一个 LLM HTTP 调用会立刻挂),
            // 当作"会话取消",外层 for-loop 会通过 cancelSignal/CANCELLED placeholder 处理。
            log.warn("[Agent] approval wait 被中断 toolCallId={}", toolCallId);
            return ApprovalGate.ApprovalDecision.cancelled("线程被中断");
        } catch (TimeoutException te) {
            // 不该发生(Gate 自带 60s completeOnTimeout 应先到),兜底
            log.warn("[Agent] approval wait 兜底超时 toolCallId={}", toolCallId);
            return ApprovalGate.ApprovalDecision.rejected("审批等待超时");
        } catch (Exception e) {
            log.warn("[Agent] approval future 异常 toolCallId={}: {}", toolCallId, e.toString());
            return ApprovalGate.ApprovalDecision.rejected("审批环节出错: " + e.getMessage());
        }
    }

    private JsonNode parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(argsJson);
        } catch (JsonProcessingException e) {
            log.warn("[Agent] tool 参数 JSON 解析失败,原文: {}", argsJson);
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"json_serialize_failed\"}";
        }
    }

    private void sendEvent(SseEmitter emitter, AgentEvent ev) {
        try {
            emitter.send(SseEmitter.event().name(ev.getType()).data(ev.getData()));
        } catch (Exception e) {
            // 客户端断开时 Spring 抛多种异常:IOException(Broken pipe)、AsyncRequestNotUsableException、
            // IllegalStateException(emitter 已 complete)等,统一 swallow。SSE 推送本来就是 best-effort,
            // 推不出去也不影响业务正确性(DB 已落库,前端刷页能看到完整状态)。
            log.warn("[Agent] SSE 推送失败 type={}: {}", ev.getType(), e.toString());
        }
    }
}
