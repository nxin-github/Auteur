package com.auteur.llm;

import com.auteur.runtimeconfig.RuntimeConfig;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 按 ErrorClassifier 的 errorType 分档的重试策略。
 *
 *   limit / timeout / network → 指数退避,最多 N 次(N 由 RuntimeConfig 读 DB)
 *   json                       → 线性,最多 N 次
 *   sensitive / 4xx            → 不重试(同 prompt/同请求重试无意义)
 *
 * 重构记录:V12(2026-06-13)从 static utility 升格为 Spring @Component,max-attempts
 * 改成从 app_config 读,延迟 base/cap ms 仍硬编码(运维很少调,改动 ROI 低)。
 */
@Component
public final class RetryPolicy {

    private final RuntimeConfig runtimeConfig;

    public RetryPolicy(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    public record Decision(boolean retry, long sleepMs) {}

    public Decision decide(String errorType, int attempt) {
        return switch (errorType == null ? "network" : errorType) {
            case "limit"    -> exponential(attempt, maxAttempts("limit",   3), 10_000L, 60_000L);
            case "timeout"  -> exponential(attempt, maxAttempts("timeout", 1),  2_000L,  5_000L);
            case "network"  -> exponential(attempt, maxAttempts("network", 3),  2_000L, 30_000L);
            case "json"     -> linear     (attempt, maxAttempts("json",    2),  1_000L,  5_000L);
            case "sensitive", "4xx" -> new Decision(false, 0L);
            default         -> exponential(attempt, maxAttempts("network", 3),  2_000L, 30_000L);
        };
    }

    private int maxAttempts(String errorClass, int fallback) {
        return runtimeConfig.getInt("auteur.llm.retry." + errorClass + "-max-attempts", fallback);
    }

    private static Decision exponential(int attempt, int maxRetries, long baseMs, long capMs) {
        if (attempt > maxRetries) return new Decision(false, 0L);
        long raw = baseMs * (1L << Math.min(attempt - 1, 16));
        if (raw > capMs) raw = capMs;
        return new Decision(true, jitter(raw, 20));
    }

    private static Decision linear(int attempt, int maxRetries, long baseMs, long capMs) {
        if (attempt > maxRetries) return new Decision(false, 0L);
        long raw = baseMs * Math.max(1, attempt);
        if (raw > capMs) raw = capMs;
        return new Decision(true, jitter(raw, 20));
    }

    private static long jitter(long ms, int pct) {
        if (pct <= 0 || ms <= 0) return ms;
        double factor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * (pct / 100.0);
        return Math.max(0L, (long) (ms * factor));
    }
}
