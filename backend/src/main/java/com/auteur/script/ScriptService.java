package com.auteur.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.JsonExtractUtils;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.CoverAssetRepository;
import com.auteur.domain.FactCheckIssueRepository;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineStage;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.ScriptSection;
import com.auteur.domain.ScriptSectionRepository;
import com.auteur.domain.ScriptStatus;
import com.auteur.domain.SeriesHookRepository;
import com.auteur.domain.StoryboardShotRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.domain.VideoAssetRepository;
import com.auteur.domain.VoiceAssetRepository;
import com.auteur.insights.InsightDtos.HookPerformancePack;
import com.auteur.insights.InsightService;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.JsonHealer;
import com.auteur.llm.PromptTemplateService;
import com.auteur.pipeline.PipelineRunService;
import com.auteur.preset.PresetInputInjector;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 编剧 / Screenwriter
 * 把选题(topic)展开成完整脚本(五段 A 钩子 / B 累积 / C 中段 / D 揭秘 / E 留白)。
 * 从 preset.script_prompt_yaml 渲染,preset_input_json 注入变量。
 */
@Slf4j
@Service
public class ScriptService {

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final TopicRepository topicRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptSectionRepository sectionRepository;
    private final PipelineRunService runService;
    private final Executor pipelineExecutor;
    private final HookExtractor hookExtractor;
    private final StoryboardShotRepository shotRepository;
    private final FactCheckIssueRepository issueRepository;
    private final VoiceAssetRepository voiceAssetRepository;
    private final VideoAssetRepository videoAssetRepository;
    private final CoverAssetRepository coverAssetRepository;
    private final SeriesHookRepository seriesHookRepository;
    private final InsightService insightService;
    private final ScriptCriticService scriptCriticService;
    private final com.auteur.video.DirectorNoteService directorNoteService;
    private final com.auteur.preset.TopicPresetResolver presetResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScriptService(LlmClient llmClient,
                         PromptTemplateService promptService,
                         TopicRepository topicRepository,
                         ScriptRepository scriptRepository,
                         ScriptSectionRepository sectionRepository,
                         PipelineRunService runService,
                         @Qualifier("pipelineExecutor") Executor pipelineExecutor,
                         HookExtractor hookExtractor,
                         StoryboardShotRepository shotRepository,
                         FactCheckIssueRepository issueRepository,
                         VoiceAssetRepository voiceAssetRepository,
                         VideoAssetRepository videoAssetRepository,
                         CoverAssetRepository coverAssetRepository,
                         SeriesHookRepository seriesHookRepository,
                         InsightService insightService,
                         ScriptCriticService scriptCriticService,
                         com.auteur.video.DirectorNoteService directorNoteService,
                         com.auteur.preset.TopicPresetResolver presetResolver) {
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.topicRepository = topicRepository;
        this.scriptRepository = scriptRepository;
        this.sectionRepository = sectionRepository;
        this.runService = runService;
        this.pipelineExecutor = pipelineExecutor;
        this.hookExtractor = hookExtractor;
        this.shotRepository = shotRepository;
        this.issueRepository = issueRepository;
        this.voiceAssetRepository = voiceAssetRepository;
        this.videoAssetRepository = videoAssetRepository;
        this.coverAssetRepository = coverAssetRepository;
        this.seriesHookRepository = seriesHookRepository;
        this.insightService = insightService;
        this.scriptCriticService = scriptCriticService;
        this.directorNoteService = directorNoteService;
        this.presetResolver = presetResolver;
    }

    @Transactional
    public Script generate(Long topicId) {
        return generate(topicId, null);
    }

    /** 带锚点(用户自由指令)的版本 —— 重新生成按钮走这条。anchor=null/空白 等价于无指令。 */
    @Transactional
    public Script generate(Long topicId, String anchor) {
        PipelineRun run = runService.start(
                PipelineStage.SCRIPT, topicId, null,
                Map.of("topicId", topicId, "mode", "sync", "hasAnchor", anchor != null && !anchor.isBlank()), "API");
        try {
            Script s = doGenerateInNewTx(topicId, anchor);
            runService.setScriptId(run.getId(), s.getId());
            runService.markDone(run.getId(), 1);
            return s;
        } catch (RuntimeException e) {
            runService.markFailed(run.getId(), e.toString());
            throw e;
        }
    }

    /**
     * 异步脚本生成:立即返回 runId,worker 在 pipelineExecutor 跑 LLM。
     * UI 通过 GET /api/runs/{runId} 轮询进度,DONE 后用 run.scriptId 跳到脚本详情。
     * 生成成功会 setScriptId 回写到 PipelineRun,这样列表/工作台能正确反查到脚本(顺手把孤儿 run 的根因解掉)。
     */
    public Long generateAsync(Long topicId, String triggeredBy) {
        return generateAsync(topicId, null, triggeredBy);
    }

    public Long generateAsync(Long topicId, String anchor, String triggeredBy) {
        Map<String, Object> params = Map.of(
                "topicId", topicId,
                "mode", "async",
                "hasAnchor", anchor != null && !anchor.isBlank());
        return runService.runAsync(PipelineStage.SCRIPT, topicId, null, params, triggeredBy, "Script",
                runId -> {
                    Script s = doGenerateInNewTx(topicId, anchor);
                    runService.setScriptId(runId, s.getId());
                    return 1;
                });
    }

    /**
     * 异步路径不在 @Transactional 边界里,但脚本/章节落库要有事务,所以独立成一段 REQUIRES_NEW。
     * 同步路径外层已经有 @Transactional,REQUIRES_NEW 会嵌套也没坏处(Hibernate 会拿到一个独立事务)。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Script doGenerateInNewTx(Long topicId, String anchor) {
        return doGenerate(topicId, anchor);
    }

    private Script doGenerate(Long topicId, String anchor) {
        long t0 = System.currentTimeMillis();
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new NotFoundException("Topic not found: " + topicId));

        // 每 topic 一脚本:生成新脚本前先 cascade 删除该 topic 下的所有旧脚本
        // (连带 sections / shots / images / voice / video / cover / bgm-choice)。
        // published_video / pipeline_run 是软引用 script_id,不会被删,保留作历史/审计。
        int removed = scriptRepository.deleteByTopicId(topicId);
        if (removed > 0) {
            log.info("[编剧] topicId={} 覆盖模式:删除旧脚本 {} 条(及其下游 cascade)", topicId, removed);
        }

        Script script = new Script();
        script.setTopicId(topicId);
        script.setVersion(1);
        script.setStatus(ScriptStatus.DRAFT);
        script = scriptRepository.save(script);
        log.info("[编剧] start topicId={} scriptId={} version=1 (overwrite mode)",
                topicId, script.getId());

        script = populateScriptFromLlm(script, topic, anchor);

        if (topic.getStatus() == TopicStatus.DRAFT) {
            topic.setStatus(TopicStatus.SCHEDULED);
            topicRepository.save(topic);
        }
        log.info("[编剧] done topicId={} scriptId={} totalMs={}",
                topicId, script.getId(), System.currentTimeMillis() - t0);

        // 异步抽下集钩子 —— 失败只 log,不影响主流程返回
        final Long sId = script.getId();
        pipelineExecutor.execute(() -> hookExtractor.extractAsync(sId));

        return script;
    }

    /**
     * 原地重新生成 —— 反查 topicId,清空下游产物(分镜/图/语音/视频/封面/系列钩子/事实核查),
     * 用 LLM 重写 fullText 与 sections,但保留 Script.id 与 version 不变。
     * 上游(选题)和下游引用关系都不会被改写,仅"内容"被新版替换。
     */
    public Long regenerateInPlaceAsync(Long scriptId, String anchor, String triggeredBy) {
        Script existing = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));
        Long topicId = existing.getTopicId();
        Map<String, Object> params = Map.of(
                "topicId", topicId,
                "scriptId", scriptId,
                "mode", "async",
                "inPlace", true,
                "hasAnchor", anchor != null && !anchor.isBlank());
        return runService.runAsync(PipelineStage.SCRIPT, topicId, scriptId, params, triggeredBy, "Script(regen)",
                runId -> {
                    doRegenerateInPlaceInNewTx(scriptId, anchor);
                    return 1;
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Script doRegenerateInPlaceInNewTx(Long scriptId, String anchor) {
        return doRegenerateInPlace(scriptId, anchor);
    }

    private Script doRegenerateInPlace(Long scriptId, String anchor) {
        long t0 = System.currentTimeMillis();
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));
        Long topicId = script.getTopicId();
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new NotFoundException("Topic not found: " + topicId));

        log.info("[编剧] regen-in-place start scriptId={} topicId={} version={} hasAnchor={}",
                scriptId, topicId, script.getVersion(), anchor != null && !anchor.isBlank());

        // 清空下游 —— image_asset 由 storyboard_shot 的 FK CASCADE 带走;
        // factcheck/voice/video/cover/series_hook 都直接按 script_id(或 from_script_id)删
        shotRepository.deleteByScriptId(scriptId);
        issueRepository.deleteByScriptId(scriptId);
        voiceAssetRepository.deleteByScriptId(scriptId);
        videoAssetRepository.deleteByScriptId(scriptId);
        coverAssetRepository.deleteByScriptId(scriptId);
        seriesHookRepository.deleteByFromScriptId(scriptId);
        sectionRepository.deleteByScriptId(scriptId);
        log.info("[编剧] regen-in-place wiped downstream scriptId={}", scriptId);

        script = populateScriptFromLlm(script, topic, anchor);

        log.info("[编剧] regen-in-place done scriptId={} totalMs={}",
                scriptId, System.currentTimeMillis() - t0);

        final Long sId = script.getId();
        pipelineExecutor.execute(() -> hookExtractor.extractAsync(sId));

        return script;
    }

    /**
     * 渲染 prompt → 调 LLM → 解析校验 → 把 fullText/wordCount/durationSeconds/modelUsed 写回入参 script,
     * 并把 5 段 ScriptSection 落库。
     */
    private Script populateScriptFromLlm(Script script, Topic topic, String anchor) {
        com.auteur.preset.PresetContext ctx = presetResolver.forTopic(topic);

        Map<String, Object> params = new HashMap<>();
        params.put("title", TextUtils.safe(topic.getTitle()));
        params.put("dynasty", TextUtils.safe(topic.getDynasty()));
        params.put("genre", TextUtils.safe(topic.getGenre()));
        params.put("protagonist", TextUtils.safe(topic.getProtagonist()));
        params.put("hook_type", TextUtils.safe(topic.getHookType()));
        params.put("emotion", TextUtils.safe(topic.getEmotion()));
        params.put("duration_minutes", topic.getDurationMinutes() == null ? 6 : topic.getDurationMinutes());
        params.put("historical_reference", TextUtils.safe(topic.getHistoricalReference()));
        params.put("anchor_block", buildAnchorBlock(anchor));
        params.put("director_vision_block",
                topic.getDirectorNote() != null && !topic.getDirectorNote().isBlank()
                        ? topic.getDirectorNote()
                        : "(本片未配置导演笔记;按默认 vibe:沉重克制 / 快入慢出)");
        try {
            HookPerformancePack hp = insightService.buildHookPerformancePack(null, 30);
            params.put("hook_top", hp.topHooks());
            params.put("hook_bottom", hp.bottomHooks());
            log.info("[编剧] hook-pack sample={} topChars={} bottomChars={}",
                    hp.sample(), hp.topHooks().length(), hp.bottomHooks().length());
        } catch (RuntimeException e) {
            log.warn("[编剧] hook-pack prep failed, fall back: {}", e.toString());
            params.put("hook_top", "（钩子表现数据加载失败,沿用 yaml 自带 5 种钩子）");
            params.put("hook_bottom", "（同上）");
        }

        PresetInputInjector.inject(objectMapper, params, topic.getPresetInputJson(), topic.getId(), "[编剧]");
        // 部分 preset.yaml 用 {{identity_card_nodes}} 这类专用变量;若 preset_input_json 含 identity_card.nodes,展开它
        injectIdentityCardNodesIfPresent(params, topic.getPresetInputJson(), topic.getId());

        PromptTemplateService.Rendered tpl = promptService.renderInline(ctx.preset().getScriptPromptYaml(), params);
        String operation = "script_" + ctx.preset().getName();
        if (script.getId() != null && topic.getPresetVersionUsed() == null) {
            topic.setPresetVersionUsed(ctx.preset().getCurrentVersion());
            topicRepository.save(topic);
        }

        String model = pickModel(topic, tpl);
        Double temperature = tpl.temperature() != null ? tpl.temperature() : 0.7;
        script.setModelUsed(model);

        LlmCallSpec spec = LlmCallSpec.builder()
                .operation(operation)
                .relatedType("SCRIPT")
                .relatedId(script.getId())
                .model(model)
                .temperature(temperature)
                .build();
        LlmResult result = llmClient.chat(spec, tpl.system(), tpl.user());
        log.info("[编剧] topicId={} scriptId={} preset={} model={} chars={} inTok={} outTok={} ms={}",
                topic.getId(), script.getId(), ctx.preset().getName(), model,
                result.getContent() == null ? 0 : result.getContent().length(),
                result.getInputTokens(), result.getOutputTokens(), result.getDurationMs());

        ScriptDraft draft = parseDraft(result.getContent());
        validate(draft);

        // 自审循环:出稿后自检,不过则带反馈重投 1 次。失败/二投仍不过 → warn 放行降级。
        ScriptCriticResult critic = scriptCriticService.audit(draft, topic, script.getId());
        if ("REWRITE".equals(critic.getDecision())) {
            log.warn("[编剧] critic 不通过 score={} issues={} → 触发反馈重投",
                    critic.getScore(), critic.getIssues());
            String feedback = critic.getFeedbackForRewrite();
            String rewriteUser = tpl.user()
                    + "\n\n【critic 反馈 — 必须修正下列问题,保留原稿核心,不要全盘重写】\n"
                    + (feedback == null || feedback.isBlank() ? "(critic 未给出可执行反馈,请按硬性纪律自查)" : feedback);
            LlmCallSpec rewriteSpec = LlmCallSpec.builder()
                    .operation(operation + "_rewrite")
                    .relatedType("SCRIPT")
                    .relatedId(script.getId())
                    .model(model)
                    .temperature(temperature)
                    .build();
            try {
                LlmResult rewriteResult = llmClient.chat(rewriteSpec, tpl.system(), rewriteUser);
                ScriptDraft rewriteDraft = parseDraft(rewriteResult.getContent());
                validate(rewriteDraft);
                draft = rewriteDraft;
                log.warn("[编剧] 二投放行 topicId={} scriptId={} (原始 critic score={})",
                        topic.getId(), script.getId(), critic.getScore());
            } catch (RuntimeException ex) {
                log.warn("[编剧] 二投失败 fall back 原稿 topicId={} scriptId={}: {}",
                        topic.getId(), script.getId(), ex.toString());
            }
        }

        script.setWordCount(draft.getWordCount());
        script.setDurationSeconds(draft.getDurationSeconds());
        script.setFullText(joinFullText(draft));
        script = scriptRepository.save(script);

        for (ScriptDraft.SectionDraft sd : draft.getSections()) {
            ScriptSection s = new ScriptSection();
            s.setScriptId(script.getId());
            s.setSectionCode(TextUtils.truncate(sd.getSectionCode(), 8));
            s.setTitle(TextUtils.truncate(sd.getTitle(), 80));
            s.setStartSeconds(sd.getStartSeconds());
            s.setEndSeconds(sd.getEndSeconds());
            s.setTextContent(sd.getTextContent());
            s.setDirectorNote(TextUtils.truncate(sd.getDirectorNote(), 500));
            s.setIsGoldenLine(Boolean.TRUE.equals(sd.getIsGoldenLine()));
            sectionRepository.save(s);
        }

        // 编剧 @ 群,告诉下游摄影"我实际定的调性 / 数字密度 / 长度 / 钩子"。
        // 流水线起点 → 先清旧 addendum,确保只反映最新一轮编剧的决策。
        try {
            directorNoteService.clear(topic.getId());
            directorNoteService.append("SCRIPT", topic.getId(), buildScriptAddendum(draft, topic));
        } catch (RuntimeException ex) {
            log.warn("[编剧] addendum 写入失败但不阻塞 topicId={}: {}", topic.getId(), ex.toString());
        }
        return script;
    }

    /** 拼编剧 @ 群的内容:实际定的 archetype + 数字密度 + 字数 + 第一句开头。 */
    private String buildScriptAddendum(ScriptDraft draft, Topic topic) {
        String archetype = "—";
        try {
            String pij = topic.getPresetInputJson();
            if (pij != null && !pij.isBlank()) {
                JsonNode root = objectMapper.readTree(pij);
                JsonNode arch = root.path("archetype");
                if (arch.isMissingNode() || arch.isNull()) arch = root.path("identity_card").path("archetype");
                if (!arch.isMissingNode() && !arch.isNull()) {
                    String s = arch.asText();
                    if (s != null && !s.isBlank()) archetype = s;
                }
            }
        } catch (Exception ignore) { /* 解析失败用 — 兜底 */ }

        String fullText = joinFullText(draft);
        long numHits = fullText.chars().filter(Character::isDigit).count();
        Integer wc = draft.getWordCount();
        String firstLine = "";
        if (draft.getSections() != null && !draft.getSections().isEmpty()) {
            String first = draft.getSections().get(0).getTextContent();
            if (first != null && !first.isBlank()) {
                firstLine = first.length() > 30 ? first.substring(0, 30) + "..." : first;
            }
        }
        return String.format(
                "实际调性=%s;总字数=%s;数字密度=%d 处(全文);第 1 句开头=\"%s\"。"
              + "下游(摄影)请按此密度和调性安排画面,镜头切换要跟得上文本节奏。",
                archetype, wc == null ? "—" : wc.toString(), numHits, firstLine);
    }

    /**
     * 模型选择：优先按预设里的 routing.by=potential_score 路由，旗舰/批量模型与阈值都从 yaml 读。
     * 任何字段缺失都回退到 yaml 顶层 model；再缺就交给 LlmClient 用全局默认。
     */
    private static String pickModel(Topic topic, PromptTemplateService.Rendered tpl) {
        com.auteur.llm.PromptTemplateLoader.Routing r = tpl.routing();
        if (r != null && "potential_score".equals(r.getBy())
                && r.getThreshold() != null
                && r.getPremiumModel() != null && !r.getPremiumModel().isBlank()
                && r.getBatchModel()   != null && !r.getBatchModel().isBlank()) {
            BigDecimal score = topic.getPotentialScore();
            return score != null && score.compareTo(r.getThreshold()) >= 0
                    ? r.getPremiumModel()
                    : r.getBatchModel();
        }
        return tpl.model();
    }

    /**
     * 兼容渲染:若 preset_input_json 里(顶层或 identity_card 下)有 nodes 数组,
     * 渲染成 yaml 期望的 {{identity_card_nodes}} 多行字符串。
     */
    private void injectIdentityCardNodesIfPresent(Map<String, Object> params, String presetInputJson, Long topicId) {
        if (presetInputJson == null || presetInputJson.isBlank()) return;
        try {
            JsonNode root = objectMapper.readTree(presetInputJson);
            JsonNode nodes = root.get("nodes");
            if (nodes == null || !nodes.isArray()) {
                JsonNode card = root.get("identity_card");
                if (card != null && card.isObject()) {
                    nodes = card.get("nodes");
                }
            }
            if (nodes == null || !nodes.isArray() || nodes.size() == 0) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nodes.size(); i++) {
                JsonNode n = nodes.get(i);
                String age = n.has("age") ? n.get("age").asText() : null;
                String phase = n.has("phase") ? n.get("phase").asText() : "?";
                String scene = n.has("scene") ? n.get("scene").asText() : "";
                JsonNode details = n.get("details");
                StringBuilder detailsSb = new StringBuilder();
                if (details != null && details.isArray()) {
                    for (int j = 0; j < details.size(); j++) {
                        if (j > 0) detailsSb.append(" / ");
                        detailsSb.append(details.get(j).asText());
                    }
                }
                sb.append("  - ");
                if (age != null && !age.isBlank() && !"null".equals(age)) {
                    sb.append("[").append(age).append("岁] ");
                }
                sb.append(phase).append(" @ ").append(scene)
                  .append(" | details: ").append(detailsSb);
                if (i < nodes.size() - 1) sb.append('\n');
            }
            params.put("identity_card_nodes", sb.toString());
        } catch (Exception e) {
            log.warn("[编剧] preset_input_json nodes 渲染失败 topicId={}: {}", topicId, e.toString());
        }
    }

    private ScriptDraft parseDraft(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM returned empty content");
        }
        String stripped = TextUtils.stripCodeFence(raw).trim();
        String json = JsonExtractUtils.extractJsonObject(stripped);
        if (json == null) {
            log.error("[编剧] response is not a JSON object. FULL raw=\n{}", raw);
            throw new IllegalStateException("LLM response is not a JSON object: " + TextUtils.preview(raw));
        }
        // 兜底修复 LLM 在字符串值里夹的未转义 ASCII 双引号
        String fixed = JsonHealer.fixUnescapedAsciiQuotes(json);
        try {
            return objectMapper.readValue(fixed, ScriptDraft.class);
        } catch (Exception e) {
            log.error("[编剧] parse failed: {}. FULL raw=\n{}", e.toString(), raw);
            throw new IllegalStateException("Failed to parse LLM JSON: " + TextUtils.preview(raw), e);
        }
    }

    private static void validate(ScriptDraft draft) {
        if (draft == null || draft.getSections() == null || draft.getSections().isEmpty()) {
            throw new IllegalStateException("Script has no sections");
        }
        if (draft.getSections().size() < 5) {
            throw new IllegalStateException("Script must have 5 sections (A/B/C/D/E), got " + draft.getSections().size());
        }
    }

    private static String joinFullText(ScriptDraft d) {
        StringBuilder sb = new StringBuilder();
        for (ScriptDraft.SectionDraft s : d.getSections()) {
            if (s.getTextContent() == null) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("[").append(s.getSectionCode()).append("] ").append(s.getTextContent());
        }
        return sb.toString();
    }

    /**
     * 人工编辑 section 后的入口:更新 textContent / title,并基于该 script 的所有 section 重建 fullText.
     * 不刷 wordCount / durationSeconds —— 那两个由生成时 LLM 给的目标值,不是统计值.
     */
    @Transactional
    public ScriptSection updateSection(Long scriptId, Long sectionId, String textContent, String title) {
        ScriptSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new NotFoundException("Section not found: " + sectionId));
        if (!scriptId.equals(section.getScriptId())) {
            throw new IllegalArgumentException(
                    "Section " + sectionId + " does not belong to script " + scriptId);
        }
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));

        section.setTextContent(textContent);
        section.setTitle(title);
        sectionRepository.save(section);

        List<ScriptSection> all = sectionRepository.findByScriptIdOrderBySectionCodeAsc(scriptId);
        script.setFullText(joinSectionFullText(all));
        scriptRepository.save(script);
        log.info("[编剧] updated section id={} script={} chars={}",
                sectionId, scriptId, textContent == null ? 0 : textContent.length());
        return section;
    }

    /** 同 {@link #joinFullText} 的拼接格式,但输入是已持久化的 ScriptSection 列表. */
    private static String joinSectionFullText(List<ScriptSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (ScriptSection s : sections) {
            if (s.getTextContent() == null) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("[").append(s.getSectionCode()).append("] ").append(s.getTextContent());
        }
        return sb.toString();
    }

    /**
     * 重新生成时用户给的"自由指令"块。空指令 → 空串。
     * 非空时包成显眼标注的"最高优先级"段,放在选题信息之后,让模型最近读到的是约束。
     */
    private static String buildAnchorBlock(String anchor) {
        if (anchor == null) return "";
        String trimmed = anchor.trim();
        if (trimmed.isEmpty()) return "";
        return "\n【本次重新生成的额外要求 · 最高优先级】\n"
                + "下面这段是用户对这次重新生成的硬性要求,必须严格遵守。"
                + "如与上方【选题】里的标题/钩子类型/主导情绪/史料参考等字段在主题或叙事重点上冲突,**以本要求为准,改写选题字段背后的故事**,不要把它降级成结尾的「下集预告」或一句过场:\n"
                + trimmed + "\n";
    }
}
