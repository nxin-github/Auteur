package com.auteur.script;

import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.ScriptSection;
import com.auteur.domain.ScriptSectionRepository;
import com.auteur.domain.StoryboardShot;
import com.auteur.domain.StoryboardShotRepository;
import com.auteur.domain.VoiceAsset;
import com.auteur.domain.VoiceAssetRepository;
import com.auteur.video.ShotTimingResolver;
import com.auteur.video.SrtParser;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * "对齐画面"服务 —— 用户改了脚本文案后,刷一遍 section/shot 的时间戳让画面切换跟实际朗读节奏对齐。
 *
 * 两档策略:
 *  - SRT 真实对齐(优先):script 已生成过 voice 且 subtitle_url 能解析时,用 SRT cue 文本流字符位置等比例对齐
 *  - 字数估算(回落):没 voice / SRT 缺失 / SRT 与脚本字数偏差过大时,按 CHARS_PER_SEC 估算
 *
 * shot 级再分配:每个 section 的新时长按段内 shot 旧 duration_seconds 占比切分。
 * 不重新生成 voice / 不重新渲染 video / 不动 image_asset —— 只刷数字。
 */
@Slf4j
@Service
public class ScriptAlignmentService {

    /** 中文 narration 大致 4-5 字/秒,默认值偏保守(中速朗读)。 */
    private static final double CHARS_PER_SEC = 4.5;
    private static final double MIN_SECTION_SECONDS_DEFAULT = 2.0;

    private final ScriptRepository scriptRepository;
    private final ScriptSectionRepository sectionRepository;
    private final StoryboardShotRepository shotRepository;
    private final VoiceAssetRepository voiceAssetRepository;
    private final com.auteur.common.path.VoiceFileResolver voiceFileResolver;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;

    public ScriptAlignmentService(ScriptRepository scriptRepository,
                                  ScriptSectionRepository sectionRepository,
                                  StoryboardShotRepository shotRepository,
                                  VoiceAssetRepository voiceAssetRepository,
                                  com.auteur.common.path.VoiceFileResolver voiceFileResolver,
                                  com.auteur.runtimeconfig.RuntimeConfig runtimeConfig) {
        this.scriptRepository = scriptRepository;
        this.sectionRepository = sectionRepository;
        this.shotRepository = shotRepository;
        this.voiceAssetRepository = voiceAssetRepository;
        this.voiceFileResolver = voiceFileResolver;
        this.runtimeConfig = runtimeConfig;
    }

    private double minSectionSeconds() {
        return runtimeConfig.getDoublePositive("auteur.script.alignment.min-section-seconds", MIN_SECTION_SECONDS_DEFAULT);
    }

    public record AlignmentResult(
            String mode,             // "srt" | "estimate"
            int totalSeconds,
            int sectionsUpdated,
            int shotsUpdated,
            int shotsSkipped,        // 没 sectionCode 或不在 section 表里的 shot
            String note
    ) {}

    @Transactional
    public AlignmentResult align(Long scriptId) {
        long t0 = System.currentTimeMillis();
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));
        List<ScriptSection> sections = sectionRepository.findByScriptIdOrderBySectionCodeAsc(scriptId);
        if (sections.isEmpty()) {
            throw new IllegalStateException("脚本 " + scriptId + " 没有 sections,无法对齐");
        }
        List<StoryboardShot> shots = shotRepository.findByScriptIdOrderByShotIndexAsc(scriptId);

        // 找最新 voice asset (优先 isFinal,否则最新一条)
        VoiceAsset voice = voiceAssetRepository.findFirstByScriptIdAndIsFinalTrueOrderByIdDesc(scriptId)
                .orElseGet(() -> {
                    List<VoiceAsset> all = voiceAssetRepository.findByScriptIdOrderByIdDesc(scriptId);
                    return all.isEmpty() ? null : all.get(0);
                });

        Map<String, double[]> sectionMs = null;
        String mode = "estimate";
        String note;

        // 尝试 SRT 模式
        if (voice != null && voice.getSubtitleUrl() != null && !voice.getSubtitleUrl().isBlank()) {
            try {
                Path srtPath = voiceFileResolver.resolve(voice.getSubtitleUrl());
                if (srtPath != null && Files.exists(srtPath)) {
                    List<SrtParser.Cue> cues = SrtParser.parseFile(srtPath);
                    Optional<LinkedHashMap<String, double[]>> r =
                            ShotTimingResolver.resolveSectionRangesBySrt(sections, cues);
                    if (r.isPresent()) {
                        sectionMs = r.get();
                        mode = "srt";
                        note = String.format("voice=#%d cues=%d 真实对齐", voice.getId(), cues.size());
                    } else {
                        note = "SRT 与脚本字数偏差过大,回落估算";
                    }
                } else {
                    note = "voice subtitle_url 解析不到本地文件,回落估算";
                }
            } catch (IOException e) {
                log.warn("[Align] SRT 读取失败 scriptId={}: {}", scriptId, e.toString());
                note = "SRT 读取失败,回落估算";
            }
        } else {
            note = voice == null ? "脚本未生成 voice,按字数估算" : "voice 没有 subtitle_url,按字数估算";
        }

        // 估算回落
        if (sectionMs == null) {
            sectionMs = estimateByChars(sections);
            note = note + String.format(" · %.1f 字/秒", CHARS_PER_SEC);
        }

        // 写回 sections
        int sectionsUpdated = 0;
        for (ScriptSection s : sections) {
            double[] r = sectionMs.get(s.getSectionCode());
            if (r == null) continue;
            s.setStartSeconds((int) Math.round(r[0] / 1000.0));
            s.setEndSeconds((int) Math.round(r[1] / 1000.0));
            sectionRepository.save(s);
            sectionsUpdated++;
        }

        // 写回 shots(段内按旧 duration 比例分配新时长)
        int[] shotCounts = redistributeShots(shots, sectionMs);

        // 总时长 = sectionMs 里 max endMs / 1000
        int totalSec = 0;
        for (double[] r : sectionMs.values()) {
            int endSec = (int) Math.round(r[1] / 1000.0);
            if (endSec > totalSec) totalSec = endSec;
        }
        script.setDurationSeconds(totalSec);
        scriptRepository.save(script);

        log.info("[Align] scriptId={} mode={} totalSec={} sectionsUpd={} shotsUpd={} shotsSkip={} ms={} note={}",
                scriptId, mode, totalSec, sectionsUpdated, shotCounts[0], shotCounts[1],
                System.currentTimeMillis() - t0, note);

        return new AlignmentResult(mode, totalSec, sectionsUpdated, shotCounts[0], shotCounts[1], note);
    }

    /** 估算每段的 [startMs, endMs],按字数 / CHARS_PER_SEC,顺序累加。 */
    private LinkedHashMap<String, double[]> estimateByChars(List<ScriptSection> sections) {
        LinkedHashMap<String, double[]> out = new LinkedHashMap<>();
        double cumSec = 0;
        for (ScriptSection s : sections) {
            int chars = countNarrationChars(s.getTextContent());
            double dur = Math.max(minSectionSeconds(), chars / CHARS_PER_SEC);
            double startMs = cumSec * 1000.0;
            double endMs = (cumSec + dur) * 1000.0;
            out.put(s.getSectionCode(), new double[]{startMs, endMs});
            cumSec += dur;
        }
        return out;
    }

    /** 中文 narration 字符数(去掉标点和空白) —— 估算比 raw length 更接近真实朗读时长。 */
    private static int countNarrationChars(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) n++;
            else if (c >= '一' && c <= '鿿') n++;
        }
        return n;
    }

    /**
     * shots 按 sectionCode 分组,按段内旧 duration 占比分配该段新时长;
     * 没 sectionCode / sectionCode 不在 sectionMs 的 shot 跳过。
     */
    private int[] redistributeShots(List<StoryboardShot> shots, Map<String, double[]> sectionMs) {
        if (shots.isEmpty()) return new int[]{0, 0};
        Map<String, List<StoryboardShot>> bySection = new TreeMap<>();
        List<StoryboardShot> orphans = new ArrayList<>();
        for (StoryboardShot shot : shots) {
            String code = shot.getSectionCode();
            if (code == null || code.isBlank() || !sectionMs.containsKey(code)) {
                orphans.add(shot);
            } else {
                bySection.computeIfAbsent(code, k -> new ArrayList<>()).add(shot);
            }
        }
        if (!orphans.isEmpty()) {
            log.warn("[Align] {} 个 shot 没 sectionCode 或 sectionCode 越界,跳过 (shotIds={})",
                    orphans.size(),
                    orphans.stream().map(s -> String.valueOf(s.getId())).toList());
        }

        int updated = 0;
        for (Map.Entry<String, List<StoryboardShot>> e : bySection.entrySet()) {
            double[] range = sectionMs.get(e.getKey());
            double startSec = range[0] / 1000.0;
            double endSec = range[1] / 1000.0;
            double totalDur = Math.max(0.1, endSec - startSec);
            List<StoryboardShot> list = e.getValue();

            double sumOld = 0;
            for (StoryboardShot shot : list) {
                sumOld += defaultDur(shot);
            }
            // sumOld 为 0 时退化为均分
            double cumSec = startSec;
            for (StoryboardShot shot : list) {
                double oldDur = defaultDur(shot);
                double newDur = sumOld > 0 ? totalDur * oldDur / sumOld : totalDur / list.size();
                double shotEnd = cumSec + newDur;
                shot.setDurationSeconds(BigDecimal.valueOf(newDur).setScale(2, RoundingMode.HALF_UP));
                shot.setTimeRange(formatTimeRange(cumSec, shotEnd));
                shotRepository.save(shot);
                updated++;
                cumSec = shotEnd;
            }
        }
        return new int[]{updated, orphans.size()};
    }

    private static double defaultDur(StoryboardShot shot) {
        BigDecimal d = shot.getDurationSeconds();
        if (d == null) return 5.0;
        double v = d.doubleValue();
        return v > 0 ? v : 5.0;
    }

    /** "0-3s" / "3-6.5s" 风格,跟 LLM 原 storyboard 输出对齐。 */
    static String formatTimeRange(double startSec, double endSec) {
        return formatSec(startSec) + "-" + formatSec(endSec) + "s";
    }

    private static String formatSec(double v) {
        double rounded = Math.round(v * 10.0) / 10.0;
        if (Math.abs(rounded - Math.round(rounded)) < 0.05) {
            return String.valueOf((long) Math.round(rounded));
        }
        return String.format("%.1f", rounded);
    }
}
