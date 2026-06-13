package com.auteur.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.ImageAsset;
import com.auteur.domain.ImageAssetRepository;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineStage;
import com.auteur.domain.StoryboardShot;
import com.auteur.domain.StoryboardShotRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.ModelRegistry;
import com.auteur.llm.PromptTemplateService;
import com.auteur.pipeline.PipelineRunService;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class ImageAuditService {

    /** 阈值:≥ PASS 直接通过;< PASS 且 ≥ REGEN 触发 1 次重生;< REGEN 标 MANUAL。
     *  默认值通过 RuntimeConfig 读 DB 覆盖。 */
    private static final int PASS_THRESHOLD_DEFAULT = 80;
    private static final int REGEN_THRESHOLD_DEFAULT = 60;
    /** 单镜最多重生几次 */
    private static final int MAX_REGEN_PER_SHOT_DEFAULT = 1;

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ModelRegistry modelRegistry;
    private final ScriptRepository scriptRepository;
    private final TopicRepository topicRepository;
    private final StoryboardShotRepository shotRepository;
    private final ImageAssetRepository assetRepository;
    private final ImageGenService imageGenService;
    private final PipelineRunService runService;
    private final Executor pipelineExecutor;
    private final Executor imageWorkExecutor;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImageAuditService(LlmClient llmClient, PromptTemplateService promptService,
                             ModelRegistry modelRegistry,
                             ScriptRepository scriptRepository, TopicRepository topicRepository,
                             StoryboardShotRepository shotRepository, ImageAssetRepository assetRepository,
                             ImageGenService imageGenService, PipelineRunService runService,
                             @Qualifier("pipelineExecutor") Executor pipelineExecutor,
                             @Qualifier("imageWorkExecutor") Executor imageWorkExecutor,
                             com.auteur.runtimeconfig.RuntimeConfig runtimeConfig) {
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.modelRegistry = modelRegistry;
        this.scriptRepository = scriptRepository;
        this.topicRepository = topicRepository;
        this.shotRepository = shotRepository;
        this.assetRepository = assetRepository;
        this.imageGenService = imageGenService;
        this.runService = runService;
        this.pipelineExecutor = pipelineExecutor;
        this.imageWorkExecutor = imageWorkExecutor;
        this.runtimeConfig = runtimeConfig;
    }

    private int passThreshold() { return runtimeConfig.getInt("auteur.image.audit.pass-threshold", PASS_THRESHOLD_DEFAULT); }
    private int regenThreshold() { return runtimeConfig.getInt("auteur.image.audit.regen-threshold", REGEN_THRESHOLD_DEFAULT); }
    private int maxRegenPerShot() { return runtimeConfig.getInt("auteur.image.audit.max-regen-per-shot", MAX_REGEN_PER_SHOT_DEFAULT); }

    /** 同步审核:兼容原 API。长任务建议改走 auditScriptAsync。 */
    @Transactional
    public List<ImageAsset> auditScript(Long scriptId) {
        PipelineRun run = runService.start(
                PipelineStage.IMAGEAUDIT, null, scriptId,
                Map.of("scriptId", scriptId, "mode", "sync"), "API");
        try {
            List<ImageAsset> result = doAudit(scriptId, 0, run.getId());
            runService.markDone(run.getId(), result.size());
            return result;
        } catch (RuntimeException e) {
            runService.markFailed(run.getId(), e.toString());
            throw e;
        }
    }

    /**
     * 异步审核:立即返回 runId。worker 在 pipelineExecutor 跑,每 shot 之间检查 pause/cancel。
     */
    public Long auditScriptAsync(Long scriptId, String triggeredBy) {
        Map<String, Object> params = Map.of("scriptId", scriptId, "mode", "async");
        PipelineRun run = runService.start(PipelineStage.IMAGEAUDIT, null, scriptId, params,
                triggeredBy != null ? triggeredBy : "API");
        Long runId = run.getId();
        pipelineExecutor.execute(() -> runWorker(scriptId, 0, runId));
        return runId;
    }

    /** Resume：从 last_completed_index 接着审。 */
    public void resumeAsync(Long runId, Long scriptId, int startIndex) {
        pipelineExecutor.execute(() -> runWorker(scriptId, startIndex, runId));
    }

    /**
     * 单图重审:用户在生图列表里手动点某张图重审。
     * 跟 auditScriptAsync 不同的语义:totalItems = 1,且不走 auto-regen。
     */
    public Long auditAssetAsync(Long assetId, String triggeredBy) {
        ImageAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NotFoundException("ImageAsset not found: " + assetId));
        StoryboardShot shot = shotRepository.findById(asset.getShotId())
                .orElseThrow(() -> new IllegalStateException(
                        "Shot not found for asset " + assetId + " shotId=" + asset.getShotId()));
        Long scriptId = shot.getScriptId();
        Map<String, Object> params = Map.of(
                "scriptId", scriptId,
                "shotId", shot.getId(),
                "assetId", assetId,
                "mode", "async",
                "scope", "single-asset");
        PipelineRun run = runService.start(PipelineStage.IMAGEAUDIT, null, scriptId, params,
                triggeredBy != null ? triggeredBy : "API");
        Long runId = run.getId();
        pipelineExecutor.execute(() -> {
            try {
                runService.updateProgress(runId, 0, 1);
                Script script = scriptRepository.findById(scriptId)
                        .orElseThrow(() -> new IllegalStateException("Script not found: " + scriptId));
                Topic topic = topicRepository.findById(script.getTopicId())
                        .orElseThrow(() -> new IllegalStateException("Topic not found: " + script.getTopicId()));
                try {
                    auditOneInNewTx(topic, shot, asset);
                } catch (Exception e) {
                    log.warn("[ImageAudit] runId={} assetId={} audit failed, mark MANUAL: {}",
                            runId, assetId, e.toString());
                    asset.setReviewDecision("MANUAL");
                    asset.setReviewIssues(TextUtils.truncate("audit failed: " + e, 500));
                    saveAssetInNewTx(asset);
                }
                runService.markDone(runId, 1);
                log.info("[ImageAudit] runId={} assetId={} single-asset audit done", runId, assetId);
            } catch (RuntimeException e) {
                log.error("[ImageAudit] single-asset worker crashed runId={} assetId={}: {}",
                        runId, assetId, e.toString(), e);
                runService.markFailed(runId, e.toString());
            }
        });
        return runId;
    }

    /** 手动重审专用:只跑 callAudit + applyAudit,不触发 auto-regen。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void auditOneInNewTx(Topic topic, StoryboardShot shot, ImageAsset asset) {
        ImageAuditResult res = callAudit(topic, shot, asset);
        applyAudit(asset, res);
    }

    /** failure 兜底落库专用,独立事务。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void saveAssetInNewTx(ImageAsset asset) {
        assetRepository.save(asset);
    }

    private void runWorker(Long scriptId, int startIndex, Long runId) {
        try {
            Script script = scriptRepository.findById(scriptId)
                    .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));
            Topic topic = topicRepository.findById(script.getTopicId())
                    .orElseThrow(() -> new IllegalStateException("Topic not found"));
            List<StoryboardShot> shots = shotRepository.findByScriptIdOrderByShotIndexAsc(scriptId);
            int total = shots.size();
            runService.updateProgress(runId, startIndex, total);

            AtomicInteger completedCount = new AtomicInteger(startIndex);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = startIndex; i < shots.size(); i++) {
                if (runService.shouldPause(runId)) {
                    awaitAll(futures);
                    if (runService.isCancelled(runId)) {
                        log.info("[ImageAudit] runId={} cancelled at index={}", runId, i);
                    } else {
                        runService.markPaused(runId, i);
                        log.info("[ImageAudit] runId={} paused at index={}", runId, i);
                    }
                    return;
                }

                StoryboardShot shot = shots.get(i);
                ImageAsset latest = assetRepository.findFirstByShotIdOrderByIdDesc(shot.getId()).orElse(null);
                if (latest == null) {
                    log.warn("[ImageAudit] runId={} shot {} has no asset, skip", runId, shot.getId());
                    runService.updateProgress(runId, completedCount.incrementAndGet(), total);
                    continue;
                }

                final ImageAsset finalAsset = latest;
                CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                    try {
                        auditOne(topic, shot, finalAsset);
                    } catch (Exception e) {
                        log.warn("[ImageAudit] runId={} shot {} audit failed, mark MANUAL: {}",
                                runId, shot.getId(), e.toString());
                        finalAsset.setReviewDecision("MANUAL");
                        finalAsset.setReviewIssues(TextUtils.truncate("audit failed: " + e, 500));
                        assetRepository.save(finalAsset);
                    }
                    runService.updateProgress(runId, completedCount.incrementAndGet(), total);
                }, imageWorkExecutor);
                futures.add(f);
            }

            awaitAll(futures);
            runService.markDone(runId, total);
        } catch (RuntimeException e) {
            log.error("[ImageAudit] worker crashed runId={}: {}", runId, e.toString(), e);
            runService.markFailed(runId, e.toString());
        }
    }

    private void awaitAll(List<CompletableFuture<Void>> futures) {
        if (!futures.isEmpty()) {
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (Exception e) {
                log.warn("[ImageAudit] awaitAll 异常: {}", e.toString());
            }
        }
    }

    /** 同步路径骨架：跟 worker 一致但不查 pause（同步 HTTP 没法暂停）。 */
    private List<ImageAsset> doAudit(Long scriptId, int startIndex, Long runId) {
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));
        Topic topic = topicRepository.findById(script.getTopicId())
                .orElseThrow(() -> new IllegalStateException("Topic not found"));
        List<StoryboardShot> shots = shotRepository.findByScriptIdOrderByShotIndexAsc(scriptId);
        int total = shots.size();
        runService.updateProgress(runId, startIndex, total);

        List<ImageAsset> processed = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger completedCount = new AtomicInteger(startIndex);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = startIndex; i < shots.size(); i++) {
            StoryboardShot shot = shots.get(i);
            ImageAsset latest = assetRepository.findFirstByShotIdOrderByIdDesc(shot.getId()).orElse(null);
            if (latest == null) {
                log.warn("[ImageAudit] shot {} has no asset, skip", shot.getId());
                runService.updateProgress(runId, completedCount.incrementAndGet(), total);
                continue;
            }
            final ImageAsset finalAsset = latest;
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                try {
                    processed.add(auditOne(topic, shot, finalAsset));
                } catch (Exception e) {
                    log.warn("[ImageAudit] shot {} audit failed, mark MANUAL: {}", shot.getId(), e.toString());
                    finalAsset.setReviewDecision("MANUAL");
                    finalAsset.setReviewIssues(TextUtils.truncate("audit failed: " + e, 500));
                    processed.add(assetRepository.save(finalAsset));
                }
                runService.updateProgress(runId, completedCount.incrementAndGet(), total);
            }, imageWorkExecutor);
            futures.add(f);
        }

        awaitAll(futures);
        return processed;
    }

    private ImageAsset auditOne(Topic topic, StoryboardShot shot, ImageAsset asset) {
        ImageAuditResult res = callAudit(topic, shot, asset);

        applyAudit(asset, res);

        // 首张 PASS final 锁定为该 script 的主角基准照(空镜不算)。
        // 后续同 script 的非空镜镜头生图时会拿这张图做 image-to-image reference。
        if (Boolean.TRUE.equals(asset.getIsFinal()) && !"空镜".equals(shot.getShotType())) {
            try {
                Script s = scriptRepository.findById(shot.getScriptId()).orElse(null);
                if (s != null && s.getProtagonistRefAssetId() == null) {
                    s.setProtagonistRefAssetId(asset.getId());
                    scriptRepository.save(s);
                    log.info("[ImageAudit] script={} locked protagonist ref to assetId={} (shot {})",
                            s.getId(), asset.getId(), shot.getShotIndex());
                }
            } catch (Exception e) {
                log.warn("[ImageAudit] failed to lock protagonist ref for shot {}: {}",
                        shot.getId(), e.toString());
            }
        }

        // REGENERATE:再生一次,新图不再递归审,留人下一轮再审
        if ("REGENERATE".equals(asset.getReviewDecision())
                && countAssets(shot.getId()) <= maxRegenPerShot()) {
            try {
                ImageAsset regen = imageGenService.generateOne(shot);
                regen.setReviewIssues("regen due to score=" + res.getScore());
                return assetRepository.save(regen);
            } catch (Exception e) {
                log.warn("[ImageAudit] regen failed for shot {}: {}", shot.getId(), e.toString());
            }
        }
        return asset;
    }

    private ImageAuditResult callAudit(Topic topic, StoryboardShot shot, ImageAsset asset) {
        PromptTemplateService.Rendered tpl = promptService.render("image_audit", Map.of(
                "dynasty", TextUtils.safe(topic.getDynasty()),
                "shot_type", TextUtils.safe(shot.getShotType()),
                "prompt_zh", TextUtils.safe(shot.getPromptZh())
        ));
        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("image_audit")
                .relatedType("IMAGE")
                .relatedId(asset.getId())
                .model(modelRegistry.modelFor("image_audit"))
                .temperature(tpl.temperature() != null ? tpl.temperature() : 0.0)
                .build();
        LlmResult r = llmClient.chatWithImage(spec, tpl.system(), tpl.user(), asset.getFileUrl());
        return parseResult(r.getContent());
    }

    private void applyAudit(ImageAsset asset, ImageAuditResult res) {
        if (res == null) {
            asset.setReviewDecision("MANUAL");
            asset.setReviewIssues("audit returned null");
            assetRepository.save(asset);
            return;
        }
        Integer score = res.getScore();
        if (score != null) {
            asset.setReviewScore(BigDecimal.valueOf(score));
        }
        String decision = decideFromScore(score, res.getDecision());
        asset.setReviewDecision(decision);
        asset.setReviewIssues(joinIssues(res.getIssues()));
        if ("PASS".equals(decision)) {
            asset.setIsFinal(true);
        }
        assetRepository.save(asset);
    }

    /** 优先用模型给的 decision;如果它没给,用阈值兜底。 */
    private String decideFromScore(Integer score, String modelDecision) {
        if (modelDecision != null && (modelDecision.equals("PASS")
                || modelDecision.equals("REGENERATE")
                || modelDecision.equals("MANUAL"))) {
            return modelDecision;
        }
        if (score == null) return "MANUAL";
        if (score >= passThreshold()) return "PASS";
        if (score >= regenThreshold()) return "REGENERATE";
        return "MANUAL";
    }

    private long countAssets(Long shotId) {
        return assetRepository.countByShotId(shotId);
    }

    private ImageAuditResult parseResult(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String json = TextUtils.stripCodeFence(raw).trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        json = json.substring(start, end + 1);
        try {
            return objectMapper.readValue(json, ImageAuditResult.class);
        } catch (Exception e) {
            log.warn("[ImageAudit] parse failed: {} | raw={}", e.toString(),
                    raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
            return null;
        }
    }

    private static String joinIssues(List<String> issues) {
        if (issues == null || issues.isEmpty()) return null;
        String s = String.join(" | ", issues);
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
