package com.auteur.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * HITL 审批闸门。
 *
 * 流程:
 *   AgentLoopService 准备执行 WRITE/ACTION 工具时调 register(toolCallId, sessionId) 拿一个 Future,
 *   再发 SSE tool_approval_request,然后阻塞等 Future。
 *   前端用户点"批准/拒绝" → POST /agent/sessions/{id}/approve → 调 resolve(toolCallId, sessionId, ...)
 *   60 秒内没决定 → completeOnTimeout 兜底,future 自带"已超时拒绝"值。
 *
 * 关键设计:
 *   - 单 future:register 把原始 future 放进 pending 同时返回它本身,resolve 完成的就是 caller 等的那个,
 *     杜绝"chained future 与原 f 分叉"的潜在 race。
 *   - sessionId 绑定:resolve 必须传 expectedSessionId,只有 toolCallId 与 sessionId 配对才允许通过,
 *     防止跨会话注入。
 *   - cancelSession(sessionId) 主动完成该 session 所有挂起 future(标 cancelled),
 *     与 AgentCancellationRegistry 联动,让取消信号能立即抽身审批等待。
 */
@Slf4j
@Component
public class ApprovalGate {

    /** 用户响应超时,超时算拒绝。LLM 可在下一轮自纠正。通过 RuntimeConfig 读 DB,默认 300 秒。 */
    private static final long DECISION_TIMEOUT_SECONDS_DEFAULT = 300;

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;

    public ApprovalGate(com.auteur.runtimeconfig.RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    private long decisionTimeoutSeconds() {
        return runtimeConfig.getInt("auteur.agent.approval-decision-timeout-seconds", (int) DECISION_TIMEOUT_SECONDS_DEFAULT);
    }

    /** AgentLoopService 调:登记一个待审批 tool_call,返回 Future 用于阻塞等待。 */
    public CompletableFuture<ApprovalDecision> register(String toolCallId, Long sessionId) {
        CompletableFuture<ApprovalDecision> f = new CompletableFuture<>();
        pending.put(toolCallId, new Pending(sessionId, f));
        long timeoutSec = decisionTimeoutSeconds();
        // completeOnTimeout 直接把 future 用 normal value 完成(不是 exception),
        // 避免和 .exceptionally(...) 分叉成两个 future 引用。返回的仍是 this。
        f.completeOnTimeout(
                ApprovalDecision.rejected("用户未在 " + timeoutSec + "s 内响应,默认拒绝"),
                timeoutSec, TimeUnit.SECONDS
        );
        // 完成时(超时/resolve/cancelSession 任一路径)统一清 pending,无 leak。
        f.whenComplete((d, ex) -> pending.remove(toolCallId));
        return f;
    }

    /**
     * AgentController 调:用户响应到达,完成对应的 Future。
     * 必须传 expectedSessionId,只有匹配 register 时绑定的 sessionId 才放行,
     * 避免拿到 toolCallId 的人(任意用户/陈旧请求)对别会话的审批投票。
     */
    public ResolveOutcome resolve(String toolCallId, Long expectedSessionId, boolean approved, String reason) {
        Pending p = pending.get(toolCallId);
        if (p == null) {
            log.warn("[Agent] resolve 找不到待审批 toolCallId={} (可能已超时/取消/重复响应)", toolCallId);
            return ResolveOutcome.NOT_FOUND;
        }
        if (expectedSessionId == null || !expectedSessionId.equals(p.sessionId)) {
            log.warn("[Agent] resolve sessionId 不匹配 toolCallId={} 期望={} 实际={}",
                    toolCallId, expectedSessionId, p.sessionId);
            return ResolveOutcome.SESSION_MISMATCH;
        }
        ApprovalDecision d = approved
                ? ApprovalDecision.approved(reason)
                : ApprovalDecision.rejected(reason == null || reason.isBlank() ? "用户拒绝" : reason);
        boolean done = p.future.complete(d);
        // future 已经被超时/取消等先一步完成时 done=false,告诉调用方决定没真正生效。
        return done ? ResolveOutcome.OK : ResolveOutcome.ALREADY_RESOLVED;
    }

    /**
     * 主动取消某 session 的所有挂起审批,future 标 cancelled。
     * AgentCancellationRegistry.cancel() 会调本方法,让审批等待能跟着 turn 取消立即抽身。
     */
    public void cancelSession(Long sessionId) {
        if (sessionId == null) return;
        // 先收集再 complete,避免 whenComplete 回调与 entrySet 迭代竞态(虽然 CHM 弱一致,稳一点更好)。
        List<CompletableFuture<ApprovalDecision>> targets = new ArrayList<>();
        for (Pending p : pending.values()) {
            if (sessionId.equals(p.sessionId)) targets.add(p.future);
        }
        for (var f : targets) {
            f.complete(ApprovalDecision.cancelled("会话已取消"));
        }
        if (!targets.isEmpty()) {
            log.info("[Agent] cancelSession sessionId={} 解除挂起审批 {} 个", sessionId, targets.size());
        }
    }

    private record Pending(Long sessionId, CompletableFuture<ApprovalDecision> future) {}

    public enum ResolveOutcome {
        OK,                // 决定已送达且生效
        NOT_FOUND,         // 找不到 toolCallId(超时/已被 resolve/被取消)
        SESSION_MISMATCH,  // toolCallId 存在但 sessionId 不匹配(跨会话注入)
        ALREADY_RESOLVED   // future 已被先一步完成(超时或并发取消)
    }

    /**
     * 审批结果。三态:
     *   - approved=true                  → 用户批准,执行
     *   - approved=false, cancelled=false → 用户/超时拒绝,落 REJECTED
     *   - cancelled=true                 → 会话取消,落 CANCELLED placeholder 保协议完整
     */
    public record ApprovalDecision(boolean approved, String reason, boolean cancelled) {
        public static ApprovalDecision approved(String reason) {
            return new ApprovalDecision(true, reason == null ? "" : reason, false);
        }
        public static ApprovalDecision rejected(String reason) {
            return new ApprovalDecision(false, reason == null ? "用户拒绝" : reason, false);
        }
        public static ApprovalDecision cancelled(String reason) {
            return new ApprovalDecision(false, reason == null ? "已取消" : reason, true);
        }
    }
}
