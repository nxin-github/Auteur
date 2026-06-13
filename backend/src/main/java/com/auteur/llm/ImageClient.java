package com.auteur.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.storage.TosStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageClient {

    private final RestClient llmRestClient;
    private final ObjectMapper objectMapper;
    private final TosStorageService tos;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;
    private final RetryPolicy retryPolicy;

    public Result generate(LlmCallSpec spec, String prompt, String size) {
        return generate(spec, prompt, size, null);
    }

    public Result generate(LlmCallSpec spec, String prompt, String size, String referenceImageUrl) {
        if (spec.getModel() == null || spec.getModel().isBlank()) {
            throw new IllegalArgumentException("Image model required (e.g. Doubao-Seedream-5.0-lite)");
        }
        String model = spec.getModel();
        String resolvedSize = size != null ? size : "1024x1792";

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return doHttpCall(spec, model, prompt, resolvedSize, referenceImageUrl, attempt);
            } catch (SensitiveContentException sensitive) {
                throw sensitive;
            } catch (RuntimeException e) {
                String errorType = ErrorClassifier.classify(e);
                if ("sensitive".equals(errorType)) {
                    log.warn("[美术] op={} model={} attempt={} blocked by content audit ({}), aborting retries",
                            spec.getOperation(), model, attempt, truncate(e.getMessage(), 200));
                    throw new SensitiveContentException(
                            "image gen blocked by upstream content audit", e);
                }
                RetryPolicy.Decision d = retryPolicy.decide(errorType, attempt);
                if (!d.retry()) {
                    log.warn("[美术] op={} model={} attempt={} failed ({}), giving up",
                            spec.getOperation(), model, attempt, errorType);
                    throw e;
                }
                log.warn("[美术] op={} model={} attempt={} failed ({}), retrying after {}ms",
                        spec.getOperation(), model, attempt, errorType, d.sleepMs());
                if (d.sleepMs() > 0) sleepQuietly(d.sleepMs());
            }
        }
    }

    private Result doHttpCall(LlmCallSpec spec, String model, String prompt, String size,
                              String referenceImageUrl, int attempt) {
        String provider = providerOf(model);

        ImageGenRequest req = new ImageGenRequest();
        req.setModel(model);
        req.setPrompt(prompt);
        req.setSize(size);
        req.setN(1);
        // Doubao 系列需要显式关水印并指定 response_format；gpt-image 系列不支持这两个字段
        if (model.startsWith("Doubao") || model.startsWith("doubao")) {
            req.setResponse_format("url");
            req.setWatermark(false);
        }
        if (referenceImageUrl != null && !referenceImageUrl.isBlank()) {
            req.setImage(referenceImageUrl);
        }

        log.info("[美术] op={} model={} attempt={} size={} promptChars={} hasRef={}",
                spec.getOperation(), model, attempt, req.getSize(),
                prompt == null ? 0 : prompt.length(),
                req.getImage() != null);

        long t0 = System.currentTimeMillis();
        ResponseEntity<byte[]> entity = llmRestClient.post()
                .uri("/images/generations")
                .body(req)
                .retrieve()
                .toEntity(byte[].class);

        int durationMs = (int) (System.currentTimeMillis() - t0);
        byte[] body = entity.getBody();
        if (body == null || body.length == 0) {
            throw new IllegalStateException("Image gen returned empty body");
        }

        // 按 Content-Type 分路：octet-stream / image/* → 直接存文件；其余 → JSON 解析
        MediaType ct = entity.getHeaders().getContentType();
        boolean isBinary = ct != null &&
                (ct.isCompatibleWith(MediaType.APPLICATION_OCTET_STREAM) || "image".equals(ct.getType()));
        if (isBinary) {
            log.info("[美术] op={} model={} binary response ({}) → uploading to TOS", spec.getOperation(), model, ct);
            String url = saveImage(body, spec);
            return new Result(url, model, provider, durationMs, req.getSize());
        }

        // JSON 路径（application/json 或无 Content-Type 时尝试解析）
        ImageGenRequest.Response resp;
        try {
            resp = objectMapper.readValue(body, ImageGenRequest.Response.class);
        } catch (Exception e) {
            throw new IllegalStateException("Image gen JSON parse failed: " + e.getMessage(), e);
        }

        if (resp == null || resp.getData() == null || resp.getData().isEmpty()) {
            throw new IllegalStateException("Image gen returned empty data");
        }
        ImageGenRequest.Response.DataItem item = resp.getData().get(0);
        String url = item.getUrl();

        // gpt-image 系列返回 b64_json 而非 URL：落盘为本地文件并返回 /api/files/image/ 路径
        if ((url == null || url.isBlank()) && item.getB64Json() != null && !item.getB64Json().isBlank()) {
            url = saveB64Image(item.getB64Json(), spec);
        }

        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Image gen returned blank url");
        }

        // 模型返回的 URL 是 provider 临时 OSS 签名 URL(24h 有效期,第三方桶并发拉慢)。
        // Remotion 渲染 30+ 张时 delayRender 28s 超时大概率挂。下载 + reupload 到项目 TOS,
        // ImageAsset.fileUrl 落稳定公网 URL。gpt-image 系列走 b64_json 分支,不会进这里。
        if (url.startsWith("http")) {
            url = repipeUrlToTos(url, spec);
        }

        return new Result(url, model, provider, durationMs, req.getSize());
    }

    /**
     * 把 provider 临时 URL 下载 + 上传到项目 TOS,返回稳定公网 URL。
     * 失败回落原 URL(下游 Remotion 可能仍可用,只是不稳)。
     * 慢:Doubao jpeg ~700KB 平均 1-2s,加在生图主链路上;但避免后续 Remotion 渲染时反复拉外部桶超时,净收益正。
     */
    private String repipeUrlToTos(String sourceUrl, LlmCallSpec spec) {
        long t0 = System.currentTimeMillis();
        byte[] bytes;
        String mime = "image/png";
        try {
            java.net.URLConnection conn = java.net.URI.create(sourceUrl).toURL().openConnection();
            conn.setConnectTimeout(runtimeConfig.getInt("auteur.image.compress.connect-timeout-ms", 15_000));
            conn.setReadTimeout(runtimeConfig.getInt("auteur.image.compress.read-timeout-ms", 60_000));
            String ct = conn.getContentType();
            if (ct != null && ct.startsWith("image/")) mime = ct;
            try (java.io.InputStream in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            }
        } catch (Exception e) {
            log.warn("[美术] repipe download failed src={} err={}, fall back to source URL",
                    truncate(sourceUrl, 80), e.toString());
            return sourceUrl;
        }
        String ext = mime.contains("jpeg") || mime.contains("jpg") ? "jpg" : "png";
        String filename = spec.getOperation().replace("/", "_") + "-" + UUID.randomUUID() + "." + ext;
        String key = TosStorageService.buildKey(spec.getScriptId(), "images", filename);
        String reuploaded = tos.upload(key, bytes, mime);
        log.info("[美术] repipe ok provider={} bytes={} ms={} → {}",
                providerOf(spec.getModel()), bytes.length,
                System.currentTimeMillis() - t0, reuploaded);
        return reuploaded;
    }

    private String saveB64Image(String b64, LlmCallSpec spec) {
        return saveImage(Base64.getDecoder().decode(b64), spec);
    }

    private String saveImage(byte[] bytes, LlmCallSpec spec) {
        String filename = spec.getOperation().replace("/", "_") + "-" + UUID.randomUUID() + ".png";
        String key = TosStorageService.buildKey(spec.getScriptId(), "images", filename);
        return tos.upload(key, bytes, "image/png");
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

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record Result(String url, String model, String provider, int durationMs, String size) {}
}
