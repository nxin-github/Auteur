package com.auteur.video;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auteur.video.* 配置入口。provider=ffmpeg(默认)|mock。
 */
@Data
@ConfigurationProperties(prefix = "auteur.video")
public class VideoProperties {

    /** ffmpeg | mock */
    private String provider = "ffmpeg";

    private final Ffmpeg ffmpeg = new Ffmpeg();
    private final Storage storage = new Storage();
    private final Remotion remotion = new Remotion();

    @Data
    public static class Ffmpeg {
        private String binaryPath = "ffmpeg";
        private int timeoutSeconds = 600;
        private int width  = 1080;
        private int height = 1920;
        private int fps    = 30;
        private int videoBitrateKbps = 4000;
        private int audioBitrateKbps = 128;
        private String subtitleFont = "PingFang SC";
        /** libass FontSize 以 PlayResY=288 为基准,实际像素 ≈ 字号 × 视频高 / 288。11 在 1920p 下约 73px。 */
        private int subtitleFontSize = 11;
        /** 单行字幕最大字符数(中英文都算 1 字),烧字幕前按上限软断行,优先在标点后切。 */
        private int subtitleMaxCharsPerLine = 14;
        /**
         * libass MarginV 按 PlayResY=288 缩放,50 在 1920p 下约 333px 距底,
         * 避开抖音标题 + 用户名 + 互动按钮区(底部 ≈250-300px)。
         */
        private int subtitleMarginV = 50;
    }

    @Data
    public static class Storage {
        private String localDir = "./storage/video";
        private String workDir  = "./storage/video/.work";
    }

    @Data
    public static class Remotion {
        private boolean enabled = false;
        private String rendererDir = "./renderer";
        private int timeoutSeconds = 1800;
        /**
         * Remotion 渲染需要 audio/image 以 http(s) URL 访问(不支持 file://)。
         * /api/files/... 相对路径会被拼上 publicBaseUrl。
         */
        private String publicBaseUrl = "http://localhost:8082";
        private String voiceUrlPrefix = "/api/files/voice/";
    }
}
