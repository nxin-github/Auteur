package com.auteur.voice.volcano;

import com.auteur.storage.TosStorageService;
import com.auteur.video.SrtParser;
import com.auteur.voice.VoiceClient;
import com.auteur.voice.VoiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 火山引擎 TTS。基于 VolcanoTtsHttpClient/AsyncClient 跑请求,拿完整 mp3 + 句级时间戳。
 *
 * 句级字幕转标准 SRT 上传 TOS,无字幕时下游 ShotTimingResolver 走 UNIFORM_SCALE 回落。
 *
 * 字段映射:
 *   speed → speech_rate(0.6-1.5 → -50~+50)
 *   pitch → pitch_rate(-6~+6 → -10~+10)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "auteur.voice.provider", havingValue = "volcano")
public class VolcanoVoiceClient implements VoiceClient {

    /** 豆包 TTS 大模型 ~¥1/万字 = ¥0.0001/字。 */
    private static final BigDecimal COST_PER_CHAR = new BigDecimal("0.0001");

    private final VoiceProperties props;
    private final VolcanoVoiceCatalog catalog;
    private final TosStorageService tos;
    private final VolcanoConfigResolver volcanoConfig;

    public VolcanoVoiceClient(VoiceProperties props, VolcanoVoiceCatalog catalog, TosStorageService tos,
                              VolcanoConfigResolver volcanoConfig) {
        this.props = props;
        this.catalog = catalog;
        this.tos = tos;
        this.volcanoConfig = volcanoConfig;
    }

    @Override
    public Result synthesize(Request req) {
        VoiceProperties.Volcano cfg = volcanoConfig.resolve(props);
        String voiceType = pickVoiceType(req.voiceModel());
        String text = req.fullText() == null ? "" : req.fullText();
        if (text.isBlank()) {
            throw new IllegalArgumentException("synthesize text is empty");
        }
        int speechRate = clampSpeechRate(req.speed() != null ? req.speed().doubleValue() : 1.0);
        int pitchRate  = clampPitch(req.pitch() != null ? req.pitch() : 0);

        log.info("[VoiceVolcano] scriptId={} voice={} chars={} speechRate={} pitch={}",
                req.scriptId(), voiceType, text.length(), speechRate, pitchRate);

        // 异步模式:服务端合成完整 mp3 后返回 URL,无流式截断风险
        VolcanoTtsHttpClient.Result ttsResult;
        if (cfg.isAsyncMode()) {
            log.info("[VoiceVolcano] scriptId={} 走异步任务接口(submit + poll query)", req.scriptId());
            VolcanoTtsAsyncClient asyncSession = new VolcanoTtsAsyncClient(cfg);
            ttsResult = asyncSession.synthesize(voiceType, text, speechRate, pitchRate);
        } else {
            VolcanoTtsHttpClient session = new VolcanoTtsHttpClient(cfg);
            ttsResult = session.synthesize(voiceType, text, speechRate, pitchRate);
        }

        if (ttsResult.audioBytes() == null || ttsResult.audioBytes().length == 0) {
            throw new RuntimeException("V3Http TTS returned empty audio");
        }

        // 句级 cue 拆短:火山 sentence 平均 ~13s/句太长,按中文标点拆到目标 ~6s/cue,
        // 让 storyboard 出更密的 shot;splitter 同时跳空文本 sentence。trimGaps=true 时启用。
        if (req.trimGaps() && ttsResult.sentences() != null && !ttsResult.sentences().isEmpty()) {
            List<VolcanoTtsHttpClient.Sentence> split = VolcanoSentenceSplitter.split(ttsResult.sentences());
            if (split.size() != ttsResult.sentences().size()) {
                log.info("[VoiceVolcano] scriptId={} 句拆短 {} → {} 个 cue", req.scriptId(),
                        ttsResult.sentences().size(), split.size());
                ttsResult = new VolcanoTtsHttpClient.Result(
                        ttsResult.audioBytes(), split, ttsResult.voiceType());
            }
        }

        long ts = System.currentTimeMillis();
        String safeVoice = voiceType.replaceAll("[^A-Za-z0-9_\\-]", "_");
        String mp3Name = String.format("script-%d-%s-%d.mp3", req.scriptId(), safeVoice, ts);
        String srtName = String.format("script-%d-%s-%d.srt", req.scriptId(), safeVoice, ts);

        String audioUrl = tos.upload(
                TosStorageService.buildKey(req.scriptId(), "voice", mp3Name),
                ttsResult.audioBytes(), "audio/mpeg");

        // 有句级时间戳就转 SRT 上传,否则 null 让下游 UNIFORM_SCALE 处理
        String subtitleUrl = null;
        if (ttsResult.sentences() != null && !ttsResult.sentences().isEmpty()) {
            try {
                byte[] srtBytes = sentencesToSrt(ttsResult.sentences()).getBytes(StandardCharsets.UTF_8);
                subtitleUrl = tos.upload(
                        TosStorageService.buildKey(req.scriptId(), "voice", srtName),
                        srtBytes, "text/plain");
                log.info("[VoiceVolcano] scriptId={} srt 上传 TOS cues={}", req.scriptId(), ttsResult.sentences().size());
            } catch (Exception e) {
                log.warn("[VoiceVolcano] srt 上传失败,字幕降级: {}", e.toString());
            }
        } else {
            log.info("[VoiceVolcano] scriptId={} 无句级时间戳,字幕走 UNIFORM_SCALE 回落", req.scriptId());
        }

        // 时长用 sentences 末句 endMs。异步接口下与 mp3 实长偏差通常 < 200ms,不需要 ffprobe;
        // 失败兜底字符估算。
        int durationSec;
        if (ttsResult.sentences() != null && !ttsResult.sentences().isEmpty()) {
            long lastEndMs = ttsResult.sentences().get(ttsResult.sentences().size() - 1).endMs();
            durationSec = Math.max(1, (int) Math.round(lastEndMs / 1000.0));
        } else {
            durationSec = Math.max(1, (int) Math.round(text.length() / 4.5));
        }
        BigDecimal cost = BigDecimal.valueOf(text.length())
                .multiply(COST_PER_CHAR).setScale(4, RoundingMode.HALF_UP);

        log.info("[VoiceVolcano] scriptId={} done duration~{}s cost=¥{} audio={} bytes={} sentences={}",
                req.scriptId(), durationSec, cost, audioUrl, ttsResult.audioBytes().length,
                ttsResult.sentences() == null ? 0 : ttsResult.sentences().size());

        return new Result(audioUrl, subtitleUrl, durationSec, cost, voiceType);
    }

    private String pickVoiceType(String requested) {
        if (requested != null && catalog.has(requested)) return requested;
        if (requested != null && !requested.isBlank()) {
            log.warn("[VoiceVolcano] voice '{}' 不在 catalog,fallback 默认", requested);
        }
        return catalog.defaultVoice();
    }

    private int clampSpeechRate(double speed) {
        int rate = (int) Math.round((speed - 1.0) * 100);
        return Math.max(-50, Math.min(50, rate));
    }

    private int clampPitch(int p) {
        int rate = p * 2;
        return Math.max(-10, Math.min(10, rate));
    }

    private static String sentencesToSrt(List<VolcanoTtsHttpClient.Sentence> sentences) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (VolcanoTtsHttpClient.Sentence s : sentences) {
            if (s.text() == null || s.text().isBlank()) continue;
            if (s.endMs() <= s.beginMs()) continue;
            // 防御 sentence.text 内嵌换行/回车:火山某些 sentence text 里有 \n / \r,
            // 直接写到 SRT 块会被 SrtParser.split("\n\\s*\n") 错切成两半丢块。压缩成单空格。
            String text = s.text().trim()
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .replaceAll(" {2,}", " ");
            if (text.isBlank()) continue;
            idx++;
            sb.append(idx).append('\n');
            sb.append(SrtParser.formatTime(s.beginMs())).append(" --> ").append(SrtParser.formatTime(s.endMs())).append('\n');
            sb.append(text).append("\n\n");
        }
        return sb.toString();
    }
}
