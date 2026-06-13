package com.auteur.voice.volcano;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.voice.VoiceProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 火山 V3 异步任务 TTS 客户端。
 *
 * 提交后轮询 query,服务端合成完整 mp3 后返回 audio_url + sentences,我方下载 mp3。
 * 优点:无流式 chunk 截断/close 时序问题,音频一定完整;缺点:延迟高(7 分钟脚本约 30-60 秒)。
 *
 * 鉴权差异:V3 异步用 X-Api-App-Id + X-Api-Access-Key(老版),不接受 X-Api-Key(新版)。
 *
 * 返回 VolcanoTtsHttpClient.Result,接口与单向流式一致,上层透明切换。
 */
@Slf4j
public class VolcanoTtsAsyncClient {

    private final VoiceProperties.Volcano cfg;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public VolcanoTtsAsyncClient(VoiceProperties.Volcano cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public VolcanoTtsHttpClient.Result synthesize(String voiceType, String text, int speechRate, int pitchRate) {
        validateConfig();
        String taskId = submit(voiceType, text, speechRate, pitchRate);
        return pollAndDownload(taskId, voiceType);
    }

    private String submit(String voiceType, String text, int speechRate, int pitchRate) {
        Map<String, Object> audioParams = new LinkedHashMap<>();
        audioParams.put("format", "mp3");
        audioParams.put("sample_rate", 24000);
        audioParams.put("speech_rate", speechRate);
        // 异步接口 audio_params 字段表只有 enable_timestamp,没有 enable_subtitle(那是双向流式/WebSocket 才有)。
        // 不论 1.0/2.0 音色统一用 enable_timestamp。
        audioParams.put("enable_timestamp", true);

        Map<String, Object> reqParams = new LinkedHashMap<>();
        reqParams.put("text", text);
        reqParams.put("speaker", voiceType);
        reqParams.put("audio_params", audioParams);
        if (pitchRate != 0) {
            try {
                String additions = mapper.writeValueAsString(Map.of(
                        "post_process", Map.of("pitch", pitchRate)));
                reqParams.put("additions", additions);
            } catch (Exception e) {
                log.warn("[V3Async] additions serialize failed,跳过 pitch: {}", e.toString());
            }
        }

        String uniqueId = UUID.randomUUID().toString();
        String uid = "auteur-" + uniqueId.substring(0, 8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", Map.of("uid", uid));
        body.put("unique_id", uniqueId);
        body.put("req_params", reqParams);

        String resourceId = resolveResourceId(voiceType);
        URI uri = URI.create(cfg.getBaseUrl() + "/api/v3/tts/submit");
        log.info("[V3Async] submit POST {} resourceId={} voice={} chars={} uniqueId={}",
                uri, resourceId, voiceType, text.length(), uniqueId);

        String bodyJson;
        try {
            bodyJson = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("V3Async submit body serialize failed: " + e.getMessage(), e);
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("X-Api-App-Id", cfg.getAppKey())
                .header("X-Api-Access-Key", cfg.getAccessKey())
                .header("X-Api-Resource-Id", resourceId)
                .header("X-Api-Request-Id", uniqueId)
                .timeout(Duration.ofSeconds(cfg.getHttpTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));

        HttpResponse<String> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("V3Async submit send failed: " + e.getMessage(), e);
        }
        String logId = resp.headers().firstValue("X-Tt-Logid").orElse("");
        if (resp.statusCode() != 200) {
            throw new RuntimeException("V3Async submit http status=" + resp.statusCode()
                    + " logId=" + logId + " body=" + truncate(resp.body(), 500));
        }

        try {
            JsonNode root = mapper.readTree(resp.body());
            int code = root.path("code").asInt(-1);
            if (code != 20000000) {
                String msg = root.path("message").asText("");
                throw new RuntimeException("V3Async submit code=" + code + " msg=" + msg + " logId=" + logId);
            }
            String taskId = root.path("data").path("task_id").asText(null);
            if (taskId == null || taskId.isBlank()) {
                throw new RuntimeException("V3Async submit no task_id logId=" + logId + " body=" + resp.body());
            }
            log.info("[V3Async] submit ok logId={} taskId={}", logId, taskId);
            return taskId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("V3Async submit parse failed: " + e.getMessage(), e);
        }
    }

    /** 轮询 query 直到 task_status=2(Success),拿 audio_url+sentences,下载 mp3,组装 Result。 */
    private VolcanoTtsHttpClient.Result pollAndDownload(String taskId, String voiceType) {
        URI queryUri = URI.create(cfg.getBaseUrl() + "/api/v3/tts/query");
        String resourceId = resolveResourceId(voiceType);
        long pollIntervalMs = cfg.getAsyncPollIntervalSec() * 1000L;
        long deadline = System.currentTimeMillis() + cfg.getAsyncMaxWaitSec() * 1000L;
        int round = 0;

        while (System.currentTimeMillis() < deadline) {
            round++;
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("V3Async query 被中断 taskId=" + taskId);
            }

            String bodyJson;
            try {
                bodyJson = mapper.writeValueAsString(Map.of("task_id", taskId));
            } catch (Exception e) {
                throw new RuntimeException("V3Async query body serialize failed: " + e.getMessage(), e);
            }

            HttpRequest req = HttpRequest.newBuilder(queryUri)
                    .header("Content-Type", "application/json")
                    .header("X-Api-App-Id", cfg.getAppKey())
                    .header("X-Api-Access-Key", cfg.getAccessKey())
                    .header("X-Api-Resource-Id", resourceId)
                    .header("X-Api-Request-Id", UUID.randomUUID().toString())
                    .timeout(Duration.ofSeconds(cfg.getAsyncQueryTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("[V3Async] query round={} 发送失败,retry: {}", round, e.toString());
                continue;
            }
            if (resp.statusCode() != 200) {
                log.warn("[V3Async] query round={} http={},retry: body={}",
                        round, resp.statusCode(), truncate(resp.body(), 200));
                continue;
            }

            try {
                JsonNode root = mapper.readTree(resp.body());
                int code = root.path("code").asInt(-1);
                if (code != 20000000) {
                    String msg = root.path("message").asText("");
                    throw new RuntimeException("V3Async query code=" + code + " msg=" + msg);
                }
                JsonNode data = root.path("data");
                int status = data.path("task_status").asInt(0);
                if (status == 1) {
                    if (round % 5 == 0) {
                        log.info("[V3Async] query round={} taskId={} 仍处理中(已等 {}s)",
                                round, taskId, round * cfg.getAsyncPollIntervalSec());
                    }
                    continue;
                }
                if (status == 3) {
                    throw new RuntimeException("V3Async query task failed taskId=" + taskId
                            + " body=" + truncate(resp.body(), 300));
                }
                if (status == 2) {
                    String audioUrl = data.path("audio_url").asText(null);
                    if (audioUrl == null || audioUrl.isBlank()) {
                        throw new RuntimeException("V3Async query Success but no audio_url taskId=" + taskId);
                    }
                    log.info("[V3Async] query taskId={} 完成,合成字符数={} 已等 {}s,下载 mp3",
                            taskId, data.path("synthesize_text_length").asInt(0),
                            round * cfg.getAsyncPollIntervalSec());
                    byte[] audioBytes = downloadAudio(audioUrl);
                    List<VolcanoTtsHttpClient.Sentence> sentences = parseSentences(data.path("sentences"));
                    return new VolcanoTtsHttpClient.Result(audioBytes, sentences, voiceType);
                }
                log.warn("[V3Async] query round={} 未知 task_status={},retry", round, status);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.warn("[V3Async] query round={} 解析失败,retry: {}", round, e.toString());
            }
        }
        throw new RuntimeException("V3Async query taskId=" + taskId + " 超时(超过 "
                + cfg.getAsyncMaxWaitSec() + "s),仍未完成");
    }

    /** 下载 audio_url 到字节数组(audio_url 1h 失效)。 */
    private byte[] downloadAudio(String audioUrl) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(audioUrl))
                .timeout(Duration.ofSeconds(cfg.getAsyncDownloadTimeoutSeconds()))
                .GET()
                .build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("V3Async audio download status=" + resp.statusCode());
            }
            try (InputStream in = resp.body()) {
                byte[] bytes = in.readAllBytes();
                if (bytes.length < 1024) {
                    throw new RuntimeException("V3Async audio bytes too small: " + bytes.length);
                }
                log.info("[V3Async] 下载 mp3 完成 bytes={}", bytes.length);
                return bytes;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("V3Async audio download failed: " + e.getMessage(), e);
        }
    }

    /** sentences[i] = {text, startTime/endTime(秒,float), words: [...]} → Sentence(text, beginMs, endMs)。 */
    private List<VolcanoTtsHttpClient.Sentence> parseSentences(JsonNode sentencesNode) {
        List<VolcanoTtsHttpClient.Sentence> out = new ArrayList<>();
        if (sentencesNode == null || !sentencesNode.isArray()) return out;
        for (JsonNode s : sentencesNode) {
            String text = s.path("text").asText(null);
            if (text == null || text.isBlank()) continue;
            double startSec = s.path("startTime").asDouble(-1);
            double endSec = s.path("endTime").asDouble(-1);
            if (startSec < 0 || endSec < 0 || endSec <= startSec) continue;
            long beginMs = Math.round(startSec * 1000);
            long endMs = Math.round(endSec * 1000);
            out.add(new VolcanoTtsHttpClient.Sentence(text, beginMs, endMs));
        }
        return out;
    }

    /** 同 VolcanoTtsHttpClient.resolveResourceId。 */
    private String resolveResourceId(String voiceType) {
        if (voiceType != null) {
            if (voiceType.endsWith("_uranus_bigtts")) return "seed-tts-2.0";
            if (isLegacyVoice(voiceType)) return "seed-tts-1.0";
        }
        return cfg.getResourceId();
    }

    private static boolean isLegacyVoice(String voiceType) {
        return voiceType != null
                && (voiceType.endsWith("_mars_bigtts") || voiceType.endsWith("_moon_bigtts"));
    }

    private void validateConfig() {
        if (cfg.getAppKey() == null || cfg.getAppKey().isBlank()) {
            throw new IllegalStateException("异步模式必须配置 auteur.voice.volcano.app-key (App ID)");
        }
        if (cfg.getAccessKey() == null || cfg.getAccessKey().isBlank()) {
            throw new IllegalStateException("异步模式必须配置 auteur.voice.volcano.access-key (Access Token)");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
