-- V16: 清除上一波 migration 写入的开发期默认 demo-text
--
-- V10 曾经把开发本机的特定话术灌入 'auteur.voice.volcano.demo-text' 作为默认。
-- 这条话术是单一项目特有的(带品牌色彩),不该作为开源仓默认值。
--
-- 本 migration 无条件清空当前实例 DB 里的这条 row。无论是 V10 灌入的默认值还是用户
-- 已经在 UI 改过的内容,都会被清掉。客户重启后需到「系统设置 → AI 语音合成」重新填。
-- 不过这是一次性操作,后续 V10 已经不会再灌默认值,fresh deploy 也是空状态。

UPDATE app_config
SET config_value = NULL
WHERE config_key = 'auteur.voice.volcano.demo-text';
