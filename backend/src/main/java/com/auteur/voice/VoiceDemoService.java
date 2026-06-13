package com.auteur.voice;

import com.auteur.voice.volcano.VolcanoConfigResolver;
import com.auteur.voice.volcano.VolcanoTtsHttpClient;
import com.auteur.voice.volcano.VolcanoVoiceCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 音色试听:合成一段 demoText 落盘。同 voiceType + speed 复用缓存,切换 speed 才重新合成。
 * 仅 provider=volcano 时实际工作;mock 抛异常。
 */
@Slf4j
@Service
public class VoiceDemoService {

    private static final String VOICE_URL_PREFIX = "/api/files/voice/";

    private final VoiceProperties props;
    private final VolcanoVoiceCatalog catalog;
    private final VolcanoConfigResolver volcanoConfig;

    public VoiceDemoService(VoiceProperties props, VolcanoVoiceCatalog catalog,
                            VolcanoConfigResolver volcanoConfig) {
        this.props = props;
        this.catalog = catalog;
        this.volcanoConfig = volcanoConfig;
    }

    /** @return audioUrl,形如 /api/files/voice/demo-{voice}-{speedRate}.mp3 */
    public String getDemoUrl(String voiceType, Double speed) {
        if (voiceType == null || voiceType.isBlank()) {
            throw new IllegalArgumentException("voiceType 不能为空");
        }
        if (!"volcano".equalsIgnoreCase(props.getProvider())) {
            throw new IllegalStateException("当前 voice provider=" + props.getProvider() + ",试听仅在 volcano 模式下可用");
        }
        if (!catalog.has(voiceType)) {
            throw new IllegalArgumentException("「" + voiceType + "」不是火山云音色，无法试听。请从下拉菜单选择 zh_ 开头的音色。");
        }

        // speed → speechRate 公式与 VolcanoVoiceClient.clampSpeechRate 一致
        double sp = speed != null ? speed : 1.0;
        int speechRate = Math.max(-50, Math.min(50, (int) Math.round((sp - 1.0) * 100)));

        String safeVoice = voiceType.replaceAll("[^A-Za-z0-9_\\-]", "_");
        // speedRate 带符号文件名加 'n'/'p'/'z' 前缀避免 shell 解析负号
        String speedTag = speechRate < 0 ? "n" + (-speechRate)
                        : speechRate > 0 ? "p" + speechRate
                        : "z";
        String fileName = String.format("demo-%s-%s.mp3", safeVoice, speedTag);
        Path dir = Paths.get(props.getStorage().getLocalDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("create voice storage dir failed: " + dir, e);
        }
        Path mp3 = dir.resolve(fileName);

        if (Files.exists(mp3)) {
            log.info("[VoiceDemo] cache hit voice={} speed={} -> {}", voiceType, sp, fileName);
            return VOICE_URL_PREFIX + fileName;
        }

        VoiceProperties.Volcano cfg = volcanoConfig.resolve(props);
        String text = cfg.getDemoText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("auteur.voice.volcano.demo-text 为空,请到「系统设置 → 火山语音合成」填写示例文本");
        }
        log.info("[VoiceDemo] synth voice={} speed={} chars={}", voiceType, sp, text.length());
        VolcanoTtsHttpClient session = new VolcanoTtsHttpClient(cfg);
        VolcanoTtsHttpClient.Result r = session.synthesize(voiceType, text, speechRate, 0);
        if (r.audioBytes() == null || r.audioBytes().length == 0) {
            throw new RuntimeException("Volcano TTS 返回空音频");
        }
        try {
            Files.write(mp3, r.audioBytes());
        } catch (IOException e) {
            throw new RuntimeException("写入 demo mp3 失败: " + mp3, e);
        }
        log.info("[VoiceDemo] saved voice={} speed={} bytes={} -> {}",
                voiceType, sp, r.audioBytes().length, fileName);
        return VOICE_URL_PREFIX + fileName;
    }
}
