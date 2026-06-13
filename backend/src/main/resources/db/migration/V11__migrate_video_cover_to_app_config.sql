-- V11: video/cover 视频合成参数迁到 app_config(批 2)
--
-- 背景:
--   ffmpeg 字幕字号 / 单行字数 / 比特率 / 帧率 / 默认分辨率,以及 Remotion publicBaseUrl、
--   封面字体 — 这些都是"调一下就要重新合成观察效果"的参数,改 yml + 重启太重。
--
-- 不迁的相关项(留 yml,本 migration 不动):
--   - auteur.video.provider:@ConditionalOnProperty 启动期决策,Spring 从 Environment 读不读 DB
--   - auteur.video.ffmpeg.binary-path:本机/Linux/Docker 不同,留 yml
--   - auteur.video.remotion.enabled / .renderer-dir:profile 决策 + 启动副作用
--   - auteur.cover.java2d.timeout-seconds:代码里未消费,跳过(不需要新增 dead key)
--
-- 部署后处理:
--   1. backend 启动 → Flyway 跑本 migration,UPDATE COALESCE 灌默认值
--   2. 业务代码已改成 runtimeConfig.get(key, props.getXxx()) 模式:DB 优先,yml 兜底
--   3. application.yml / application-local.yml / application-docker.yml 对应 11 行可删
--
-- 影响:
--   - 11 项视频/封面参数 — 每次合成现读,改 DB 立即对下次合成生效(无需重启)
--   - 注意:Remotion publicBaseUrl 是给 Remotion 浏览器拉素材用的对外 base 地址,
--          backend 部署在容器后改这个值要保证容器外可达

INSERT IGNORE INTO app_config (config_key, description, is_secret, category, sort_order) VALUES
  ('auteur.video.ffmpeg.timeout-seconds',            'ffmpeg 单次合成超时(秒);超时进程被杀',                            0, 'video', 10),
  ('auteur.video.ffmpeg.width',                      '默认视频宽(像素);单 topic 可在 video.width 字段覆盖',           0, 'video', 20),
  ('auteur.video.ffmpeg.height',                     '默认视频高(像素);单 topic 可在 video.height 字段覆盖',          0, 'video', 30),
  ('auteur.video.ffmpeg.fps',                        '帧率',                                                              0, 'video', 40),
  ('auteur.video.ffmpeg.video-bitrate-kbps',         '视频比特率(kbps)',                                                 0, 'video', 50),
  ('auteur.video.ffmpeg.audio-bitrate-kbps',         '音频比特率(kbps)',                                                 0, 'video', 60),
  ('auteur.video.ffmpeg.subtitle-font',              '字幕字体名;Linux/Docker 用 Noto Sans CJK SC,macOS 用 PingFang SC', 0, 'video', 70),
  ('auteur.video.ffmpeg.subtitle-font-size',         '字幕字号(libass FontSize,基于 PlayResY=288)',                  0, 'video', 80),
  ('auteur.video.ffmpeg.subtitle-max-chars-per-line','单行字幕最多字符数,超过自动软断行',                                 0, 'video', 90),
  ('auteur.video.ffmpeg.subtitle-margin-v',          '字幕距底距离(libass MarginV)',                                    0, 'video', 100),
  ('auteur.video.remotion.public-base-url',          'Remotion 浏览器拉 /api/files/* 素材用的对外 base URL',             0, 'video', 110),
  ('auteur.cover.java2d.font-family',                '封面标题字体名(优先栈,系统找不到回 sans-serif)',                  0, 'cover', 10);

-- 灌默认值 = yml 现有硬编码;COALESCE 保证已有非空值不被覆盖(幂等)
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '600')                              WHERE config_key = 'auteur.video.ffmpeg.timeout-seconds';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '1080')                             WHERE config_key = 'auteur.video.ffmpeg.width';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '1920')                             WHERE config_key = 'auteur.video.ffmpeg.height';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '30')                               WHERE config_key = 'auteur.video.ffmpeg.fps';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '4000')                             WHERE config_key = 'auteur.video.ffmpeg.video-bitrate-kbps';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '128')                              WHERE config_key = 'auteur.video.ffmpeg.audio-bitrate-kbps';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'PingFang SC')                      WHERE config_key = 'auteur.video.ffmpeg.subtitle-font';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '11')                               WHERE config_key = 'auteur.video.ffmpeg.subtitle-font-size';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '14')                               WHERE config_key = 'auteur.video.ffmpeg.subtitle-max-chars-per-line';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '50')                               WHERE config_key = 'auteur.video.ffmpeg.subtitle-margin-v';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'http://localhost:8082')            WHERE config_key = 'auteur.video.remotion.public-base-url';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'PingFang SC')                      WHERE config_key = 'auteur.cover.java2d.font-family';
