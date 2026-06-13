-- V13: 业务调优旋钮迁到 app_config(批 4 / 最后一批)
--
-- 背景:
--   分镜审核阈值、图片审核分数线、默认镜头时长、复盘查询样本量、字幕对齐最小时长 — 这些"业务旋钮"
--   过去散在各 Service 的 private static final 常量里。改一个要 recompile + redeploy,前端运维改不了。
--   迁完后前端「系统设置 → 业务调优」UI 即时编辑。
--
-- 迁移内容(10 项):
--   - storyboard.critic 3 项:min-shot-type-variety, max-mid-ratio, max-duplicate-groups
--   - image.audit 3 项:pass-threshold, regen-threshold, max-regen-per-shot
--   - video 1 项:default-shot-sec
--   - insights.weekly 2 项:recent-videos-limit, min-sample-for-review
--   - script.alignment 1 项:min-section-seconds
--
-- 不迁的相关项:
--   - VideoAssemblyService.DEFAULT_W / DEFAULT_H:代码里未引用,死代码不需要新 key
--
-- 部署后处理:
--   1. backend 启动 → Flyway 跑本 migration
--   2. 业务 Service 已注入 RuntimeConfig + 改读 DB
--   3. 前端 SystemConfig.vue 已加 'tuning' category 分组

INSERT IGNORE INTO app_config (config_key, description, is_secret, category, sort_order) VALUES
  -- 分镜审核 (StoryboardCriticService)
  ('auteur.storyboard.critic.min-shot-type-variety',  '分镜批评 — 最少镜头类型种数(低于则建议重拆)',                       0, 'tuning', 10),
  ('auteur.storyboard.critic.max-mid-ratio',          '分镜批评 — 中近景占比上限(0~1,超过则警告同质化)',                  0, 'tuning', 20),
  ('auteur.storyboard.critic.max-duplicate-groups',   '分镜批评 — 允许的重复 prompt 组数(0=完全不允许)',                  0, 'tuning', 30),

  -- 图片审核 (ImageAuditService)
  ('auteur.image.audit.pass-threshold',                '图片审核 — 通过分数线(score >= 直接 PASS)',                       0, 'tuning', 100),
  ('auteur.image.audit.regen-threshold',               '图片审核 — 重生分数线(score >= 进入 REGENERATE,否则 FAIL)',     0, 'tuning', 110),
  ('auteur.image.audit.max-regen-per-shot',            '图片审核 — 单 shot 最多重生几次(防无限重生)',                     0, 'tuning', 120),

  -- 视频默认时长 (VideoAssemblyService)
  ('auteur.video.default-shot-sec',                    '镜头默认时长(秒);shot.duration_seconds 缺失或 ≤0 时兜底',          0, 'tuning', 200),

  -- 复盘查询 (WeeklyReviewService)
  ('auteur.insights.weekly.recent-videos-limit',       '周复盘 — 拉取最近发布视频的硬上限(条)',                            0, 'tuning', 300),
  ('auteur.insights.weekly.min-sample-for-review',     '周复盘 — 最小样本数(本周视频数低于此值不生成复盘)',                0, 'tuning', 310),

  -- 字幕对齐 (ScriptAlignmentService)
  ('auteur.script.alignment.min-section-seconds',      '字幕对齐 — 段最小时长(秒);防止段过短导致字幕碎片',                 0, 'tuning', 400);

UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '4')    WHERE config_key = 'auteur.storyboard.critic.min-shot-type-variety';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '0.60') WHERE config_key = 'auteur.storyboard.critic.max-mid-ratio';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '0')    WHERE config_key = 'auteur.storyboard.critic.max-duplicate-groups';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '80')   WHERE config_key = 'auteur.image.audit.pass-threshold';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '60')   WHERE config_key = 'auteur.image.audit.regen-threshold';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '1')    WHERE config_key = 'auteur.image.audit.max-regen-per-shot';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '5.0')  WHERE config_key = 'auteur.video.default-shot-sec';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '30')   WHERE config_key = 'auteur.insights.weekly.recent-videos-limit';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '3')    WHERE config_key = 'auteur.insights.weekly.min-sample-for-review';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '2.0')  WHERE config_key = 'auteur.script.alignment.min-section-seconds';
