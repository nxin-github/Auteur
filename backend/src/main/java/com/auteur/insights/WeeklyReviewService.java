package com.auteur.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.WeeklyReview;
import com.auteur.domain.WeeklyReviewRepository;
import com.auteur.insights.InsightDtos.TopBottomReport;
import com.auteur.insights.InsightDtos.VideoFeature;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.ModelRegistry;
import com.auteur.llm.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 周复盘 4 段(本周亮点 / 教训 / 试错 / 下周改进)的 LLM 自动生成。
 * 失败兜底:LLM 调用 / JSON 解析失败时不抛 500,4 段写"AI 解析失败,请手动复盘"返回前端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReviewService {

    private static final int RECENT_VIDEOS_HARD_LIMIT_DEFAULT = 30;
    private static final int MIN_SAMPLE_FOR_REVIEW_DEFAULT = 3;
    private static final Pattern WEEK_CODE_RE = Pattern.compile("^(\\d{4})-W(\\d{2})$");

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ModelRegistry modelRegistry;
    private final InsightService insightService;
    private final JdbcTemplate jdbc;
    private final WeeklyReviewRepository weeklyReviewRepository;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private int recentVideosHardLimit() { return runtimeConfig.getInt("auteur.insights.weekly.recent-videos-limit", RECENT_VIDEOS_HARD_LIMIT_DEFAULT); }
    private int minSampleForReview() { return runtimeConfig.getInt("auteur.insights.weekly.min-sample-for-review", MIN_SAMPLE_FOR_REVIEW_DEFAULT); }

    /**
     * 故意不加 @Transactional(readOnly = true):LlmClient 内部要往 cost_log 写审计行,
     * 一旦外层 tx 是 readOnly,JDBC 拒绝 INSERT,Spring 把 tx 标 rollback-only,
     * 方法返回时 commit 抛 UnexpectedRollbackException。
     */
    public WeeklyReviewResult generate(String platform, int days) {
        int window = Math.max(1, days);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(window);

        List<WeekVideo> weekVideos = fetchWeekVideos(platform, since, now);
        Stats stats = computeStats(weekVideos);
        TopBottomReport tb = insightService.topBottom(platform, window, 5);

        // 上周窗口:[since-window, since)
        List<WeekVideo> prevWeekVideos = fetchWeekVideos(platform, since.minusDays(window), since);
        Stats prevStats = computeStats(prevWeekVideos);
        String currentWeekCode = weekKey();
        String prevWeekCode = previousWeekCode(currentWeekCode);
        String prevWeekPlan = weeklyReviewRepository.findByWeekCode(prevWeekCode)
                .map(WeeklyReview::getNextWeek)
                .filter(s -> s != null && !s.isBlank())
                .orElse("(上周无保存的下周改进计划)");

        // 样本 < 阈值 直接返回兜底,不烧 token
        if (weekVideos.size() < minSampleForReview()) {
            String msg = String.format("本周样本不足(仅 %d 条),建议下周加大产能再复盘。", weekVideos.size());
            return new WeeklyReviewResult(msg, msg, msg, msg, stats, true);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("week_key", currentWeekCode);
        vars.put("video_count", weekVideos.size());
        vars.put("total_views", String.format("%,d", stats.totalViews));
        vars.put("avg_retention", stats.avgRetention == null
                ? "—"
                : stats.avgRetention.toPlainString() + "%");
        vars.put("week_videos", formatWeekVideos(weekVideos));
        vars.put("top_features", formatVideoFeatures(tb.top(), tb.topCommonality(), "本周内无 Top 视频"));
        vars.put("bottom_features", formatVideoFeatures(tb.bottom(), tb.bottomCommonality(), "本周内无 Bottom 视频"));
        vars.put("prev_week_code", prevWeekCode);
        vars.put("prev_video_count", prevWeekVideos.size());
        vars.put("prev_total_views", String.format("%,d", prevStats.totalViews));
        vars.put("prev_avg_retention", prevStats.avgRetention == null
                ? "—"
                : prevStats.avgRetention.toPlainString() + "%");
        vars.put("prev_week_plan", prevWeekPlan);

        PromptTemplateService.Rendered tpl = promptService.render("weekly_review", vars);
        Double temperature = tpl.temperature() != null ? tpl.temperature() : 0.6;

        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("weekly_review")
                .relatedType("REVIEW")
                .model(modelRegistry.modelFor("weekly_review"))
                .temperature(temperature)
                .maxTokens(tpl.maxTokens())
                .build();

        LlmResult result;
        try {
            result = llmClient.chat(spec, tpl.system(), tpl.user());
        } catch (RuntimeException e) {
            log.warn("[WeeklyReview] LLM call failed: {}", e.toString());
            return fallback(stats, "AI 调用失败:" + e.getMessage());
        }
        String raw = result.getContent();
        log.info("[WeeklyReview] LLM raw chars={} inTok={} outTok={} ms={}",
                raw == null ? 0 : raw.length(),
                result.getInputTokens(), result.getOutputTokens(), result.getDurationMs());

        return parseResult(raw, stats);
    }

    private List<WeekVideo> fetchWeekVideos(String platform, LocalDateTime since, LocalDateTime until) {
        StringBuilder sb = new StringBuilder()
                .append("SELECT pv.id, pv.title, pv.platform, pv.published_at, ")
                .append("       pv.retention_pct, pv.views, pv.hook_template, ")
                .append("       t.dynasty, t.genre, t.hook_type, t.emotion, t.duration_minutes ")
                .append("FROM published_video pv ")
                .append("LEFT JOIN topic t ON t.id = pv.topic_id ")
                .append("WHERE pv.published_at >= ? AND pv.published_at < ? ");
        if (platform != null && !platform.isBlank()) sb.append("AND pv.platform = ? ");
        sb.append("ORDER BY pv.published_at DESC LIMIT ").append(recentVideosHardLimit());

        Object[] args = (platform == null || platform.isBlank())
                ? new Object[]{since, until}
                : new Object[]{since, until, platform.trim()};
        List<WeekVideo> out = new ArrayList<>();
        jdbc.query(sb.toString(), args, rs -> {
            BigDecimal r = rs.getBigDecimal("retention_pct");
            int dm = rs.getInt("duration_minutes");
            Integer durationMinutes = rs.wasNull() ? null : dm;
            out.add(new WeekVideo(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("platform"),
                    r == null ? null : r.setScale(2, RoundingMode.HALF_UP),
                    rs.getLong("views"),
                    rs.getString("hook_template"),
                    rs.getString("dynasty"),
                    rs.getString("genre"),
                    rs.getString("hook_type"),
                    rs.getString("emotion"),
                    durationMinutes
            ));
        });
        return out;
    }

    private Stats computeStats(List<WeekVideo> videos) {
        long totalViews = 0;
        BigDecimal sumR = BigDecimal.ZERO;
        int retCount = 0;
        for (WeekVideo v : videos) {
            totalViews += v.views;
            if (v.retentionPct != null) {
                sumR = sumR.add(v.retentionPct);
                retCount++;
            }
        }
        BigDecimal avg = retCount > 0
                ? sumR.divide(BigDecimal.valueOf(retCount), 2, RoundingMode.HALF_UP)
                : null;
        return new Stats(videos.size(), totalViews, avg);
    }

    private static String weekKey() {
        LocalDateTime now = LocalDateTime.now();
        int year = now.getYear();
        int dayOfYear = now.getDayOfYear();
        int week = (int) Math.ceil((dayOfYear + java.time.LocalDate.of(year, 1, 1).getDayOfWeek().getValue()) / 7.0);
        return String.format("%d-W%02d", year, week);
    }

    /** week>1 直接 -1;week=1 跨年回退到上一年的 W52(非严格 ISO,跟 weekKey() 算法一致)。 */
    private static String previousWeekCode(String weekCode) {
        var m = WEEK_CODE_RE.matcher(weekCode);
        if (!m.matches()) return weekCode;
        int year = Integer.parseInt(m.group(1));
        int week = Integer.parseInt(m.group(2));
        if (week > 1) return String.format("%d-W%02d", year, week - 1);
        return String.format("%d-W%02d", year - 1, 52);
    }

    private static String formatWeekVideos(List<WeekVideo> videos) {
        if (videos.isEmpty()) return "(本周无发布视频)";
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter df = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = 0; i < videos.size(); i++) {
            WeekVideo v = videos.get(i);
            sb.append(i + 1).append(". 《").append(v.title).append("》");
            sb.append(" 完播 ").append(v.retentionPct == null ? "—" : v.retentionPct.toPlainString() + "%");
            sb.append(", 播放 ").append(String.format("%,d", v.views));
            if (v.hookTemplate != null && !v.hookTemplate.isBlank()) {
                sb.append(", 钩子模板=").append(v.hookTemplate);
            }
            sb.append(" [");
            sb.append(orQuestion(v.dynasty)).append("/").append(orQuestion(v.genre)).append("/").append(orQuestion(v.hookType));
            if (v.durationMinutes != null) sb.append("/").append(v.durationMinutes).append("min");
            sb.append("]\n");
        }
        return sb.toString().trim();
    }

    private static String formatVideoFeatures(
            List<VideoFeature> rows, Map<String, String> commonality, String emptyHint) {
        if (rows.isEmpty()) return emptyHint;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            VideoFeature v = rows.get(i);
            sb.append(i + 1).append(". 《").append(v.title()).append("》")
              .append(" 完播 ").append(v.retentionPct() == null ? "—" : v.retentionPct().toPlainString() + "%")
              .append(" [")
              .append(v.dimensions().getOrDefault("dynasty", "?"))
              .append("/").append(v.dimensions().getOrDefault("genre", "?"))
              .append("/").append(v.dimensions().getOrDefault("hookType", "?"))
              .append("]\n");
        }
        if (!commonality.isEmpty()) {
            sb.append("共性:");
            commonality.forEach((k, val) -> sb.append(k).append("=").append(val).append("  "));
        }
        return sb.toString().trim();
    }

    private WeeklyReviewResult parseResult(String raw, Stats stats) {
        if (raw == null || raw.isBlank()) {
            log.warn("[WeeklyReview] LLM returned empty content");
            return fallback(stats, "AI 返回为空");
        }
        String json = TextUtils.stripCodeFence(raw).trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            log.warn("[WeeklyReview] LLM response is not a JSON object: {}", TextUtils.preview(raw));
            return fallback(stats, "AI 返回非 JSON");
        }
        json = json.substring(start, end + 1);
        try {
            JsonNode node = objectMapper.readTree(json);
            String h = readField(node, "highlights");
            String l = readField(node, "lessons");
            String e = readField(node, "experiments");
            String n = readField(node, "nextWeek");
            if (h == null || l == null || e == null || n == null) {
                log.warn("[WeeklyReview] missing fields in LLM JSON: {}", TextUtils.preview(raw));
                return fallback(stats, "AI 返回字段不全");
            }
            return new WeeklyReviewResult(h, l, e, n, stats, false);
        } catch (Exception ex) {
            log.warn("[WeeklyReview] JSON parse failed: {}", TextUtils.preview(raw), ex);
            return fallback(stats, "AI JSON 解析失败");
        }
    }

    private static String readField(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) return null;
        String text = v.isTextual() ? v.asText() : v.toString();
        return text == null || text.isBlank() ? null : text;
    }

    private static WeeklyReviewResult fallback(Stats stats, String reason) {
        String msg = "AI 解析失败(" + reason + "),原始输出已记录到日志,请手动复盘。";
        return new WeeklyReviewResult(msg, msg, msg, msg, stats, true);
    }

    /** 拿某一周的复盘文本;不存在返回空对象。 */
    @Transactional(readOnly = true)
    public WeeklyReviewView getByWeek(String weekCode) {
        validateWeekCode(weekCode);
        return weeklyReviewRepository.findByWeekCode(weekCode)
                .map(WeeklyReviewView::from)
                .orElseGet(() -> WeeklyReviewView.empty(weekCode));
    }

    /** upsert:存在按 weekCode 更新 4 段,不存在新建一行;period_start/end 由 weekCode 推算。 */
    @Transactional
    public WeeklyReviewView upsertByWeek(String weekCode, WeeklyReviewSavePayload payload) {
        validateWeekCode(weekCode);
        WeeklyReview entity = weeklyReviewRepository.findByWeekCode(weekCode)
                .orElseGet(() -> {
                    WeeklyReview n = new WeeklyReview();
                    n.setWeekCode(weekCode);
                    LocalDate[] period = derivePeriod(weekCode);
                    n.setPeriodStart(period[0]);
                    n.setPeriodEnd(period[1]);
                    return n;
                });
        entity.setHighlights(payload.highlights());
        entity.setLessons(payload.lessons());
        entity.setExperiments(payload.experiments());
        entity.setNextWeek(payload.nextWeek());
        WeeklyReview saved = weeklyReviewRepository.save(entity);
        log.info("[WeeklyReview] upserted week={} id={}", weekCode, saved.getId());
        return WeeklyReviewView.from(saved);
    }

    private static void validateWeekCode(String weekCode) {
        if (weekCode == null || !WEEK_CODE_RE.matcher(weekCode).matches()) {
            throw new IllegalArgumentException("weekCode 必须形如 YYYY-Www,实际:" + weekCode);
        }
    }

    /**
     * 把 "YYYY-Www" 还原成近似的 (period_start, period_end)。
     * 跟前端 thisWeekKey() 算法对齐:基于 1 月 1 日起算的"自然第几周",不是严格 ISO-week。
     */
    private static LocalDate[] derivePeriod(String weekCode) {
        var m = WEEK_CODE_RE.matcher(weekCode);
        if (!m.matches()) throw new IllegalArgumentException("weekCode invalid: " + weekCode);
        int year = Integer.parseInt(m.group(1));
        int week = Integer.parseInt(m.group(2));
        LocalDate jan1 = LocalDate.of(year, 1, 1);
        LocalDate start = jan1.plusDays((long) (week - 1) * 7);
        return new LocalDate[]{start, start.plusDays(6)};
    }

    public record WeeklyReviewResult(
            String highlights,
            String lessons,
            String experiments,
            String nextWeek,
            Stats stats,
            /** true = 兜底文案(样本不足 / LLM 失败 / 解析失败),前端不应自动落库。 */
            boolean fallback
    ) {}

    public record Stats(
            int videoCount,
            long totalViews,
            BigDecimal avgRetention
    ) {}

    /** GET /api/reviews/weekly 返回体。null 字段 = 该段未填。updatedAt null = 数据库无记录。 */
    public record WeeklyReviewView(
            String weekCode,
            String highlights,
            String lessons,
            String experiments,
            String nextWeek,
            LocalDateTime updatedAt
    ) {
        static WeeklyReviewView from(WeeklyReview r) {
            return new WeeklyReviewView(
                    r.getWeekCode(),
                    r.getHighlights(), r.getLessons(),
                    r.getExperiments(), r.getNextWeek(),
                    r.getUpdatedAt()
            );
        }

        static WeeklyReviewView empty(String weekCode) {
            return new WeeklyReviewView(weekCode, null, null, null, null, null);
        }
    }

    /** PUT /api/reviews/weekly 请求体。空字符串和 null 都视作清空该段。 */
    public record WeeklyReviewSavePayload(
            String highlights,
            String lessons,
            String experiments,
            String nextWeek
    ) {}

    private record WeekVideo(
            long id,
            String title,
            String platform,
            BigDecimal retentionPct,
            long views,
            String hookTemplate,
            String dynasty,
            String genre,
            String hookType,
            String emotion,
            Integer durationMinutes
    ) {}

    private static String orQuestion(String s) {
        return (s == null || s.isBlank()) ? "?" : s;
    }
}
