-- V10: voice/bgm 第三方服务的运行时参数迁到 app_config(批 1)
--
-- 背景:
--   apiKey/accessKey/clientId 等密钥已在 V1 迁过。本批补齐"非密钥但仍需运维调"的部分:
--   超时秒数、API base-url、试听文本、异步轮询参数等。改这些值之前要 SSH 进容器改 yml + 重启;
--   迁完后在前端「系统设置」UI 直接编辑。
--
-- 部署后处理:
--   1. backend 启动 → Flyway 跑本 migration,UPDATE COALESCE 灌默认值(等同 yml 当前硬编码)
--   2. backend Java 业务代码已改成 runtimeConfig.get(key, fallback) 模式
--   3. application-local.yml / application-docker.yml / application.yml 里的对应行可删除
--      (DB 已是单一可信源,yml 兜底 fallback 留在 Java 代码里)
--
-- 影响:
--   - voice.volcano.* 8 项:VolcanoVoiceClient 每请求重读,改 DB 立即生效(无需重启)
--   - bgm.jamendo.{base-url,timeout-seconds} 2 项:RestClient bean 启动时构造,改 DB 需重启 backend

INSERT IGNORE INTO app_config (config_key, description, is_secret, category, sort_order) VALUES
  -- voice (火山引擎 TTS) 非密钥参数
  ('auteur.voice.volcano.base-url',                       '火山 TTS 公共服务地址',                                          0, 'voice', 60),
  ('auteur.voice.volcano.http-timeout-seconds',           '火山 TTS HTTP 请求超时(秒);异步合成长文本可设 90+',           0, 'voice', 70),
  ('auteur.voice.volcano.demo-text',                      '配音演员页试听文本;改后下次试听新合成生效',                     0, 'voice', 80),
  ('auteur.voice.volcano.async-mode',                     '异步任务模式开关:true=submit+query(无截断风险),false=单向流式', 0, 'voice', 90),
  ('auteur.voice.volcano.async-poll-interval-sec',        '异步模式轮询间隔(秒)',                                         0, 'voice', 100),
  ('auteur.voice.volcano.async-max-wait-sec',             '异步模式总等待上限(秒);超时报错',                              0, 'voice', 110),
  ('auteur.voice.volcano.async-query-timeout-seconds',    '异步 query 单次 HTTP 超时(秒)',                                0, 'voice', 120),
  ('auteur.voice.volcano.async-download-timeout-seconds', '异步合成完后下载 mp3 的 HTTP 超时(秒)',                       0, 'voice', 130),

  -- bgm (Jamendo) 非密钥参数
  ('auteur.bgm.jamendo.base-url',                         'Jamendo API 基址',                                              0, 'bgm', 20),
  ('auteur.bgm.jamendo.timeout-seconds',                  'Jamendo HTTP 超时(秒);改后需重启 backend',                    0, 'bgm', 30);

-- 灌默认值 = yml 现有硬编码,确保迁移后行为不变。COALESCE 保证已配置过的非空值不被覆盖(幂等)
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'https://openspeech.bytedance.com')          WHERE config_key = 'auteur.voice.volcano.base-url';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '90')                                         WHERE config_key = 'auteur.voice.volcano.http-timeout-seconds';
-- demo-text 不灌默认值;客户在「系统设置 → AI 语音合成」UI 自己填(VoiceDemoService 检测空时会提示)
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'true')                                       WHERE config_key = 'auteur.voice.volcano.async-mode';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '3')                                          WHERE config_key = 'auteur.voice.volcano.async-poll-interval-sec';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '300')                                        WHERE config_key = 'auteur.voice.volcano.async-max-wait-sec';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '30')                                         WHERE config_key = 'auteur.voice.volcano.async-query-timeout-seconds';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '120')                                        WHERE config_key = 'auteur.voice.volcano.async-download-timeout-seconds';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'https://api.jamendo.com/v3.0')              WHERE config_key = 'auteur.bgm.jamendo.base-url';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '30')                                         WHERE config_key = 'auteur.bgm.jamendo.timeout-seconds';
