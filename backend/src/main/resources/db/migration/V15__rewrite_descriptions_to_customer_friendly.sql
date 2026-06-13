-- V15: 把 app_config 里所有 description 改成客户友好的中文白话
--
-- 背景:之前 description 写得偏向运维/工程师(夹杂 endpoint/Bucket/X-Api-Key/PlayResY 等
-- 技术词),前端 SystemConfig 直接展示给客户看不懂。本 migration 把它们全部改写成
-- 不需要任何技术背景就能理解的中文(保留必要的"例如"提示客户应该填什么)。
--
-- 不改 config_key、不改 config_value、不改 category — 只改 description 一列。
-- 客户已经手动改过的 config_value 不动。

-- ===== llm 大模型接口(面向客户必填) =====
UPDATE app_config SET description = '大模型接口地址,例如 https://relay.example.com/v1'                  WHERE config_key = 'auteur.llm.base-url';
UPDATE app_config SET description = '大模型接口密钥(中转站签发的访问凭证)'                            WHERE config_key = 'auteur.llm.api-key';
UPDATE app_config SET description = '默认大模型名称,例如 DeepSeek-V3.2 或 claude-opus-4-7'           WHERE config_key = 'auteur.llm.default-model';
UPDATE app_config SET description = '调用大模型的超时秒数(超时则报错重试)'                          WHERE config_key = 'auteur.llm.timeout-seconds';

-- ===== tos 云端文件存储(面向客户必填) =====
UPDATE app_config SET description = '火山引擎对象存储服务地址,例如 tos-cn-beijing.volces.com'        WHERE config_key = 'auteur.tos.endpoint';
UPDATE app_config SET description = '火山引擎对象存储所在区域,例如 cn-beijing'                       WHERE config_key = 'auteur.tos.region';
UPDATE app_config SET description = '火山引擎账号访问密钥(Access Key)'                              WHERE config_key = 'auteur.tos.access-key';
UPDATE app_config SET description = '火山引擎账号私有密钥(Secret Key)'                              WHERE config_key = 'auteur.tos.secret-key';
UPDATE app_config SET description = '火山引擎对象存储桶名称(用于存放视频/语音/封面文件)'           WHERE config_key = 'auteur.tos.bucket';

-- ===== voice 语音合成(面向客户必填 + 调参) =====
UPDATE app_config SET description = '火山引擎语音合成 API Key'                                       WHERE config_key = 'auteur.voice.volcano.api-key';
UPDATE app_config SET description = '火山引擎语音合成 App ID(异步合成必填)'                         WHERE config_key = 'auteur.voice.volcano.app-key';
UPDATE app_config SET description = '火山引擎语音合成 Access Key(异步合成必填)'                    WHERE config_key = 'auteur.voice.volcano.access-key';
UPDATE app_config SET description = '火山引擎语音合成资源 ID,例如 seed-tts-2.0'                     WHERE config_key = 'auteur.voice.volcano.resource-id';
UPDATE app_config SET description = '火山引擎语音合成服务地址'                                        WHERE config_key = 'auteur.voice.volcano.base-url';
UPDATE app_config SET description = '语音合成请求超时秒数(长文本建议 90 以上)'                     WHERE config_key = 'auteur.voice.volcano.http-timeout-seconds';
UPDATE app_config SET description = '试听文本(在「配音演员」页点试听时朗读这段)'                  WHERE config_key = 'auteur.voice.volcano.demo-text';
UPDATE app_config SET description = '是否走异步合成模式(推荐 true,无截断风险;false 走流式)'      WHERE config_key = 'auteur.voice.volcano.async-mode';
UPDATE app_config SET description = '异步合成的轮询间隔秒数'                                          WHERE config_key = 'auteur.voice.volcano.async-poll-interval-sec';
UPDATE app_config SET description = '异步合成最长等待秒数(超过则报错)'                             WHERE config_key = 'auteur.voice.volcano.async-max-wait-sec';
UPDATE app_config SET description = '异步合成单次查询超时秒数'                                        WHERE config_key = 'auteur.voice.volcano.async-query-timeout-seconds';
UPDATE app_config SET description = '合成完成后下载音频的超时秒数'                                    WHERE config_key = 'auteur.voice.volcano.async-download-timeout-seconds';

-- ===== bgm 背景音乐(面向客户必填) =====
UPDATE app_config SET description = 'Jamendo 背景音乐曲库的 Client ID(免费注册:https://devportal.jamendo.com/)' WHERE config_key = 'auteur.bgm.jamendo.client-id';
UPDATE app_config SET description = 'Jamendo 曲库接口地址'                                            WHERE config_key = 'auteur.bgm.jamendo.base-url';
UPDATE app_config SET description = 'Jamendo 接口超时秒数(改后需重启应用)'                         WHERE config_key = 'auteur.bgm.jamendo.timeout-seconds';

-- ===== extension 浏览器插件(面向客户必填) =====
UPDATE app_config SET description = '浏览器插件接入的认证令牌(Token)'                               WHERE config_key = 'auteur.extension.token';

-- ===== video 视频合成(运维调参) =====
UPDATE app_config SET description = '视频合成单次超时秒数'                                            WHERE config_key = 'auteur.video.ffmpeg.timeout-seconds';
UPDATE app_config SET description = '视频默认宽度(像素)'                                            WHERE config_key = 'auteur.video.ffmpeg.width';
UPDATE app_config SET description = '视频默认高度(像素)'                                            WHERE config_key = 'auteur.video.ffmpeg.height';
UPDATE app_config SET description = '视频帧率(每秒帧数)'                                            WHERE config_key = 'auteur.video.ffmpeg.fps';
UPDATE app_config SET description = '视频码率(kbps,越高画质越好但文件越大)'                       WHERE config_key = 'auteur.video.ffmpeg.video-bitrate-kbps';
UPDATE app_config SET description = '音频码率(kbps)'                                                  WHERE config_key = 'auteur.video.ffmpeg.audio-bitrate-kbps';
UPDATE app_config SET description = '字幕字体名称(留空自动按操作系统选,通常无需填写)'             WHERE config_key = 'auteur.video.ffmpeg.subtitle-font';
UPDATE app_config SET description = '字幕字号'                                                          WHERE config_key = 'auteur.video.ffmpeg.subtitle-font-size';
UPDATE app_config SET description = '字幕单行最多字符数(超过自动换行)'                              WHERE config_key = 'auteur.video.ffmpeg.subtitle-max-chars-per-line';
UPDATE app_config SET description = '字幕距底部距离'                                                    WHERE config_key = 'auteur.video.ffmpeg.subtitle-margin-v';
UPDATE app_config SET description = '视频渲染时素材访问地址(应用对外可达的 URL)'                  WHERE config_key = 'auteur.video.remotion.public-base-url';

-- ===== cover 封面 =====
UPDATE app_config SET description = '封面标题字体名(留空自动按操作系统选)'                          WHERE config_key = 'auteur.cover.java2d.font-family';

-- ===== tuning 业务质检规则(运维调参) =====
UPDATE app_config SET description = '分镜质检:最少镜头类型种数(低于则建议重新拆分)'              WHERE config_key = 'auteur.storyboard.critic.min-shot-type-variety';
UPDATE app_config SET description = '分镜质检:中近景占比上限(0~1 之间,超过则提示画面同质化)'    WHERE config_key = 'auteur.storyboard.critic.max-mid-ratio';
UPDATE app_config SET description = '分镜质检:允许的重复画面描述组数(0 = 完全不允许重复)'        WHERE config_key = 'auteur.storyboard.critic.max-duplicate-groups';
UPDATE app_config SET description = '图片审核:通过分数线(评分高于此值算合格)'                    WHERE config_key = 'auteur.image.audit.pass-threshold';
UPDATE app_config SET description = '图片审核:重新生成分数线(低于通过线但高于此值会自动重生)'    WHERE config_key = 'auteur.image.audit.regen-threshold';
UPDATE app_config SET description = '图片审核:单镜头最多重新生成次数'                              WHERE config_key = 'auteur.image.audit.max-regen-per-shot';
UPDATE app_config SET description = '镜头默认时长(秒,数据缺失时使用)'                              WHERE config_key = 'auteur.video.default-shot-sec';
UPDATE app_config SET description = '周复盘:拉取最近发布视频的条数上限'                            WHERE config_key = 'auteur.insights.weekly.recent-videos-limit';
UPDATE app_config SET description = '周复盘:最小样本数(本周视频少于此值不生成复盘)'              WHERE config_key = 'auteur.insights.weekly.min-sample-for-review';
UPDATE app_config SET description = '字幕对齐:段最小时长(秒,防止段过短产生字幕碎片)'             WHERE config_key = 'auteur.script.alignment.min-section-seconds';

-- ===== agent AI 助手对话(运维调参) =====
UPDATE app_config SET description = 'AI 助手单轮工具调用上限次数(防止失控)'                        WHERE config_key = 'auteur.agent.max-turns';
UPDATE app_config SET description = 'AI 助手对话最近保留多少轮(更早的会折叠成摘要)'                WHERE config_key = 'auteur.agent.k-recent-user-turns';
UPDATE app_config SET description = 'AI 助手单条消息最多字符数(超过自动截断)'                      WHERE config_key = 'auteur.agent.replay-max-chars';
UPDATE app_config SET description = 'AI 助手等待审批超时秒数(应稍大于审批响应超时)'                WHERE config_key = 'auteur.agent.approval-wait-seconds';
UPDATE app_config SET description = '审批响应超时秒数(超过未操作算拒绝)'                          WHERE config_key = 'auteur.agent.approval-decision-timeout-seconds';

-- ===== llm.retry 重试策略(运维调参) =====
UPDATE app_config SET description = '触发限流时最多重试次数'                                          WHERE config_key = 'auteur.llm.retry.limit-max-attempts';
UPDATE app_config SET description = '上游超时时最多重试次数(慢模型建议设 1)'                       WHERE config_key = 'auteur.llm.retry.timeout-max-attempts';
UPDATE app_config SET description = '网络错误时最多重试次数'                                          WHERE config_key = 'auteur.llm.retry.network-max-attempts';
UPDATE app_config SET description = 'JSON 解析失败时最多重试次数'                                     WHERE config_key = 'auteur.llm.retry.json-max-attempts';

-- ===== image 图片转存(运维调参) =====
UPDATE app_config SET description = '图片转存到云端的连接超时(毫秒)'                                WHERE config_key = 'auteur.image.compress.connect-timeout-ms';
UPDATE app_config SET description = '图片转存到云端的读取超时(毫秒)'                                WHERE config_key = 'auteur.image.compress.read-timeout-ms';

-- ===== model 大模型选型(在「AI 模型」页面展示,这里也改清楚) =====
UPDATE app_config SET description = '选题脑暴 — 各预设未指定模型时的默认值'                          WHERE config_key = 'auteur.model.brainstorm';
UPDATE app_config SET description = '脚本生成 — 各预设未指定模型时的默认值'                          WHERE config_key = 'auteur.model.script';
UPDATE app_config SET description = '脚本批评 — 各预设未指定模型时的默认值'                          WHERE config_key = 'auteur.model.script_critic';
UPDATE app_config SET description = '分镜生成 — 各预设未指定模型时的默认值'                          WHERE config_key = 'auteur.model.storyboard';
UPDATE app_config SET description = 'BGM 情绪标注 — 给脚本打情绪标签'                                WHERE config_key = 'auteur.model.bgm_mood';
UPDATE app_config SET description = '续集钩子抽取 — 从结尾段提取下一集线索'                          WHERE config_key = 'auteur.model.hook_extract';
UPDATE app_config SET description = '事实核查(初查)— 找疑点'                                       WHERE config_key = 'auteur.model.factcheck';
UPDATE app_config SET description = '事实核查(复查)— 验证疑点是否成立'                             WHERE config_key = 'auteur.model.factcheck_verify';
UPDATE app_config SET description = '事实核查改写 — 把核查建议落到脚本上'                            WHERE config_key = 'auteur.model.factcheck_apply';
UPDATE app_config SET description = '视频归因 — 已发布视频的归因分析'                                WHERE config_key = 'auteur.model.video_attribution';
UPDATE app_config SET description = '周复盘 — 跨视频周度数据洞察'                                    WHERE config_key = 'auteur.model.weekly_review';
UPDATE app_config SET description = '分镜文案精修 — 中文画面 → 英文 prompt'                         WHERE config_key = 'auteur.model.shot_prompt_refine';
UPDATE app_config SET description = '分镜文案脱敏 — 上游审查触发后自动改写'                          WHERE config_key = 'auteur.model.shot_prompt_desensitize';
UPDATE app_config SET description = '图像审核 — 给单张分镜画面打分(0-100)+ 通过/重生/人工'         WHERE config_key = 'auteur.model.image_audit';
UPDATE app_config SET description = '图像主模型 — 各预设未指定时的默认值'                            WHERE config_key = 'auteur.model.image_primary';
UPDATE app_config SET description = '图像降级模型 — 主模型超时/不可用时强制使用'                    WHERE config_key = 'auteur.model.image_fallback';
UPDATE app_config SET description = 'AI 助手新会话默认模型'                                            WHERE config_key = 'auteur.model.agent_default';
