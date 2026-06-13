package com.auteur.voice.volcano;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.voice.VoiceProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 火山 V3 单向流式 TTS - HTTP Chunked 协议。
 *
 * resourceId 按 voice 后缀路由:火山 voice 与 resource 强绑定,uranus → seed-tts-2.0,
 * mars/moon → seed-tts-1.0,选错报 code=55000000 "resource ID is mismatched"。
 *
 * 响应是 chunked 流,每行一个 JSON:音频帧(data=base64 mp3 片段)/ 字幕帧(sentence.words[]) /
 * code=20000000 成功结束 / 其它非 0 立刻抛。
 */
@Slf4j
public class VolcanoTtsHttpClient {

    private final VoiceProperties.Volcano cfg;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public VolcanoTtsHttpClient(VoiceProperties.Volcano cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public record Sentence(String text, long beginMs, long endMs) {}

    public record Result(byte[] audioBytes, List<Sentence> sentences, String voiceType) {}

    public Result synthesize(String voiceType, String text, int speechRate, int pitchRate) {
        validateConfig();

        Map<String, Object> audioParams = new LinkedHashMap<>();
        audioParams.put("format", "mp3");
        audioParams.put("sample_rate", 24000);
        audioParams.put("speech_rate", speechRate);
        // 字幕开关跟资源版本走:2.0 (uranus) 用 enable_subtitle;1.0 (mars/moon) 用 enable_timestamp。
        // 配错那一边直接被服务端忽略 → 响应里没 sentence 帧,下游 StoryboardService 缺 subtitle_url。
        if (isLegacyVoice(voiceType)) {
            audioParams.put("enable_timestamp", true);
        } else {
            audioParams.put("enable_subtitle", true);
        }

        Map<String, Object> reqParams = new LinkedHashMap<>();
        reqParams.put("text", text);
        reqParams.put("speaker", voiceType);
        reqParams.put("audio_params", audioParams);
        // pitch 走 additions.post_process.pitch,additions 整体是 jsonstring 类型(文档要求)
        if (pitchRate != 0) {
            try {
                String additions = mapper.writeValueAsString(Map.of(
                        "post_process", Map.of("pitch", pitchRate)));
                reqParams.put("additions", additions);
            } catch (Exception e) {
                log.warn("[V3Http] additions serialize failed,跳过 pitch 设置: {}", e.toString());
            }
        }

        String requestId = UUID.randomUUID().toString();
        String uid = "auteur-" + requestId.substring(0, 8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", Map.of("uid", uid));
        body.put("req_params", reqParams);

        // resourceId 按 voice 后缀挑(catalog 同时含 uranus 2.0 与 mars emo v2 1.0,
        // 不动态路由会撞 code=55000000 资源错配)
        String resourceId = resolveResourceId(voiceType);
        URI uri = URI.create(cfg.getBaseUrl() + "/api/v3/tts/unidirectional");
        String apiKeyTail = cfg.getApiKey() == null || cfg.getApiKey().length() < 4
                ? "<empty>"
                : "..." + cfg.getApiKey().substring(cfg.getApiKey().length() - 4);
        log.info("[V3Http] POST {} resourceId={} apiKeyTail={} voice={} chars={} requestId={}",
                uri, resourceId, apiKeyTail, voiceType, text.length(), requestId);

        String bodyJson;
        try {
            bodyJson = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("V3Http body serialize failed: " + e.getMessage(), e);
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("X-Api-Resource-Id", resourceId)
                .header("X-Api-Request-Id", requestId)
                .timeout(Duration.ofSeconds(cfg.getHttpTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
        if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
            rb.header("X-Api-Key", cfg.getApiKey());
        }
        if (cfg.getAppKey() != null && !cfg.getAppKey().isBlank()) {
            rb.header("X-Api-App-Key", cfg.getAppKey());
        }
        if (cfg.getAccessKey() != null && !cfg.getAccessKey().isBlank()) {
            rb.header("X-Api-Access-Key", cfg.getAccessKey());
        }

        HttpResponse<InputStream> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new RuntimeException("V3Http send failed: " + e.getMessage(), e);
        }
        String logId = resp.headers().firstValue("X-Tt-Logid").orElse("");
        log.info("[V3Http] response status={} logId={}", resp.statusCode(), logId);

        if (resp.statusCode() != 200) {
            String errBody;
            try (InputStream is = resp.body()) {
                errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                errBody = "<read body failed: " + e.getMessage() + ">";
            }
            throw new RuntimeException("V3Http http status=" + resp.statusCode()
                    + " logId=" + logId + " body=" + truncate(errBody, 500));
        }

        try (InputStream is = resp.body()) {
            return parseStream(is, voiceType, logId);
        } catch (IOException e) {
            throw new RuntimeException("V3Http stream read failed logId=" + logId
                    + " err=" + e.getMessage(), e);
        }
    }

    private Result parseStream(InputStream stream, String voiceType, String logId) throws IOException {
        ByteArrayOutputStream audioBuf = new ByteArrayOutputStream();
        List<Sentence> sentences = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String line;
        boolean finished = false;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            JsonNode root;
            try {
                root = mapper.readTree(line);
            } catch (Exception e) {
                log.warn("[V3Http] non-JSON chunk skipped: {}", truncate(line, 120));
                continue;
            }
            int code = root.path("code").asInt(-1);

            if (code == 20000000) {
                int textWords = root.path("usage").path("text_words").asInt(0);
                log.info("[V3Http] done logId={} audioBytes={} sentences={} usage.text_words={}",
                        logId, audioBuf.size(), sentences.size(), textWords);
                finished = true;
                break;
            }
            if (code != 0) {
                String msg = root.path("message").asText("");
                throw new RuntimeException("V3Http server error code=" + code
                        + " msg=" + msg + " logId=" + logId);
            }
            // 音频帧 data 是 base64
            JsonNode dataNode = root.path("data");
            if (dataNode.isTextual() && !dataNode.asText().isEmpty()) {
                try {
                    byte[] audio = Base64.getDecoder().decode(dataNode.asText());
                    audioBuf.write(audio);
                } catch (IllegalArgumentException e) {
                    log.warn("[V3Http] base64 decode failed,跳过: {}", e.toString());
                }
            }
            // 字幕帧 sentence.words[].startTime/endTime 单位秒
            JsonNode sentenceNode = root.get("sentence");
            if (sentenceNode != null && !sentenceNode.isNull() && !sentenceNode.isMissingNode()) {
                Sentence s = extractSentence(sentenceNode);
                if (s != null) sentences.add(s);
            }
        }

        if (!finished) {
            log.warn("[V3Http] stream ended without code=20000000 logId={} audioBytes={}",
                    logId, audioBuf.size());
        }
        return new Result(audioBuf.toByteArray(), sentences, voiceType);
    }

    /** 句子起止 = 第一个 word.startTime / 最后一个 word.endTime(秒)。 */
    private Sentence extractSentence(JsonNode sentence) {
        String text = sentence.path("text").asText(null);
        if (text == null || text.isBlank()) return null;
        JsonNode words = sentence.path("words");
        if (!words.isArray() || words.isEmpty()) return null;

        double startSec = words.get(0).path("startTime").asDouble(-1);
        double endSec = words.get(words.size() - 1).path("endTime").asDouble(-1);
        if (startSec < 0 || endSec < 0 || endSec <= startSec) return null;

        long beginMs = Math.round(startSec * 1000);
        long endMs = Math.round(endSec * 1000);
        return new Sentence(text, beginMs, endMs);
    }

    /**
     * 按 voice_type 后缀挑 X-Api-Resource-Id。火山 voice 与资源强绑定,选错报 code=55000000。
     *   *_uranus_bigtts → seed-tts-2.0
     *   *_mars_bigtts / *_moon_bigtts(含 *_emo_v2_*) → seed-tts-1.0
     *   其它/自定义复刻 → cfg.getResourceId() 兜底
     */
    private String resolveResourceId(String voiceType) {
        if (voiceType != null) {
            if (voiceType.endsWith("_uranus_bigtts")) return "seed-tts-2.0";
            if (isLegacyVoice(voiceType)) return "seed-tts-1.0";
        }
        return cfg.getResourceId();
    }

    /** 1.0 系列(mars/moon),包括情绪 V2。这条线字幕开关用 enable_timestamp。 */
    private static boolean isLegacyVoice(String voiceType) {
        return voiceType != null
                && (voiceType.endsWith("_mars_bigtts") || voiceType.endsWith("_moon_bigtts"));
    }

    private void validateConfig() {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            if ((cfg.getAppKey() == null || cfg.getAppKey().isBlank())
                    && (cfg.getAccessKey() == null || cfg.getAccessKey().isBlank())) {
                throw new IllegalStateException(
                        "auteur.voice.volcano.api-key 未配置(或旧版 app-key/access-key 都为空)");
            }
        }
        // resource-id 不强校验:已知后缀由 resolveResourceId 自动定;只有兜底分支才会回落到 cfg.getResourceId()
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}