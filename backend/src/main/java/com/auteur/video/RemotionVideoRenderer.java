package com.auteur.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.auteur.voice.VoiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Remotion 视频合成器(画幅与 composition 由 preset 决定)。
 *
 * 流程:把 Request → props.json → ProcessBuilder 调 render.sh → 落本地 mp4 → ffmpeg loudnorm → 上 TOS。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "auteur.video.remotion.enabled", havingValue = "true")
public class RemotionVideoRenderer implements VideoRenderer {

    /** Remotion 不按秒计费,留 0;后续接 Remotion Lambda 时再算 */
    private static final BigDecimal COST_PER_SEC = BigDecimal.ZERO;

    private final VideoProperties props;
    private final VoiceProperties voiceProps;
    private final ObjectMapper objectMapper;
    private final com.auteur.storage.TosStorageService tos;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;

    public RemotionVideoRenderer(VideoProperties props, VoiceProperties voiceProps,
                                 ObjectMapper objectMapper,
                                 com.auteur.storage.TosStorageService tos,
                                 com.auteur.runtimeconfig.RuntimeConfig runtimeConfig) {
        this.props = props;
        this.voiceProps = voiceProps;
        this.objectMapper = objectMapper;
        this.tos = tos;
        this.runtimeConfig = runtimeConfig;
    }

    @Override
    public Result render(Request req) {
        log.info("[剪辑·Remotion] render entry scriptId={} contentType={} clips={}",
                req.scriptId(), req.contentType(),
                req.clips() != null ? req.clips().size() : 0);
        if (req.clips() == null || req.clips().isEmpty()) {
            throw new IllegalArgumentException("RemotionVideoRenderer: clips empty");
        }
        if (req.personaJson() == null || req.personaJson().isBlank()) {
            throw new IllegalArgumentException("RemotionVideoRenderer: personaJson missing, topicId=" + req.topicId());
        }

        VideoProperties.Remotion remotionCfg = props.getRemotion();
        Path rendererDir = Paths.get(remotionCfg.getRendererDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(rendererDir)) {
            throw new RuntimeException("Remotion rendererDir 不存在: " + rendererDir
                    + " (config: auteur.video.remotion.renderer-dir)");
        }

        Path outDir = ensureDir(Paths.get(props.getStorage().getLocalDir()));
        Path workDir = ensureDir(Paths.get(props.getStorage().getWorkDir())
                .resolve("remotion-" + UUID.randomUUID()));

        try {
            List<ImageClip> ordered = new ArrayList<>(req.clips());
            ordered.sort(Comparator.comparingInt(ImageClip::shotIndex));

            JsonNode persona = objectMapper.readTree(req.personaJson());
            String personaName = textOr(persona, "name", "未知");

            // Remotion 不支持 file://,统一转 http(s)
            String audioHttpUrl = toHttpUrl(req.audioUrl());

            List<SrtParser.Cue> srtCues = loadSrtCues(req.subtitleUrl());
            ArrayNode cuesNode = cuesToJson(srtCues);

            // clips sum 可能 < 音频真实长度(VideoAssemblyService 跳过无图 shot 会丢这些 shot 的时长)。
            // 取 SRT 末尾时间作为音频真实长度的代理。
            double clipsTotalSec = ordered.stream().mapToDouble(ImageClip::durationSec).sum();
            double srtEndSec = srtCues.isEmpty() ? 0
                    : srtCues.get(srtCues.size() - 1).endMs() / 1000.0;
            double audioDurationSec = Math.max(clipsTotalSec, srtEndSec);
            if (audioDurationSec <= 0) audioDurationSec = ordered.size() * 5.0;

            // clips 总和 < 真实音频长度时,只延长最后一个 clip 填补尾部。
            // 不做全局拉伸——会把 PRECISE_BY_SECTION 精确对齐的切镜点同等比例撑开,导致声画错位。
            if (audioDurationSec > clipsTotalSec + 0.5 && clipsTotalSec > 0 && !ordered.isEmpty()) {
                double tail = audioDurationSec - clipsTotalSec;
                ImageClip last = ordered.get(ordered.size() - 1);
                ordered.set(ordered.size() - 1, new ImageClip(
                        last.shotIndex(), last.imageUrl(),
                        last.startSec(), last.durationSec() + tail, last.caption(),
                        last.sectionCode(), last.anchorText()));
                log.info("[剪辑·Remotion] scriptId={} 末镜延长 +{}s → total={}s",
                        req.scriptId(),
                        String.format("%.1f", tail),
                        String.format("%.1f", audioDurationSec));
            }

            ArrayNode shotsNode = objectMapper.createArrayNode();
            for (ImageClip c : ordered) {
                ObjectNode s = objectMapper.createObjectNode();
                s.put("shotId", c.shotIndex());
                s.put("startSec", c.startSec());
                s.put("durationSec", c.durationSec());
                s.put("imageUrl", toHttpUrl(c.imageUrl()));
                s.put("sectionCode", c.sectionCode() != null ? c.sectionCode() : "");
                shotsNode.add(s);
            }

            ObjectNode root = objectMapper.createObjectNode();
            root.put("audioUrl", audioHttpUrl != null ? audioHttpUrl : "");
            root.put("audioDurationSec", audioDurationSec);
            root.set("shots", shotsNode);
            root.set("subtitleCues", cuesNode);
            root.put("watermark", req.watermarkText() == null ? "" : req.watermarkText());
            if (req.bgm() != null && req.bgm().httpUrl() != null && !req.bgm().httpUrl().isBlank()) {
                root.put("bgmUrl", req.bgm().httpUrl());
                root.put("bgmVolume", req.bgm().volume());
                log.info("[剪辑·Remotion] scriptId={} BGM 接入 url={} volume={}",
                        req.scriptId(), req.bgm().httpUrl(), req.bgm().volume());
            }
            // hook 段:前 4s 快切 + 钩子旁白。主体 shots 与 audio 整体后推 hookDurationSec。
            // pageFlipSoundUrl 为每张图切换时叠播的翻书音效。任一字段缺失走"无 hook"路径。
            if (req.hook() != null
                    && req.hook().imageUrls() != null && !req.hook().imageUrls().isEmpty()
                    && req.hook().audioUrl() != null && !req.hook().audioUrl().isBlank()) {
                ArrayNode hookImagesNode = objectMapper.createArrayNode();
                for (String url : req.hook().imageUrls()) {
                    hookImagesNode.add(toHttpUrl(url));
                }
                root.set("hookImages", hookImagesNode);
                root.put("hookAudioUrl", req.hook().audioUrl());
                root.put("hookText", req.hook().text() == null ? "" : req.hook().text());
                root.put("hookDurationSec", req.hook().durationSec());
                String pageFlipHttpUrl = req.hook().pageFlipSoundUrl() == null
                        ? "" : (toHttpUrl(req.hook().pageFlipSoundUrl()) == null
                                ? "" : toHttpUrl(req.hook().pageFlipSoundUrl()));
                root.put("hookPageFlipSoundUrl", pageFlipHttpUrl);
                log.info("[剪辑·Remotion] scriptId={} hook 接入 images={} dur={}s text={} pageFlip={}",
                        req.scriptId(), req.hook().imageUrls().size(),
                        req.hook().durationSec(), req.hook().text(),
                        req.hook().pageFlipSoundUrl() == null || req.hook().pageFlipSoundUrl().isBlank() ? "无" : "有");
            }
            ObjectNode plan = buildDemoPlan(ordered, persona, req.scriptId());
            root.set("plan", plan);

            Path propsFile = workDir.resolve("props.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(propsFile.toFile(), root);

            long ts = System.currentTimeMillis();
            String outName = String.format("script-%d-%d.mp4", req.scriptId(), ts);
            Path outPath = outDir.resolve(outName).toAbsolutePath();

            if (req.compositionId() == null || req.compositionId().isBlank()) {
                throw new IllegalArgumentException("RemotionVideoRenderer: compositionId 必填,请检查 preset.composition_id");
            }
            String compositionId = req.compositionId();
            int durationMs = runRender(rendererDir, outPath, propsFile.toAbsolutePath(),
                    remotionCfg.getTimeoutSeconds(), compositionId);

            postProcessLoudnorm(outPath, workDir, req.scriptId());

            int durationSec = (int) Math.max(1, Math.round(audioDurationSec));

            log.info("[剪辑·Remotion] scriptId={} persona={} shots={} audio={} cues={} -> {} ({}ms render, ~{}s video)",
                    req.scriptId(), personaName, ordered.size(),
                    audioHttpUrl != null ? "yes" : "none",
                    cuesNode.size(), outName, durationMs, durationSec);

            String url = tos.upload(
                    com.auteur.storage.TosStorageService.buildKey(req.scriptId(), "video", outName),
                    outPath, "video/mp4");
            deleteQuietly(outPath);
            return new Result(url, durationSec,
                    req.width() > 0 ? req.width() : 1080,
                    req.height() > 0 ? req.height() : 1920,
                    req.format() != null ? req.format() : "9:16",
                    COST_PER_SEC);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Remotion render failed: " + e.getMessage(), e);
        } finally {
            cleanWorkDir(workDir);
        }
    }

    // ---------------- 子步骤 ----------------

    /**
     * 用 scriptId 做种子的伪随机选 motion,且强制相邻镜不同种。
     * 同一个 scriptId 重渲染得到相同序列(可复现);不同 scriptId 序列各异(对 AI 检测器
     * 不再呈现固定周期模式)。"相邻不同"是这套约束里最关键的一条:循环原版每 5 镜重复,
     * 一旦改成相邻去重,即使 5 种全用上,序列也不会出现规律周期。
     *
     * 内容感:有 anchorText 时走 MotionIntentHeuristic,把高潮/凝视/转场等情绪映射到运镜池;
     * 无 anchorText 时回退到全 5 种池随机(P0 行为)。两条路径都在同一个 Random 实例上抽,
     * 所以"种子可复现 + 相邻去重"在两种 case 下都成立。
     */
    private ObjectNode buildDemoPlan(List<ImageClip> ordered, JsonNode persona, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        ArrayNode shotPlans = objectMapper.createArrayNode();
        String prev = null;
        for (ImageClip clip : ordered) {
            String motion = MotionIntentHeuristic.pickMotion(clip.anchorText(), prev, rnd);
            ObjectNode sp = objectMapper.createObjectNode();
            sp.put("shotId", clip.shotIndex());
            sp.put("motion", motion);
            shotPlans.add(sp);
            prev = motion;
        }

        ArrayNode keywords = objectMapper.createArrayNode();
        String name = textOr(persona, "name", "");
        if (!name.isBlank()) keywords.add(name);
        String identity = textOr(persona, "identity", "");
        if (!identity.isBlank() && identity.length() <= 8) keywords.add(identity);

        ObjectNode plan = objectMapper.createObjectNode();
        plan.set("shotPlans", shotPlans);
        plan.set("highlightKeywords", keywords);
        plan.set("blackHoldsAt", objectMapper.createArrayNode());
        return plan;
    }

    /** 加载并解析 SRT,失败安全降级为空。支持 https TOS URL 与 legacy /api/files/voice/x.srt。 */
    private List<SrtParser.Cue> loadSrtCues(String subtitleUrl) {
        if (subtitleUrl == null || subtitleUrl.isBlank()) return Collections.emptyList();

        if (subtitleUrl.startsWith("http://") || subtitleUrl.startsWith("https://")) {
            try {
                Path tmp = Files.createTempFile("remotion-srt-", ".srt");
                try (java.io.InputStream in = java.net.URI.create(subtitleUrl).toURL().openStream()) {
                    Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                List<SrtParser.Cue> cues = SrtParser.parseFile(tmp);
                Files.deleteIfExists(tmp);
                return cues;
            } catch (Exception e) {
                log.warn("[剪辑·Remotion] SRT 下载/解析失败(http) url={}: {}", subtitleUrl, e.toString());
                return Collections.emptyList();
            }
        }

        String prefix = props.getRemotion().getVoiceUrlPrefix();
        if (!subtitleUrl.startsWith(prefix)) return Collections.emptyList();
        String name = subtitleUrl.substring(prefix.length());
        Path local = Paths.get(voiceProps.getStorage().getLocalDir())
                .resolve(name).toAbsolutePath();
        if (!Files.exists(local)) {
            log.warn("[剪辑·Remotion] subtitle 本地文件不存在 url={} resolved={}", subtitleUrl, local);
            return Collections.emptyList();
        }
        try {
            return SrtParser.parseFile(local);
        } catch (IOException e) {
            log.warn("[剪辑·Remotion] SRT 解析失败 url={}: {}", subtitleUrl, e.toString());
            return Collections.emptyList();
        }
    }

    private ArrayNode cuesToJson(List<SrtParser.Cue> cues) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (SrtParser.Cue c : cues) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("startSec", c.startMs() / 1000.0);
            n.put("endSec", c.endMs() / 1000.0);
            n.put("text", c.text() != null ? c.text() : "");
            arr.add(n);
        }
        return arr;
    }

    /**
     * 把 audio/image url 翻译成 Remotion 浏览器能下载的 http(s) URL。
     * Remotion 不支持 file://;/api/files/... 拼 publicBaseUrl。
     */
    private String toHttpUrl(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("/")) {
            String base = runtimeConfig.get("auteur.video.remotion.public-base-url",
                    props.getRemotion().getPublicBaseUrl());
            if (base == null || base.isBlank()) {
                log.warn("[剪辑·Remotion] publicBaseUrl 未配置,无法将 {} 拼成 HTTP URL", url);
                return null;
            }
            return base.replaceAll("/+$", "") + url;
        }
        log.warn("[剪辑·Remotion] url 既非 http(s) 也非 /api/files/...,Remotion 可能加载失败: {}", url);
        return null;
    }

    private int runRender(Path rendererDir, Path outPath, Path propsPath, int timeoutSec,
                          String compositionId)
            throws IOException, InterruptedException {        List<String> cmd = List.of(
                "bash", "scripts/render.sh",
                outPath.toString(),
                propsPath.toString(),
                compositionId
        );
        log.info("[剪辑·Remotion] exec: cd {} && {}", rendererDir, String.join(" ", cmd));
        long t0 = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(rendererDir.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder tail = new StringBuilder();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                String chunk = new String(buf, 0, n);
                tail.append(chunk);
                if (tail.length() > 16384) tail.delete(0, tail.length() - 8192);
            }
        }
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("Remotion render timeout after " + timeoutSec + "s");
        }
        int code = p.exitValue();
        if (code != 0) {
            throw new RuntimeException("Remotion render exit=" + code
                    + " tail=" + tail.toString().trim());
        }
        return (int) (System.currentTimeMillis() - t0);
    }

    /**
     * ffmpeg 把 mp4 整体响度推到 -16 LUFS(短视频平台标准),收紧动态范围。
     * Remotion 直出 mp4 通常在 -24~-22 LUFS,差 6-8 LU(听感 ~2x)。
     *
     * filter chain:acompressor 先把瞬态压住(ratio=3 温和),再 loudnorm 推到 -16,LRA 收紧到 7。
     * 视频流 -c:v copy 不重编。失败 → log warn 保留原文件,不阻塞成片。
     */
    private void postProcessLoudnorm(Path outPath, Path workDir, Long scriptId) {
        Path normalized = workDir.resolve("normalized.mp4");
        long t0 = System.currentTimeMillis();
        try {
            String audioFilter = "acompressor=threshold=-18dB:ratio=3:attack=20:release=250,"
                    + "loudnorm=I=-16:TP=-1.5:LRA=7";
            List<String> cmd = List.of(
                    "ffmpeg", "-y", "-loglevel", "error",
                    "-i", outPath.toString(),
                    "-af", audioFilter,
                    "-c:v", "copy",
                    "-c:a", "aac", "-b:a", "192k",
                    normalized.toString()
            );
            log.info("[剪辑·Remotion] scriptId={} loudnorm+compress 启动 -> {}", scriptId, normalized);
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder tail = new StringBuilder();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    tail.append(new String(buf, 0, n));
                    if (tail.length() > 4096) tail.delete(0, tail.length() - 2048);
                }
            }
            boolean done = p.waitFor(300, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                log.warn("[剪辑·Remotion] scriptId={} loudnorm 超时 5min,保留原文件", scriptId);
                deleteQuietly(normalized);
                return;
            }
            if (p.exitValue() != 0) {
                log.warn("[剪辑·Remotion] scriptId={} loudnorm 失败 exit={} tail={},保留原文件",
                        scriptId, p.exitValue(), tail.toString().trim());
                deleteQuietly(normalized);
                return;
            }
            if (!Files.exists(normalized) || Files.size(normalized) < 1024) {
                log.warn("[剪辑·Remotion] scriptId={} loudnorm 产物缺失/过小,保留原文件", scriptId);
                deleteQuietly(normalized);
                return;
            }
            Files.delete(outPath);
            Files.move(normalized, outPath);
            log.info("[剪辑·Remotion] scriptId={} loudnorm+compress OK ({}ms),响度 -16 LUFS / LRA target 7",
                    scriptId, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("[剪辑·Remotion] scriptId={} loudnorm 异常,保留原文件: {}", scriptId, e.toString());
            deleteQuietly(normalized);
        }
    }

    // ---------------- helpers ----------------

    private static void deleteQuietly(java.nio.file.Path p) {
        try { if (p != null) java.nio.file.Files.deleteIfExists(p); }
        catch (Exception e) { /* ignore */ }
    }

    private Path ensureDir(Path p) {
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new RuntimeException("create dir failed: " + p, e);
        }
        return p;
    }

    private void cleanWorkDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.delete(x); } catch (IOException ignore) {}
            });
        } catch (IOException e) {
            log.warn("[剪辑·Remotion] cleanup failed dir={}: {}", dir, e.toString());
        }
    }

    private static String textOr(JsonNode n, String field, String fallback) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return fallback;
        String s = v.asText();
        return s.isBlank() ? fallback : s;
    }
}
