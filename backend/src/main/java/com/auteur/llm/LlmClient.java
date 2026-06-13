package com.auteur.llm;

import com.auteur.common.text.TextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final RestClient llmRestClient;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;
    private final RetryPolicy retryPolicy;

    public LlmResult chat(LlmCallSpec spec, String systemPrompt, String userPrompt) {
        return chatWithRetry(spec, List.of(
                ChatRequest.Message.system(systemPrompt),
                ChatRequest.Message.user(userPrompt)
        ), userPrompt == null ? 0 : userPrompt.length());
    }

    public LlmResult chatWithImage(LlmCallSpec spec, String systemPrompt, String userText, String imageUrl) {
        return chatWithRetry(spec, List.of(
                ChatRequest.Message.system(systemPrompt),
                ChatRequest.Message.userWithImage(userText, resolveImageUrl(imageUrl))
        ), userText == null ? 0 : userText.length());
    }

    /**
     * 带 tool 的多轮对话：调用方传完整 messages（含历史） + tool 定义，
     * 返回 LlmToolResult，包含 assistant 的文本 + 可能的 tool_calls。
     * 调用方负责执行 tool 并把结果作为新的 tool message 追加到 messages 后重新调用。
     */
    public LlmToolResult chatWithTools(LlmCallSpec spec,
                                       List<ChatRequest.Message> messages,
                                       List<ChatRequest.Tool> tools) {
        String model = resolvePrimaryModel(spec);
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return doToolHttpCall(spec, model, messages, tools, attempt);
            } catch (RuntimeException e) {
                String errorType = ErrorClassifier.classify(e);
                RetryPolicy.Decision d = retryPolicy.decide(errorType, attempt);
                if (!d.retry()) {
                    log.warn("[LLM] op={} model={} attempt={} failed ({}), giving up",
                            spec.getOperation(), model, attempt, errorType);
                    throw e;
                }
                log.warn("[LLM] op={} model={} attempt={} failed ({}), retrying after {}ms",
                        spec.getOperation(), model, attempt, errorType, d.sleepMs());
                if (d.sleepMs() > 0) sleepQuietly(d.sleepMs());
            }
        }
    }

    private LlmToolResult doToolHttpCall(LlmCallSpec spec, String model,
                                         List<ChatRequest.Message> messages,
                                         List<ChatRequest.Tool> tools, int attempt) {
        Double temperature = spec.getTemperature() != null ? spec.getTemperature() : 0.3;
        String provider = providerOf(model);

        ChatRequest req = new ChatRequest();
        req.setModel(model);
        if (supportsTemperature(model)) req.setTemperature(temperature);
        if (spec.getMaxTokens() != null && spec.getMaxTokens() > 0) {
            req.setMax_tokens(spec.getMaxTokens());
        }
        req.setMessages(messages);
        if (tools != null && !tools.isEmpty()) {
            req.setTools(tools);
            req.setTool_choice("auto");
        }

        log.info("[LLM] op={} model={} attempt={} tools={} msgs={}",
                spec.getOperation(), model, attempt, tools == null ? 0 : tools.size(), messages.size());

        long t0 = System.currentTimeMillis();
        ChatResponse resp = llmRestClient.post()
                .uri("/chat/completions")
                .body(req)
                .retrieve()
                .body(ChatResponse.class);
        int durationMs = (int) (System.currentTimeMillis() - t0);

        if (resp == null || resp.getChoices() == null || resp.getChoices().isEmpty()) {
            throw new IllegalStateException("LLM returned empty choices");
        }
        ChatResponse.Choice choice = resp.getChoices().get(0);
        ChatResponse.Message msg = choice.getMessage();
        Integer inTok = resp.getUsage() == null ? null : resp.getUsage().getPrompt_tokens();
        Integer outTok = resp.getUsage() == null ? null : resp.getUsage().getCompletion_tokens();

        log.info("[LLM] op={} model={} attempt={} ok ms={} inTok={} outTok={} finish={} toolCalls={}",
                spec.getOperation(), model, attempt, durationMs, inTok, outTok,
                choice.getFinish_reason(),
                msg.getTool_calls() == null ? 0 : msg.getTool_calls().size());

        return LlmToolResult.builder()
                .content(msg.getContent())
                .toolCalls(msg.getTool_calls())
                .finishReason(choice.getFinish_reason())
                .model(model)
                .provider(provider)
                .inputTokens(inTok)
                .outputTokens(outTok)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 本地 /api/files/image/ 路径无法被外部视觉模型访问，转为 base64 data URI。
     * TOS 图片已是 https URL，直接透传；旧本地路径兜底转 base64（历史数据）。
     */
    private String resolveImageUrl(String imageUrl) {
        if (imageUrl == null) return null;
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")
                || imageUrl.startsWith("data:")) return imageUrl;
        // 旧本地路径兜底（历史数据）
        if (imageUrl.startsWith("/api/files/image/")) {
            String filename = imageUrl.substring("/api/files/image/".length());
            Path local = Paths.get("./storage/image").toAbsolutePath().resolve(filename);
            if (Files.exists(local)) {
                try {
                    byte[] bytes = Files.readAllBytes(local);
                    return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
                } catch (IOException e) {
                    log.warn("[LLM] 本地图片转 base64 失败 path={}: {}", local, e.toString());
                }
            }
        }
        return imageUrl;
    }

    private LlmResult chatWithRetry(LlmCallSpec spec, List<ChatRequest.Message> messages, int chars) {
        String model = resolvePrimaryModel(spec);
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return doHttpCall(spec, model, messages, chars, attempt);
            } catch (RuntimeException e) {
                String errorType = ErrorClassifier.classify(e);
                RetryPolicy.Decision d = retryPolicy.decide(errorType, attempt);
                if (!d.retry()) {
                    log.warn("[LLM] op={} model={} attempt={} failed ({}), giving up",
                            spec.getOperation(), model, attempt, errorType);
                    throw e;
                }
                log.warn("[LLM] op={} model={} attempt={} failed ({}), retrying after {}ms",
                        spec.getOperation(), model, attempt, errorType, d.sleepMs());
                if (d.sleepMs() > 0) sleepQuietly(d.sleepMs());
            }
        }
    }

    private LlmResult doHttpCall(LlmCallSpec spec, String model,
                                 List<ChatRequest.Message> messages, int chars, int attempt) {
        Double temperature = spec.getTemperature() != null ? spec.getTemperature() : 0.8;
        String provider = providerOf(model);

        ChatRequest req = new ChatRequest();
        req.setModel(model);
        if (supportsTemperature(model)) {
            req.setTemperature(temperature);
        }
        if (spec.getMaxTokens() != null && spec.getMaxTokens() > 0) {
            req.setMax_tokens(spec.getMaxTokens());
        }
        req.setMessages(messages);

        log.info("[LLM] op={} model={} attempt={} userPromptChars={}",
                spec.getOperation(), model, attempt, chars);

        long t0 = System.currentTimeMillis();
        ChatResponse resp;
        try {
            resp = llmRestClient.post()
                    .uri("/chat/completions")
                    .body(req)
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RuntimeException e) {
            int durationMs = (int) (System.currentTimeMillis() - t0);
            log.warn("[LLM] op={} model={} attempt={} fail ms={} err={}",
                    spec.getOperation(), model, attempt, durationMs,
                    e.getMessage() == null ? e.getClass().getSimpleName() : TextUtils.truncate(e.getMessage(), 200));
            throw e;
        }

        int durationMs = (int) (System.currentTimeMillis() - t0);

        if (resp == null || resp.getChoices() == null || resp.getChoices().isEmpty()) {
            log.warn("[LLM] op={} model={} attempt={} fail ms={} err=empty_choices",
                    spec.getOperation(), model, attempt, durationMs);
            throw new IllegalStateException("LLM returned empty choices");
        }

        Integer inTok = resp.getUsage() == null ? null : resp.getUsage().getPrompt_tokens();
        Integer outTok = resp.getUsage() == null ? null : resp.getUsage().getCompletion_tokens();

        String content = resp.getChoices().get(0).getMessage().getContent();
        log.info("[LLM] op={} model={} attempt={} ok ms={} inTok={} outTok={} outChars={}",
                spec.getOperation(), model, attempt, durationMs, inTok, outTok,
                content == null ? 0 : content.length());

        return LlmResult.builder()
                .content(content)
                .model(model)
                .provider(provider)
                .inputTokens(inTok)
                .outputTokens(outTok)
                .durationMs(durationMs)
                .build();
    }

    private String resolvePrimaryModel(LlmCallSpec spec) {
        if (spec.getModel() != null && !spec.getModel().isBlank()) return spec.getModel();
        // 走到这条路径意味着调用方没经过 ModelRegistry 直接构造了 LlmCallSpec —— 这是 bug。
        // 兜底再读一次 app_config 里 auteur.llm.default-model(系统设置页可改),空就抛,让问题立即显形。
        String fallback = runtimeConfig.get("auteur.llm.default-model");
        if (fallback.isBlank()) {
            throw new IllegalStateException(
                    "LlmCallSpec.model 为空且 auteur.llm.default-model 未在系统设置中配置。"
                            + "请检查调用方是否走 ModelRegistry,或在「配置 → 系统设置」填 auteur.llm.default-model。");
        }
        return fallback;
    }

    private static String providerOf(String model) {
        if (model == null || model.isBlank()) return "unknown";
        String lower = model.toLowerCase();
        int cut = lower.length();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '-' || c == '.' || c == '/' || c == '_') { cut = i; break; }
        }
        return lower.substring(0, cut);
    }

    private static boolean supportsTemperature(String model) {
        if (model == null) return true;
        String lower = model.toLowerCase();
        return !lower.startsWith("claude-") || !lower.contains("-4-7");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
