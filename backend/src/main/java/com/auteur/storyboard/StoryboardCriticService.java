package com.auteur.storyboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.domain.CriticLog;
import com.auteur.domain.CriticLogRepository;
import com.auteur.domain.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 摄影自审编辑 / Storyboard Critic
 *
 * 摄影出稿后的自审,只用硬规则:
 *   1. shot_type 多样性  — 30 镜不能全是中近景
 *   2. 极特写镜头数      — 至少 2(强调情绪定格)
 *   3. 中近景占比        — 不超过 60%
 *   4. prompt_zh 重复检测
 *
 * 范围:仅 PRECISE_BY_CUE 模式启用,其它直接 PASS。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoryboardCriticService {

    private final CriticLogRepository criticLogRepository;
    private final com.auteur.preset.TopicPresetResolver presetResolver;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** shot_type 至少出现的不同种类数。默认 4,通过 RuntimeConfig 读 DB 覆盖。 */
    private static final int MIN_SHOT_TYPE_VARIETY_DEFAULT = 4;

    /** 中近景占比上限。默认 0.60。 */
    private static final double MAX_MID_RATIO_DEFAULT = 0.60;

    /** 完全重复 prompt_zh 的最大允许组数(任意 group >= 2 即触发)。默认 0。 */
    private static final int MAX_DUPLICATE_GROUPS_DEFAULT = 0;

    private int minShotTypeVariety() { return runtimeConfig.getInt("auteur.storyboard.critic.min-shot-type-variety", MIN_SHOT_TYPE_VARIETY_DEFAULT); }
    private double maxMidRatio() { return runtimeConfig.getDouble("auteur.storyboard.critic.max-mid-ratio", MAX_MID_RATIO_DEFAULT); }
    private int maxDuplicateGroups() { return runtimeConfig.getInt("auteur.storyboard.critic.max-duplicate-groups", MAX_DUPLICATE_GROUPS_DEFAULT); }

    /** 极特写关键词。LLM 写 shot_type 时表述不统一,做包含匹配。 */
    private static final Set<String> EXTREME_CLOSEUP_KEYWORDS = Set.of("极特写", "特写");

    private static final Set<String> MID_KEYWORDS = Set.of("中近景", "中景", "近景");

    /**
     * 自审入口。返回的 decision = PASS / REWRITE。
     * scriptId 仅用于 critic_log 落库追溯,可空。
     */
    public StoryboardCriticResult audit(List<StoryboardShotDraft> drafts, Topic topic, Long scriptId) {
        StoryboardCriticResult result = doAudit(drafts, topic);
        writeLog(result, topic, scriptId);
        return result;
    }

    /** 老调用保持兼容 — 不写 critic_log。 */
    public StoryboardCriticResult audit(List<StoryboardShotDraft> drafts, Topic topic) {
        return doAudit(drafts, topic);
    }

    private StoryboardCriticResult doAudit(List<StoryboardShotDraft> drafts, Topic topic) {
        if (drafts == null || drafts.isEmpty()) {
            return passWithScore(100);
        }
        // PRECISE_BY_CUE 启用画面 critic;FREE 模式直接 PASS
        com.auteur.preset.PresetContext ctx = presetResolver.forTopic(topic);
        if (!"PRECISE_BY_CUE".equals(ctx.preset().getStoryboardMode())) {
            return passWithScore(100);
        }

        List<String> issues = new ArrayList<>();
        StringBuilder feedback = new StringBuilder();

        int total = drafts.size();
        Map<String, Integer> typeCount = new HashMap<>();
        Map<String, Integer> promptCount = new HashMap<>();
        int extremeCloseup = 0;
        int midShots = 0;

        for (StoryboardShotDraft d : drafts) {
            String type = d.getShotType() == null ? "" : d.getShotType().trim();
            if (!type.isEmpty()) {
                typeCount.merge(type, 1, Integer::sum);
            }
            if (containsAny(type, EXTREME_CLOSEUP_KEYWORDS)) extremeCloseup++;
            if (containsAny(type, MID_KEYWORDS)) midShots++;

            String prompt = d.getPromptZh() == null ? "" : d.getPromptZh().trim();
            if (!prompt.isEmpty()) {
                promptCount.merge(prompt, 1, Integer::sum);
            }
        }

        // 1. shot_type 多样性
        int minVariety = minShotTypeVariety();
        if (typeCount.size() < minVariety) {
            issues.add("shot_type 仅 " + typeCount.size() + " 种(下限 " + minVariety + ")");
            feedback.append("· shot_type 多样性不足,目前只有 ").append(typeCount.keySet())
                    .append(",至少需要 ").append(minVariety)
                    .append(" 种(可选:极特写/特写/中近景/中景/全景/远景/空镜/POV);\n");
        }

        // 2. 极特写镜数(从 preset.minExtremeCloseup 读;0 = 不检查)
        int minExtremeCloseup = ctx.preset().getMinExtremeCloseup();
        if (minExtremeCloseup > 0 && extremeCloseup < minExtremeCloseup) {
            issues.add("极特写仅 " + extremeCloseup + " 镜(下限 " + minExtremeCloseup + ")");
            feedback.append("· 极特写镜头不足:需要至少 ").append(minExtremeCloseup)
                    .append(" 个极特写做情绪定格(目前只有 ").append(extremeCloseup).append(" 个);\n");
        }

        // 3. 中近景占比
        double midRatio = total == 0 ? 0 : (double) midShots / total;
        double maxMid = maxMidRatio();
        if (midRatio > maxMid) {
            int pct = (int) Math.round(midRatio * 100);
            issues.add("中近景占比 " + pct + "% > " + (int) (maxMid * 100) + "%");
            feedback.append("· 中近景过多(占比 ").append(pct).append("%),应增加全景/特写/空镜调节节奏;\n");
        }

        // 4. prompt_zh 完全重复
        long duplicateGroups = promptCount.values().stream().filter(c -> c >= 2).count();
        if (duplicateGroups > maxDuplicateGroups()) {
            List<String> samples = new ArrayList<>();
            promptCount.forEach((p, c) -> {
                if (c >= 2 && samples.size() < 3) {
                    samples.add("\"" + (p.length() > 30 ? p.substring(0, 30) + "..." : p) + "\" × " + c);
                }
            });
            issues.add("prompt_zh 重复 " + duplicateGroups + " 组");
            feedback.append("· 多镜 prompt_zh 完全重复(").append(samples)
                    .append("),请把每镜的画面细节(光线/角度/前景物)区分开;\n");
        }

        if (issues.isEmpty()) {
            return passWithScore(100);
        }

        StoryboardCriticResult r = new StoryboardCriticResult();
        r.setScore(0);
        r.setDecision("REWRITE");
        r.setIssues(issues);
        r.setFeedbackForRewrite(feedback.toString());
        log.warn("[摄影Critic] topicId={} score=0 issues={}", topic.getId(), issues);
        return r;
    }

    private static StoryboardCriticResult passWithScore(int score) {
        StoryboardCriticResult r = new StoryboardCriticResult();
        r.setScore(score);
        r.setDecision("PASS");
        return r;
    }

    private static boolean containsAny(String s, Set<String> keywords) {
        if (s == null || s.isEmpty()) return false;
        for (String k : keywords) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    /** Helper for unit tests / logs。 */
    public static Set<String> shotTypeKeys(List<StoryboardShotDraft> drafts) {
        Set<String> out = new HashSet<>();
        if (drafts == null) return out;
        for (StoryboardShotDraft d : drafts) {
            if (d.getShotType() != null && !d.getShotType().isBlank()) out.add(d.getShotType().trim());
        }
        return out;
    }

    /** 落库 critic_log。失败 swallow + warn。 */
    private void writeLog(StoryboardCriticResult result, Topic topic, Long scriptId) {
        try {
            com.auteur.preset.PresetContext ctx = presetResolver.forTopic(topic);
            CriticLog row = new CriticLog();
            row.setRole("STORYBOARD");
            row.setTopicId(topic.getId());
            row.setScriptId(scriptId);
            row.setContentType(ctx.preset().getName());
            row.setScore(result.getScore() == null ? 0 : result.getScore());
            row.setDecision(result.getDecision() == null ? "PASS" : result.getDecision());
            if (result.getIssues() != null && !result.getIssues().isEmpty()) {
                row.setIssuesJson(objectMapper.writeValueAsString(result.getIssues()));
            }
            row.setFeedback(result.getFeedbackForRewrite());
            criticLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[摄影Critic] critic_log 写入失败 topicId={}: {}", topic.getId(), e.toString());
        }
    }
}
