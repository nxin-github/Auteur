-- V14: review 修复 — 修正 V11/V12 的两类问题
--
-- 1) approval-wait-seconds 默认值错误(critical):
--    V12 写入 '65' 但代码 APPROVAL_WAIT_SECONDS_DEFAULT=305(因为本 PR 把 ApprovalGate
--    decision-timeout 从 60 升到 300,wait 应稍大于 decision-timeout)。
--    后果:Agent 在 65s 抛 TimeoutException,ApprovalGate 在 300s 才发 rejected → 用户
--    若取了 65s+ 才点批准,Agent 已经放弃了。
--
-- 2) 字体类默认值会让 Linux/Docker fresh deploy 字幕渲染失败(regression):
--    V11 全局默认 'PingFang SC' 是 macOS 字体;原 application-docker.yml 里有 profile
--    覆盖到 'Noto Sans CJK SC',这次重构把 yml 行删了导致 Linux/Docker 拿不到正确字体。
--    本 migration 把"看起来是 V11 默认值"的那条清空,让代码端按 OS 兜底
--    (FfmpegVideoRenderer.osAwareSubtitleFont() / Java2DCoverRenderer.pickFont())。
--    用户已经手动改过的字体值不动(只匹配 'PingFang SC' 这个 V11 SQL 写入的特定字符串)。
--
-- 注意:本 migration 是修复性质,即使 V11/V12 的 fresh deploy 已经跑过,跑 V14 后行为
-- 也会恢复正确(approval 改成 305,字体走 OS 兜底)。
-- 不会撞已有用户配置:
--   - approval-wait-seconds:V12 默认 65 是错的,本 migration 强制更新到 305
--     但只在 DB 还是 '65' 时改;若用户已手动改为别的值(比如调短到 120),不动。
--   - 字体:只清匹配 'PingFang SC' 的那条;用户改成别的(比如 'Microsoft YaHei')不动。

-- 1) Fix approval-wait-seconds default(只修 V12 误写入的 '65')
UPDATE app_config
SET config_value = '305'
WHERE config_key = 'auteur.agent.approval-wait-seconds'
  AND config_value = '65';

-- 2) Clear V11 写入的字体默认值,让代码端按 OS 兜底
--    用户已改过的(任何其它值)保持不动
UPDATE app_config
SET config_value = NULL
WHERE config_key IN (
        'auteur.video.ffmpeg.subtitle-font',
        'auteur.cover.java2d.font-family'
      )
  AND config_value = 'PingFang SC';
