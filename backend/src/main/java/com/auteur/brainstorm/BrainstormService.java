package com.auteur.brainstorm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.PipelineStage;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.insights.InsightDtos.BrainstormDataPack;
import com.auteur.insights.InsightService;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.ModelRegistry;
import com.auteur.llm.PromptTemplateService;
import com.auteur.pipeline.PipelineRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrainstormService {

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ModelRegistry modelRegistry;
    private final TopicRepository topicRepository;
    private final SeriesResolver seriesResolver;
    private final PipelineRunService runService;
    private final InsightService insightService;
    private final com.auteur.preset.PresetService presetService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public List<Topic> brainstorm(BrainstormRequest req) {
        com.auteur.domain.PipelineRun run = runService.start(
                PipelineStage.BRAINSTORM, null, null,
                Map.of("n", req.getN(),
                        "archive_hint", TextUtils.safe(req.getArchiveHint()),
                        "done_topics", TextUtils.safe(req.getDoneTopics()),
                        "preset_id", req.getPresetId() == null ? "" : req.getPresetId().toString()),
                "API");
        try {
            List<Topic> result = doBrainstorm(req);
            runService.markDone(run.getId(), result.size());
            return result;
        } catch (RuntimeException e) {
            runService.markFailed(run.getId(), e.toString());
            throw e;
        }
    }

    private List<Topic> doBrainstorm(BrainstormRequest req) {
        if (req.getPresetId() == null) {
            throw new IllegalStateException("brainstorm 必须传 presetId");
        }
        com.auteur.preset.Preset preset = presetService.get(req.getPresetId());
        if (preset.getBrainstormPromptYaml() == null || preset.getBrainstormPromptYaml().isBlank()) {
            throw new IllegalStateException(
                    "preset id=" + preset.getId() + " (" + preset.getName() + ") 未配置 brainstorm_prompt,不支持头脑风暴");
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("n", req.getN());
        vars.put("archive_hint", TextUtils.safe(req.getArchiveHint()));
        vars.put("done_topics", TextUtils.safe(req.getDoneTopics()));

        boolean dataDriven = Boolean.TRUE.equals(req.getUseDataDriven());
        if (dataDriven) {
            int days = req.getWindowDays() == null ? 30 : Math.max(1, req.getWindowDays());
            try {
                BrainstormDataPack pack = insightService.buildBrainstormPack(req.getPlatform(), days, preset.getName());
                vars.put("weight_table", pack.weightTable());
                vars.put("top_features", pack.topFeatures());
                vars.put("bottom_features", pack.bottomFeatures());
                vars.put("prev_week_plan", pack.prevWeekPlan());
                log.info("[Brainstorm] data-driven on platform={} preset={} days={} weightChars={} topChars={} bottomChars={} prevPlanChars={}",
                        req.getPlatform(), preset.getName(), days,
                        pack.weightTable().length(), pack.topFeatures().length(), pack.bottomFeatures().length(),
                        pack.prevWeekPlan() == null ? 0 : pack.prevWeekPlan().length());
            } catch (RuntimeException e) {
                log.warn("[Brainstorm] data-driven prep failed, fall back to default: {}", e.toString());
                fillDataDrivenDefaults(vars);
            }
        } else {
            fillDataDrivenDefaults(vars);
        }

        PromptTemplateService.Rendered tpl = promptService.renderInline(preset.getBrainstormPromptYaml(), vars);
        String operation = "brainstorm_" + preset.getName();
        // 模型优先级:req.model(用户单次覆盖) > preset 内 yaml.model > 全局默认(app_config)
        String model = req.getModel() != null && !req.getModel().isBlank()
                ? req.getModel()
                : modelRegistry.modelOrDefault(tpl.model(), "brainstorm");
        Double temperature = tpl.temperature() != null ? tpl.temperature() : 0.85;

        LlmCallSpec spec = LlmCallSpec.builder()
                .operation(operation)
                .relatedType("TOPIC")
                .model(model)
                .temperature(temperature)
                .build();
        LlmResult result = llmClient.chat(spec, tpl.system(), tpl.user());
        String raw = result.getContent();
        log.info("[Brainstorm] LLM raw chars={} inTok={} outTok={} ms={}",
                raw == null ? 0 : raw.length(),
                result.getInputTokens(), result.getOutputTokens(), result.getDurationMs());

        List<BrainstormCandidate> candidates = parseCandidates(raw);
        log.info("[Brainstorm] parsed candidates={}", candidates.size());

        List<Topic> persisted = new ArrayList<>(candidates.size());
        for (BrainstormCandidate c : candidates) {
            Topic t = new Topic();
            String trimmedTitle = TextUtils.truncate(c.getTitle(), 200);
            t.setTitle(trimmedTitle);
            if (trimmedTitle != null && !trimmedTitle.isBlank()) {
                t.setProjectName(trimmedTitle.substring(0, Math.min(trimmedTitle.length(), 10)));
            }
            t.setDynasty(TextUtils.truncate(c.getDynasty(), 40));
            t.setGenre(TextUtils.truncate(c.getGenre(), 40));
            t.setProtagonist(TextUtils.truncate(c.getProtagonist(), 120));
            t.setHookType(TextUtils.truncate(c.getHookType(), 40));
            t.setEmotion(TextUtils.truncate(c.getEmotion(), 40));
            t.setDurationMinutes(c.getDurationMinutes());
            t.setPotentialScore(c.getPotentialScore());
            t.setHistoricalReference(c.getHistoricalReference());
            t.setAiSuggestedSeries(TextUtils.truncate(c.getSuggestedSeries(), 120));
            t.setSeriesId(seriesResolver.resolveSeriesId(
                    c.getSuggestedSeries(), c.getDynasty(), c.getGenre()));
            t.setStatus(TopicStatus.DRAFT);
            t.setSource("AI_BRAINSTORM");
            t.setPresetId(preset.getId());
            t.setPresetVersionUsed(preset.getCurrentVersion());
            if (c.getPresetInput() != null && !c.getPresetInput().isEmpty()) {
                // 通用路径:LLM 直接按预设的 input_schema 字段名输出 preset_input 对象,整个写入。
                // 适用任何 input_schema 用具体业务字段(非 lifecopy 4 字段)的预设。
                try {
                    t.setPresetInputJson(objectMapper.writeValueAsString(c.getPresetInput()));
                } catch (Exception e) {
                    log.warn("[Brainstorm] preset_input_json (generic) 序列化失败 title={}: {}",
                            c.getTitle(), e.toString());
                }
                // protagonist 兜底:identityTag 顶层字段如有就用,否则留空(各预设可在选题脑暴时
                // 顺手填顶层 protagonist 字段)
                if ((t.getProtagonist() == null || t.getProtagonist().isBlank())
                        && c.getIdentityTag() != null) {
                    t.setProtagonist(TextUtils.truncate(c.getIdentityTag(), 120));
                }
            } else if (c.getIdentityTag() != null) {
                // 老路径(lifecopy):identity_tag / era / archetype / nodes 4 个硬编码字段
                Map<String, Object> input = new HashMap<>();
                input.put("identity_tag", c.getIdentityTag());
                input.put("era", c.getEra());
                input.put("archetype", c.getArchetype());
                input.put("nodes", c.getNodes() == null ? List.of() : c.getNodes());
                try {
                    t.setPresetInputJson(objectMapper.writeValueAsString(input));
                } catch (Exception e) {
                    log.warn("[Brainstorm] preset_input_json 序列化失败 title={}: {}",
                            c.getTitle(), e.toString());
                }
                if (t.getProtagonist() == null || t.getProtagonist().isBlank()) {
                    t.setProtagonist(TextUtils.truncate(c.getIdentityTag(), 120));
                }
            }
            persisted.add(topicRepository.save(t));
        }
        return persisted;
    }

    private List<BrainstormCandidate> parseCandidates(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM returned empty content");
        }
        String json = TextUtils.stripCodeFence(raw).trim();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("LLM response is not a JSON array: " + TextUtils.preview(raw));
        }
        json = json.substring(start, end + 1);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BrainstormCandidate>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse LLM JSON: " + TextUtils.preview(raw), e);
        }
    }

    /** 非数据驱动 / 数据驱动失败时给 yaml 占位符兜底，避免 {{...}} 透传到 LLM。 */
    private static void fillDataDrivenDefaults(Map<String, Object> vars) {
        vars.put("weight_table",
                "- 朝代权重：明 0.45 / 清 0.42 / 民国 0.40 / 唐 0.35 / 宋 0.30\n" +
                "- 题材权重：宫廷 0.48 / 案件 0.45 / 灵异 0.42 / 政治 0.32\n" +
                "- 钩子权重：反逻辑 0.50 / 数字冲击 0.46 / 时间地点反常 0.42 / 未解之谜 0.40 / 反差身份 0.38");
        vars.put("top_features", "（暂无历史数据，沿用静态权重经验值）");
        vars.put("bottom_features", "（暂无历史数据）");
        vars.put("prev_week_plan", "（暂无上周复盘的下周改进计划）");
    }

}
