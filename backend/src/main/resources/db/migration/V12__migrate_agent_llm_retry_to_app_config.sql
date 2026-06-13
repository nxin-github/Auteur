-- V12: 代码里硬编码的 timeout / 重试 / agent loop 参数迁到 app_config(批 3)
--
-- 背景:
--   AgentLoopService.MAX_TURNS / REPLAY_MAX_CHARS 等"运维可调"常量散在 Java 代码里。
--   改一次要 recompile + redeploy。本批迁到 DB,前端「系统设置」UI 即时编辑(下次请求生效)。
--
-- 迁移内容(12 项):
--   - agent loop 5 项:max-turns, k-recent-user-turns, replay-max-chars,
--                    approval-wait-seconds, approval-decision-timeout-seconds
--   - llm 1 项:    timeout-seconds(原 application.yml)
--   - llm.retry 4 项:limit/timeout/network/json 各类错误的 max-attempts
--   - image 2 项:  compress.connect-timeout-ms, compress.read-timeout-ms
--
-- 不迁的相关项(留代码硬编码):
--   - RetryPolicy 各档延迟 base/cap ms:静态 utility 类,改造 Spring bean 涉及面大,
--     且这些值"set and forget",运维很少调。延迟到独立批次专门处理。
--
-- 部署后处理:
--   1. backend 启动 → Flyway 跑本 migration,UPDATE COALESCE 灌默认值(等同代码硬编码)
--   2. AgentLoopService / ApprovalGate / ImageClient 已注入 RuntimeConfig,改 DB 立即生效
--   3. application.yml 删除 auteur.llm.timeout-seconds 一行

INSERT IGNORE INTO app_config (config_key, description, is_secret, category, sort_order) VALUES
  -- agent loop 行为参数
  ('auteur.agent.max-turns',                          'Agent 单轮工具调用上限次数;达上限强制收尾',                       0, 'agent', 10),
  ('auteur.agent.k-recent-user-turns',                'Agent 重放滑窗:最近保留几个 user 轮次;超出折叠为 summary',        0, 'agent', 20),
  ('auteur.agent.replay-max-chars',                   'Agent 重放给 LLM 单条消息字符上限;超过截断',                       0, 'agent', 30),
  ('auteur.agent.approval-wait-seconds',              'Agent 等审批 future.get 超时(秒);稍大于 decision-timeout 留兜底', 0, 'agent', 40),
  ('auteur.agent.approval-decision-timeout-seconds',  'HITL 审批用户响应超时(秒);超时算拒绝',                            0, 'agent', 50),

  -- LLM 全局
  ('auteur.llm.timeout-seconds',                      'LLM HTTP 请求总超时(秒)',                                          0, 'llm', 100),

  -- LLM 重试 max-attempts(per error class)
  ('auteur.llm.retry.limit-max-attempts',             '触发限流(429/limit)时最多重试几次',                                0, 'llm', 110),
  ('auteur.llm.retry.timeout-max-attempts',           '上游超时时最多重试几次(慢模型如 gpt-image-2 建议 1)',           0, 'llm', 120),
  ('auteur.llm.retry.network-max-attempts',           '网络抖动/连接错误最多重试几次',                                     0, 'llm', 130),
  ('auteur.llm.retry.json-max-attempts',              'JSON 解析失败最多重试几次',                                         0, 'llm', 140),

  -- image 客户端
  ('auteur.image.compress.connect-timeout-ms',        '生图临时 URL 下载到 TOS 时的连接超时(ms)',                         0, 'llm', 200),
  ('auteur.image.compress.read-timeout-ms',           '生图临时 URL 下载到 TOS 时的读超时(ms)',                           0, 'llm', 210);

-- 灌默认值 = 代码 / yml 现有硬编码,跑完迁移后行为不变
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '8')         WHERE config_key = 'auteur.agent.max-turns';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '6')         WHERE config_key = 'auteur.agent.k-recent-user-turns';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '32000')     WHERE config_key = 'auteur.agent.replay-max-chars';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '65')        WHERE config_key = 'auteur.agent.approval-wait-seconds';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '300')       WHERE config_key = 'auteur.agent.approval-decision-timeout-seconds';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '600')       WHERE config_key = 'auteur.llm.timeout-seconds';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '3')         WHERE config_key = 'auteur.llm.retry.limit-max-attempts';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '1')         WHERE config_key = 'auteur.llm.retry.timeout-max-attempts';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '3')         WHERE config_key = 'auteur.llm.retry.network-max-attempts';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '2')         WHERE config_key = 'auteur.llm.retry.json-max-attempts';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '15000')     WHERE config_key = 'auteur.image.compress.connect-timeout-ms';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), '60000')     WHERE config_key = 'auteur.image.compress.read-timeout-ms';
