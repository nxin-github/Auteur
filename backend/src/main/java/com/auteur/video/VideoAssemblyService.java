package com.auteur.video;

import com.auteur.bgm.BgmService;
import com.auteur.common.FinalAssetMarker;
import com.auteur.common.path.VoiceFileResolver;
import com.auteur.domain.ImageAsset;
import com.auteur.domain.ImageAssetRepository;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineStage;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptBgmChoice;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.ScriptSection;
import com.auteur.domain.ScriptSectionRepository;
import com.auteur.domain.StoryboardShot;
import com.auteur.domain.StoryboardShotRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.domain.VideoAsset;
import com.auteur.domain.VideoAssetRepository;
import com.auteur.domain.VoiceAsset;
import com.auteur.domain.VoiceAssetRepository;
import com.auteur.pipeline.PipelineRunService;
import com.auteur.topic.TopicStatusAdvancer;
import com.auteur.voice.VoiceClient;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * 视频合成 worker。流程:拉 storyboard_shot + script_section → 选 voice → 解析 SRT →
 * ShotTimingResolver 计算每镜真实时长 → 每 shot 选图 → 拼 ImageClip → 调 VideoRenderer → 落库 video_asset。
 *
 * 骨架阶段 totalItems=1(script-level 单 item)。
 */
@Slf4j
@Service
public class VideoAssemblyService {

    private static final double DEFAULT_SHOT_SEC_DEFAULT = 5.0;
    private static final int DEFAULT_W = 1080;
    private static final int DEFAULT_H = 1920;
    private static final String DEFAULT_FORMAT = "9:16";

    private final VideoRenderer videoRenderer;
    private final Optional<RemotionVideoRenderer> remotionRenderer;
    private final ScriptRepository scriptRepository;
    private final TopicRepository topicRepository;
    private final StoryboardShotRepository shotRepository;
    private final ScriptSectionRepository sectionRepository;
    private final ImageAssetRepository imageAssetRepository;
    private final VoiceAssetRepository voiceAssetRepository;
    private final VideoAssetRepository videoAssetRepository;
    private final PipelineRunService runService;
    private final Executor pipelineExecutor;
    private final VoiceFileResolver voiceFileResolver;
    private final BgmService bgmService;
    private final TopicStatusAdvancer topicStatusAdvancer;
    private final com.auteur.voice.VoiceClient voiceClient;
    private final com.auteur.preset.TopicPresetResolver presetResolver;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;

    public VideoAssemblyService(VideoRenderer videoRenderer,
                                Optional<RemotionVideoRenderer> remotionRenderer,
                                ScriptRepository scriptRepository,
                                TopicRepository topicRepository,
                                StoryboardShotRepository shotRepository,
                                ScriptSectionRepository sectionRepository,
                                ImageAssetRepository imageAssetRepository,
                                VoiceAssetRepository voiceAssetRepository,
                                VideoAssetRepository videoAssetRepository,
                                PipelineRunService runService,
                                @Qualifier("pipelineExecutor") Executor pipelineExecutor,
                                VoiceFileResolver voiceFileResolver,
                                BgmService bgmService,
                                TopicStatusAdvancer topicStatusAdvancer,
                                com.auteur.voice.VoiceClient voiceClient,
                                com.auteur.preset.TopicPresetResolver presetResolver,
                                com.auteur.runtimeconfig.RuntimeConfig runtimeConfig) {
        this.videoRenderer = videoRenderer;
        this.remotionRenderer = remotionRenderer;
        this.scriptRepository = scriptRepository;
        this.topicRepository = topicRepository;
        this.shotRepository = shotRepository;
        this.sectionRepository = sectionRepository;
        this.imageAssetRepository = imageAssetRepository;
        this.voiceAssetRepository = voiceAssetRepository;
        this.videoAssetRepository = videoAssetRepository;
        this.runService = runService;
        this.pipelineExecutor = pipelineExecutor;
        this.voiceFileResolver = voiceFileResolver;
        this.bgmService = bgmService;
        this.topicStatusAdvancer = topicStatusAdvancer;
        this.voiceClient = voiceClient;
        this.presetResolver = presetResolver;
        this.runtimeConfig = runtimeConfig;
    }

    public record RenderParams(Long voiceAssetId, String format,
                               Integer width, Integer height, Boolean markFinal) {}

    public Long renderAsync(Long scriptId, RenderParams p, String triggeredBy) {
        Map<String, Object> params = new HashMap<>();
        params.put("scriptId", scriptId);
        params.put("voiceAssetId", p.voiceAssetId());
        params.put("format", p.format());
        params.put("mode", "async");
        PipelineRun run = runService.start(PipelineStage.VIDEO, null, scriptId, params,
                triggeredBy != null ? triggeredBy : "API");
        Long runId = run.getId();
        pipelineExecutor.execute(() -> runWorker(scriptId, p, runId));
        return runId;
    }

    private void runWorker(Long scriptId, RenderParams p, Long runId) {
        try {
            runService.updateProgress(runId, 0, 1);
            WorkerInputs inputs = loadScriptShotsSections(scriptId, runId);
            if (inputs == null) return;
            Script script = inputs.script();
            List<StoryboardShot> shots = inputs.shots();
            List<ScriptSection> sections = inputs.sections();

            VoiceAsset voice = pickVoice(scriptId, p.voiceAssetId());
            String audioUrl = voice != null ? voice.getAudioUrl() : null;
            String subUrl   = voice != null ? voice.getSubtitleUrl() : null;

            // SRT 解析(PRECISE 时长对齐的核心输入)+ 解析每镜真实时长。失败安全回落,不影响主流程。
            ShotTimingResolver.Resolution timing = buildTimingMap(shots, sections, voice, runId);

            // introSec(SRT 前置静音)加到第一个有图 clip,使 clip 绝对时间与音频对齐。
            AssembledClips assembled = assembleClips(shots, timing, runId);
            List<VideoRenderer.ImageClip> clips = assembled.clips();
            int usedShots = assembled.usedShots();
            if (clips.isEmpty()) {
                runService.markFailed(runId, "script #" + scriptId + " 没有任何镜头有可用 image_asset");
                return;
            }

            // 兜底分辨率:RenderParams 没传时按横屏 1920×1080(任何 preset 都至少有 formatWidth/Height,
            // 但本入口被 ad-hoc 调用时 p.width()/height() 可能为空,留作 safety net)
            int width = p.width() != null && p.width() > 0 ? p.width() : 1920;
            int height = p.height() != null && p.height() > 0 ? p.height() : 1080;
            String format = p.format() != null && !p.format().isBlank() ? p.format() : "16:9";

            VideoRenderer.BgmConfig bgmCfg = null;
            Long bgmTrackId = null;
            BigDecimal bgmVolume = null;

            VideoRenderer.Result r = chooseRenderer(script).render(buildRequest(
                    scriptId, clips, audioUrl, subUrl, format, width, height, bgmCfg,
                    voice != null ? voice.getSubtitleStyle() : "standard",
                    script, voice));

            persistAndAdvance(scriptId, runId, p, voice, usedShots, r,
                    bgmCfg, bgmTrackId, bgmVolume, timing);
        } catch (RuntimeException e) {
            log.error("[制片] worker crashed runId={} scriptId={}: {}",
                    runId, scriptId, e.toString(), e);
            runService.markFailed(runId, e.toString());
        }
    }

    /**
     * 渲染完成后的收尾:清理 BGM 临时文件 → persistInNewTx 落库 → markDone + log。
     * 不在 @Transactional 边界内调用。
     */
    private void persistAndAdvance(Long scriptId, Long runId, RenderParams p,
                                   VoiceAsset voice, int usedShots, VideoRenderer.Result r,
                                   VideoRenderer.BgmConfig bgmCfg, Long bgmTrackId,
                                   BigDecimal bgmVolume,
                                   ShotTimingResolver.Resolution timing) {
        if (bgmCfg != null && bgmCfg.bgmFile() != null) {
            try { java.nio.file.Files.deleteIfExists(bgmCfg.bgmFile()); }
            catch (Exception e) { log.warn("[制片] 删除 BGM 本地缓存失败: {}", e.toString()); }
        }

        persistInNewTx(scriptId, voice != null ? voice.getId() : null, usedShots, p, r,
                bgmTrackId, bgmVolume,
                timing.strategy().name(), timing.reason());

        runService.markDone(runId, 1);
        log.info("[制片] runId={} scriptId={} done shots={} duration={}s url={}",
                runId, scriptId, usedShots, r.durationSeconds(), r.videoUrl());
    }

    /** 内部聚合结果:assembleClips 的输出。 */
    private record AssembledClips(List<VideoRenderer.ImageClip> clips, int usedShots) {}

    /** runWorker 早期校验阶段的输入聚合。 */
    private record WorkerInputs(Script script, List<StoryboardShot> shots, List<ScriptSection> sections) {}

    /**
     * 加载 script + shots + sections 并做空校验。
     * shots 为空时:markFailed + 返回 null,调用方早退。其他校验失败按 NotFoundException 抛出。
     */
    private WorkerInputs loadScriptShotsSections(Long scriptId, Long runId) {
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));
        List<StoryboardShot> shots = shotRepository.findByScriptIdOrderByShotIndexAsc(scriptId);
        if (shots.isEmpty()) {
            runService.markFailed(runId, "script #" + scriptId + " 没有分镜,先跑 STORYBOARD");
            return null;
        }
        List<ScriptSection> sections = sectionRepository.findByScriptIdOrderBySectionCodeAsc(scriptId);
        return new WorkerInputs(script, shots, sections);
    }

    /**
     * 加载 SRT cues(失败回落空)+ 调用 ShotTimingResolver.resolve 计算每镜时长。
     * 支持 https TOS URL 与 legacy 本地路径。
     */
    private ShotTimingResolver.Resolution buildTimingMap(List<StoryboardShot> shots,
                                                         List<ScriptSection> sections,
                                                         VoiceAsset voice,
                                                         Long runId) {
        String subUrl = voice != null ? voice.getSubtitleUrl() : null;
        List<SrtParser.Cue> cues = loadSrtCuesAny(subUrl, runId);
        Double voiceDurSec = voice != null && voice.getDurationSeconds() != null
                ? voice.getDurationSeconds().doubleValue() : null;
        ShotTimingResolver.Resolution timing = ShotTimingResolver.resolve(
                shots, sections, cues, voiceDurSec);
        log.info("[制片] runId={} timing strategy={} introSec={}s reason={}",
                runId, timing.strategy(), String.format("%.1f", timing.introSec()), timing.reason());
        return timing;
    }

    /**
     * 遍历 shots,挑图、按 timing.durations 拼 ImageClip。
     * introSec(SRT 前置静音)加到第一个有图 clip,使 clip 绝对时间与音频对齐。
     */
    private AssembledClips assembleClips(List<StoryboardShot> shots,
                                         ShotTimingResolver.Resolution timing,
                                         Long runId) {
        List<VideoRenderer.ImageClip> clips = new ArrayList<>();
        double cursor = 0.0;
        int usedShots = 0;
        boolean introApplied = false;
        for (int i = 0; i < shots.size(); i++) {
            StoryboardShot shot = shots.get(i);
            ImageAsset img = pickImageForShot(shot.getId());
            if (img == null || img.getFileUrl() == null || img.getFileUrl().isBlank()) {
                log.info("[制片] runId={} shot {} no image, skip", runId, shot.getId());
                continue;
            }
            double dur = timing.durations().get(i);
            if (!introApplied && timing.introSec() > 0.1) {
                dur += timing.introSec();
                introApplied = true;
            }
            if (dur <= 0) dur = runtimeConfig.getDouble("auteur.video.default-shot-sec", DEFAULT_SHOT_SEC_DEFAULT);
            clips.add(new VideoRenderer.ImageClip(
                    shot.getShotIndex() != null ? shot.getShotIndex() : usedShots,
                    img.getFileUrl(), cursor, dur, shot.getPromptZh(),
                    shot.getSectionCode(), shot.getAnchorText()));
            cursor += dur;
            usedShots++;
        }
        return new AssembledClips(clips, usedShots);
    }

    /** 优先 is_final 中最新的,fallback 全部里最新一张。null = 该 shot 没图。 */
    private ImageAsset pickImageForShot(Long shotId) {
        List<ImageAsset> all = imageAssetRepository.findByShotIdOrderByIdAsc(shotId);
        if (all.isEmpty()) return null;
        ImageAsset finalLatest = null;
        for (int i = all.size() - 1; i >= 0; i--) {
            ImageAsset a = all.get(i);
            if (Boolean.TRUE.equals(a.getIsFinal())) {
                finalLatest = a;
                break;
            }
        }
        return finalLatest != null ? finalLatest : all.get(all.size() - 1);
    }

    /** 用户指定 > script 的 final > 最新一条 > null。 */
    private VoiceAsset pickVoice(Long scriptId, Long requestedVoiceId) {
        if (requestedVoiceId != null) {
            return voiceAssetRepository.findById(requestedVoiceId)
                    .filter(v -> v.getScriptId().equals(scriptId))
                    .orElse(null);
        }
        VoiceAsset finalVoice = voiceAssetRepository
                .findFirstByScriptIdAndIsFinalTrueOrderByIdDesc(scriptId).orElse(null);
        if (finalVoice != null) return finalVoice;
        List<VoiceAsset> all = voiceAssetRepository.findByScriptIdOrderByIdDesc(scriptId);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 加载并解析 SRT,失败安全降级到 List.of()。
     * 支持 https/http URL(下载到临时文件)与 legacy /api/files/voice/x.srt 本地路径。
     */
    private List<SrtParser.Cue> loadSrtCuesAny(String subUrl, Long runId) {
        if (subUrl == null || subUrl.isBlank()) return List.of();
        if (subUrl.startsWith("http://") || subUrl.startsWith("https://")) {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("voice-srt-", ".srt");
                try (java.io.InputStream in = java.net.URI.create(subUrl).toURL().openStream()) {
                    Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return SrtParser.parseFile(tmp);
            } catch (Exception e) {
                log.warn("[制片] runId={} SRT 下载/解析失败(http) url={}: {}", runId, subUrl, e.toString());
                return List.of();
            } finally {
                if (tmp != null) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                }
            }
        }
        Path srtPath = voiceFileResolver.resolveStrict(subUrl);
        if (srtPath == null || !Files.exists(srtPath)) {
            log.warn("[制片] runId={} 无法解析 subtitleUrl: {}", runId, subUrl);
            return List.of();
        }
        try {
            return SrtParser.parseFile(srtPath);
        } catch (IOException e) {
            log.warn("[制片] runId={} parse SRT failed path={}: {}", runId, srtPath, e.toString());
            return List.of();
        }
    }

    /** 所有内容类型统一走 Remotion;Remotion 未启用时回落 ffmpeg/mock。 */
    private VideoRenderer chooseRenderer(Script script) {
        if (remotionRenderer.isEmpty()) return videoRenderer;
        Topic topic = topicRepository.findById(script.getTopicId()).orElse(null);
        com.auteur.preset.PresetContext ctx = topic == null ? null : presetResolver.forTopic(topic);
        log.info("[制片] scriptId={} topicId={} preset={} → Remotion",
                script.getId(), topic != null ? topic.getId() : null,
                ctx != null ? ctx.preset().getName() : null);
        return remotionRenderer.get();
    }

    /** 构造 Request。preset.composition_id 决定 Remotion composition。 */
    private VideoRenderer.Request buildRequest(
            Long scriptId, List<VideoRenderer.ImageClip> clips,
            String audioUrl, String subUrl, String format, int width, int height,
            VideoRenderer.BgmConfig bgmCfg, String subtitleStyle, Script script,
            VoiceAsset voice) {
        Topic topic = topicRepository.findById(script.getTopicId()).orElse(null);
        String personaJson = topic != null ? buildFallbackPersona(topic) : "{}";
        com.auteur.preset.PresetContext ctx = topic == null ? null : presetResolver.forTopic(topic);
        VideoRenderer.HookConfig hook = topic != null ? prepareHook(clips, topic, voice, ctx) : null;
        String compositionId = ctx != null ? ctx.preset().getCompositionId() : null;
        String presetName = ctx != null ? ctx.preset().getName() : null;
        // preset.watermark_text 决定水印文本;null/空 = 不加水印。Remotion 的 Watermark 组件已对空值短路。
        String watermarkText = ctx != null ? ctx.preset().getWatermarkText() : null;
        return new VideoRenderer.Request(
                scriptId, clips, audioUrl, subUrl, format, width, height, bgmCfg, subtitleStyle,
                presetName,
                personaJson,
                null,
                topic != null ? topic.getId() : null,
                hook,
                compositionId,
                watermarkText);
    }

    /**
     * hook 段准备:从 image_asset 均匀抽 12 张 + 用 topic.title 临时跑 voice。
     * 任一步失败返回 null,主流程退回"无 hook"原行为。
     */
    private VideoRenderer.HookConfig prepareHook(
            List<VideoRenderer.ImageClip> clips, Topic topic, VoiceAsset voice,
            com.auteur.preset.PresetContext ctx) {
        try {
            if (clips == null || clips.size() < 6) {
                log.info("[制片] hook: clips 不足 6 张,跳过");
                return null;
            }
            String title = topic.getTitle();
            if (title == null || title.isBlank()) {
                log.info("[制片] hook: topic.title 空,跳过");
                return null;
            }

            int hookCount = 12;
            int n = clips.size();
            List<String> hookUrls = new java.util.ArrayList<>(hookCount);
            for (int i = 0; i < hookCount; i++) {
                int idx = (int) Math.round((double) i * (n - 1) / (hookCount - 1));
                hookUrls.add(clips.get(idx).imageUrl());
            }

            // hook voice 用 topic.title,音色与主 voice 一致
            String voiceModel = voice != null ? voice.getModel() : null;
            VoiceClient.Request hookReq = new VoiceClient.Request(
                    topic.getId() != null ? topic.getId() : 0L,
                    title,
                    voiceModel, voiceModel,
                    java.math.BigDecimal.ONE, 0, "standard",
                    false  // hook 短文本不需要 SentenceSplitter / trimmer
            );
            log.info("[制片] hook: 跑独立 TTS 合成 title={}", title);
            long t0 = System.currentTimeMillis();
            VoiceClient.Result hookResult = voiceClient.synthesize(hookReq);
            log.info("[制片] hook: voice 合成完成 ms={} url={}",
                    System.currentTimeMillis() - t0, hookResult.audioUrl());

            // 默认 hook 时长 4s(参照视频前 4s 快切节奏)
            String pageFlipUrl = (ctx != null && ctx.preset().getHookPageFlipSoundUrl() != null)
                    ? ctx.preset().getHookPageFlipSoundUrl() : "";
            return new VideoRenderer.HookConfig(hookUrls, hookResult.audioUrl(), title, 4.0,
                    pageFlipUrl);
        } catch (Exception e) {
            log.warn("[制片] hook 准备失败,本次无 hook 段: {}", e.toString());
            return null;
        }
    }

    /** Remotion 需要 personaJson 字段;preset 不消费 persona 时给最简兜底。 */
    private static String buildFallbackPersona(Topic topic) {
        String name = topic.getTitle() != null && !topic.getTitle().isBlank()
                ? topic.getTitle() : (topic.getProtagonist() != null ? topic.getProtagonist() : "旁白");
        String identity = topic.getProtagonist() != null && !topic.getProtagonist().isBlank()
                ? topic.getProtagonist() : "—";
        return String.format("{\"name\":\"%s\",\"identity\":\"%s\"}",
                name.replace("\"", "\\\""), identity.replace("\"", "\\\""));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VideoAsset persistInNewTx(Long scriptId, Long voiceAssetId, int shotCount,
                                     RenderParams p, VideoRenderer.Result r,
                                     Long bgmTrackId, BigDecimal bgmVolume,
                                     String timingStrategy, String timingNote) {
        VideoAsset v = new VideoAsset();
        v.setScriptId(scriptId);
        v.setVoiceAssetId(voiceAssetId);
        v.setVideoUrl(r.videoUrl());
        v.setDurationSeconds(r.durationSeconds());
        v.setWidth(r.width());
        v.setHeight(r.height());
        v.setFormat(r.format());
        v.setShotCount(shotCount);
        v.setCostYuan(r.costYuan() != null ? r.costYuan() : BigDecimal.ZERO);
        v.setBgmTrackId(bgmTrackId);
        v.setBgmVolume(bgmVolume);
        v.setTimingStrategy(timingStrategy);
        v.setTimingNote(timingNote != null && timingNote.length() > 500
                ? timingNote.substring(0, 500) : timingNote);
        boolean markFinal = Boolean.TRUE.equals(p.markFinal());
        v.setIsFinal(markFinal);
        v = videoAssetRepository.save(v);
        if (markFinal) {
            FinalAssetMarker.clearOthers(
                    v,
                    videoAssetRepository.findByScriptIdOrderByIdDesc(scriptId),
                    VideoAsset::getId,
                    VideoAsset::setIsFinal,
                    videoAssetRepository::save,
                    true);
            // final 视频落库 → 自动把 topic 推到 PRODUCED。幂等,只在 SCHEDULED 时切。
            try {
                Long topicId = scriptRepository.findById(scriptId).map(Script::getTopicId).orElse(null);
                topicStatusAdvancer.advance(topicId, TopicStatus.SCHEDULED, TopicStatus.PRODUCED);
            } catch (RuntimeException ex) {
                log.warn("[制片] topic status advance 失败 scriptId={} err={}", scriptId, ex.toString());
            }
        }
        return v;
    }
}
