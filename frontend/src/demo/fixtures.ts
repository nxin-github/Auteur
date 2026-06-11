/**
 * Demo fixtures 总表 —— 真实流水线数据
 *
 * 数据来源: 真实跑了一条 "今天体验的人生副本：偷外卖的人的一生"(topic id=15, script id=12),
 * 由 backend/scripts/export-demo-fixtures.py 导出。资源(图/音频/视频)直接走 TOS URL,
 * demo 站不带本地拷贝。
 *
 * 主表把 method + URL 模板映射到响应数据。这里只列演示路径会用到的核心 endpoints。
 * 其余未列的会被 interceptor 默认返回 null,不会让 demo 站炸,只是 console 里有一行 warn。
 */

import type { InternalAxiosRequestConfig } from 'axios'

// ─── 真实 fixtures(从 ./fixtures/*.json 导入)───────────
import topicFixture from './fixtures/topic.json'
import scriptFixture from './fixtures/script.json'
import scriptSectionsFixture from './fixtures/script-sections.json'
import brainstormResultFixture from './fixtures/brainstorm-result.json'
import storyboardFixture from './fixtures/storyboard.json'
import imagesFixture from './fixtures/images.json'
import voiceFixture from './fixtures/voice.json'
import videosFixture from './fixtures/videos.json'
import coversFixture from './fixtures/covers.json'
import presetFixture from './fixtures/preset.json'
import criticLogsFixture from './fixtures/critic-logs.json'
import factcheckIssuesFixture from './fixtures/factcheck-issues.json'
import bgmChoiceFixture from './fixtures/bgm-choice.json'
import bgmTracksFixture from './fixtures/bgm-tracks.json'
import pipelineRunsFixture from './fixtures/pipeline-runs.json'
import publishedVideosFixture from './fixtures/published-videos.json'
import genreStatsFixture from './fixtures/genre-stats.json'
import weeklyReviewsFixture from './fixtures/weekly-reviews.json'
import seriesFixture from './fixtures/series.json'
import seriesHooksFixture from './fixtures/series-hooks.json'
// 复盘 / 数据看板:精心造的"今天体验的人生副本"系列演化数据
import insightsTopBottomFixture from './fixtures/insights-top-bottom.json'
import insightsDimensionWeightsFixture from './fixtures/insights-dimension-weights.json'
import insightsWeeklyReviewFixture from './fixtures/insights-weekly-review.json'
import dailyTrendFixture from './fixtures/analytics-daily-trend.json'

export type FixtureFn = (config: InternalAxiosRequestConfig) => unknown
export type FixtureEntry = unknown | FixtureFn

// 帮 ID 路径里挑出"我们演示的那条" — script_id 实际是 12,但前端可能用各种 id 调用。
// 演示路径上只有这一条 script,我们对所有 :id 都返回同一条数据(简化)。
const DEMO_SCRIPT = scriptFixture
const DEMO_TOPIC = topicFixture

// 选题池:把 brainstorm 候选 + 主 topic 当作 DRAFT 池里的内容。
// 前端 listTopics(DRAFT) 期望 SpringPage<Topic>{ content, totalElements, ... }
const draftTopics = brainstormResultFixture.filter(
  (t: { status?: string }) => t.status !== 'PUBLISHED',
)
const publishedTopics = brainstormResultFixture.filter(
  (t: { status?: string }) => t.status === 'PUBLISHED',
)

function springPage<T>(items: T[], pageSize = 30) {
  return {
    content: items,
    totalElements: items.length,
    totalPages: Math.max(1, Math.ceil(items.length / pageSize)),
    number: 0,
    size: pageSize,
    first: true,
    last: true,
    empty: items.length === 0,
    numberOfElements: items.length,
  }
}

/**
 * 主表
 */
export const fixturesTable: Record<string, FixtureEntry> = {
  // ─── Preset ───────────────────────────────────────────
  'GET /presets': [presetFixture].filter(Boolean),
  'GET /presets/:id': presetFixture,
  'GET /presets/by-name/:id': presetFixture,
  'GET /presets/:id/versions': [],
  'GET /presets/:id/assets': [],

  // ─── Topic ────────────────────────────────────────────
  'GET /topics': (cfg: InternalAxiosRequestConfig) => {
    const status = (cfg.params?.status as string) ?? 'DRAFT'
    const items = status === 'PUBLISHED' ? publishedTopics : draftTopics
    return springPage(items)
  },
  'GET /topics/:id': DEMO_TOPIC,
  'GET /topics/:id/lineage': [
    {
      topicId: DEMO_TOPIC.id,
      title: DEMO_TOPIC.title,
      projectName: DEMO_TOPIC.projectName ?? null,
      latestScriptId: DEMO_SCRIPT.id,
      hookSummary: null,
      fromScriptId: null,
    },
  ],
  // 头脑风暴 —— 返回完整候选,前端会展示这一批并让用户挑。
  'POST /topics/brainstorm': brainstormResultFixture,
  // 同步生成脚本(不太常用,前端默认走 async 路径)
  'POST /topics/:id/scripts/generate': DEMO_SCRIPT,
  // 异步触发 —— 返 runId,前端轮询 GET /runs/{id} 看进度
  'POST /topics/:id/scripts/generate-async': () => ({ runId: 1001 }),
  'PATCH /topics/:id': (cfg: InternalAxiosRequestConfig) => ({ ...DEMO_TOPIC, ...(cfg.data as object) }),
  'DELETE /topics/:id': null,
  'POST /topics/:id/director-note/optimize': () => ({
    note: DEMO_TOPIC.directorNote ?? null,
    explanation: '导演笔记已根据您的反馈重新生成(demo mock)',
  }),

  // ─── Script ───────────────────────────────────────────
  // GET /scripts —— 列表(用于 ScriptList / TopicDetail 顶部最新 script 查询)
  // 形状是 SpringPage<ScriptListItem>:打平的 Script + projectName + 最近 PipelineRun 字段
  'GET /scripts': () => {
    const item = {
      id: scriptFixture.id,
      topicId: (scriptFixture as { topicId?: number | null }).topicId ?? topicFixture.id,
      projectName: topicFixture.projectName ?? topicFixture.title,
      version: (scriptFixture as { version?: number }).version ?? 1,
      modelUsed: (scriptFixture as { modelUsed?: string | null }).modelUsed ?? null,
      wordCount: (scriptFixture as { wordCount?: number | null }).wordCount ?? null,
      durationSeconds: (scriptFixture as { durationSeconds?: number | null }).durationSeconds ?? null,
      status: (scriptFixture as { status?: string }).status ?? 'PUBLISHED',
      reviewScore: (scriptFixture as { reviewScore?: number | null }).reviewScore ?? null,
      createdAt: (scriptFixture as { createdAt?: string }).createdAt ?? new Date(0).toISOString(),
      updatedAt: (scriptFixture as { updatedAt?: string }).updatedAt ?? new Date(0).toISOString(),
      lastRunStage: 'VIDEO',
      lastRunStatus: 'DONE',
      lastRunAt: (scriptFixture as { updatedAt?: string }).updatedAt ?? new Date(0).toISOString(),
    }
    return springPage([item])
  },
  // 注意:GET /scripts/:id 返 ScriptDetailResponse(聚合),不是单个 Script。
  'GET /scripts/:id': {
    script: scriptFixture,
    sections: scriptSectionsFixture,
    presetName: (presetFixture as unknown as { name?: string })?.name ?? null,
    bgmLocked: Boolean((presetFixture as unknown as { bgmLocked?: number | boolean })?.bgmLocked),
  },
  // 真实 endpoint 是 /shots(不是 /storyboard)
  'GET /scripts/:id/shots': storyboardFixture,
  'POST /scripts/:id/storyboard/generate': () => ({ runId: 1002 }),
  'POST /scripts/:id/storyboard/generate-async': () => ({ runId: 1002 }),
  'GET /scripts/:id/images': imagesFixture,
  'POST /scripts/:id/images/generate': () => ({ runId: 1003 }),
  // 返 VoiceAsset[] 列表(不是单条)
  'GET /scripts/:id/voice': voiceFixture,
  'POST /scripts/:id/voice/generate': () => ({ runId: 1004 }),
  // 真实 endpoint 是 /video(单数,不是 /videos)
  'GET /scripts/:id/video': videosFixture,
  'POST /scripts/:id/videos/assemble': () => ({ runId: 1007 }),
  // 真实 endpoint 是 /issues
  'GET /scripts/:id/issues': factcheckIssuesFixture,
  'POST /scripts/:id/factcheck-async': () => ({ runId: 1005 }),
  'POST /scripts/:id/factcheck': factcheckIssuesFixture,
  'GET /scripts/:id/covers': coversFixture,
  'POST /scripts/:id/covers': () => ({ runId: 1006 }),
  'GET /scripts/:id/critic-logs': criticLogsFixture,
  'POST /scripts/:id/align-timing': storyboardFixture,
  // 单镜重生成 —— demo 里直接返回原图(假装"已重新生成")
  'POST /scripts/:id/images/:id/regenerate': (cfg: InternalAxiosRequestConfig) => {
    const shotId = Number(cfg.url?.match(/images\/(\d+)/)?.[1] ?? 0)
    return imagesFixture.find((i: { shotId?: number }) => i.shotId === shotId) ?? imagesFixture[0]
  },

  // ─── BGM ──────────────────────────────────────────────
  'GET /bgm/scripts/:id/recommend': bgmTracksFixture.slice(0, 5),
  'GET /bgm/scripts/:id/tracks': bgmTracksFixture,
  'GET /bgm/scripts/:id/choice': bgmChoiceFixture,
  'POST /bgm/scripts/:id/select': bgmChoiceFixture,

  // ─── Cover ────────────────────────────────────────────
  'POST /covers/:id/finalize': (cfg: InternalAxiosRequestConfig) => {
    const id = Number(cfg.url?.match(/covers\/(\d+)/)?.[1] ?? 0)
    return coversFixture.find((c: { id?: number }) => c.id === id) ?? coversFixture[0]
  },

  // ─── FactCheck ────────────────────────────────────────
  'POST /factcheck-issues/:id/apply': null,

  // ─── Agent ────────────────────────────────────────────
  // demo 站的 chat 暂时只展示空界面 + "demo 模式不支持实时对话"提示。
  'GET /agent/tools': [],
  'GET /agent/sessions': [],
  'POST /agent/sessions': () => ({
    id: 1,
    title: 'Demo 对话',
    createdAt: new Date(0).toISOString(),
    archived: false,
  }),
  'GET /agent/sessions/:id/messages': [],

  // ─── Insights / 数据看板 ──────────────────────────────
  // 精心造的"今天体验的人生副本"系列 12 周演化数据 + 维度权重 + 周复盘
  'GET /insights/top-bottom': insightsTopBottomFixture,
  'GET /insights/dimension-weights': insightsDimensionWeightsFixture,
  'POST /insights/weekly-review': insightsWeeklyReviewFixture,
  'POST /insights/recompute-scores': () => ({ updated: 12 }),
  'POST /insights/video-attribution': () => ({
    verdict: '完播率 71.2% 超出账号基线 19 个百分点,达成「爆款」阈值',
    whatWorked: '反转开场(前 3 秒展示外卖箱里只剩半个馒头)+ 第一人称代入(我以为他是来送餐的)+ 5 分钟时长精准卡进抖音长视频流量池',
    whatFailed: '中段 60-90s 部分用户流失(评论区反映"煽情过度"),需要把情绪曲线压平 0.3 个量级',
    recommendations: '1) 同公式复制到「午夜便利店」「医院走廊」题材;2) 中段把情绪关键词从「悲悯」降为「克制」;3) 封面 CTR 7.8% 偏低,大头特写改为道具特写(外卖箱)',
    fallback: false,
  }),
  'GET /analytics/daily-trend': dailyTrendFixture,
  'GET /analytics/compare': { rows: [] },
  'GET /reviews/weekly': weeklyReviewsFixture,

  // ─── 其他 ─────────────────────────────────────────────
  'GET /config': {},
  'GET /brand-identity': null,
  'GET /published-videos': springPage(publishedVideosFixture),
  'GET /genre-stats': genreStatsFixture,
  'GET /series': seriesFixture,
  'GET /series-hooks': seriesHooksFixture,
  'GET /pipeline-runs': pipelineRunsFixture,

  // 持久化操作 —— demo 里都假装成功,不真改任何东西
  'POST /agent/sessions/:id/messages': () => ({ ok: true }),
  'PATCH /scripts/:id': (cfg: InternalAxiosRequestConfig) => ({ ...DEMO_SCRIPT, ...(cfg.data as object) }),
  'PATCH /scripts/:id/sections/:id': (cfg: InternalAxiosRequestConfig) => cfg.data,
  'PATCH /storyboard-shots/:id': (cfg: InternalAxiosRequestConfig) => cfg.data,
  'POST /presets': (cfg: InternalAxiosRequestConfig) => ({ ...presetFixture, ...(cfg.data as object) }),
  'PATCH /presets/:id': (cfg: InternalAxiosRequestConfig) => cfg.data,
  'POST /presets/:id/save-version': null,
  'POST /presets/:id/rollback': presetFixture,
  'POST /presets/:id/optimize': () => ({ optimized: presetFixture, explanation: 'demo 模式' }),
}

// ─── 长任务模拟 ─────────────────────────────────────────
// 长任务流水线(分镜/生图/配音/视频)前端会轮询 GET /runs/{runId} 看进度。
// 这里给每个 runId 一个内部状态机:第一次问 = RUNNING,过 N 秒后再问 = DONE。

interface RunProgress {
  startedAt: number
  durationMs: number
  scriptId?: number
}

const runs: Record<number, RunProgress> = {}

// 不同阶段的 demo "假等待时长" —— 让用户感觉真在跑,但又不无聊
const RUN_DURATIONS: Record<number, number> = {
  1001: 4000, // generate-script
  1002: 5000, // generate-storyboard
  1003: 8000, // generate-images (最长,52 张图)
  1004: 4000, // generate-voice
  1005: 3000, // factcheck
  1006: 4000, // generate-covers
  1007: 6000, // assemble-video
}

// runId → 流水线阶段(必须返给前端,否则 PipelineRun 字段缺失页面会卡)
const RUN_STAGES: Record<number, string> = {
  1001: 'SCRIPT',
  1002: 'STORYBOARD',
  1003: 'IMAGEGEN',
  1004: 'VOICE',
  1005: 'FACTCHECK',
  1006: 'COVER',
  1007: 'VIDEO',
}

export function getRunStatus(runId: number) {
  if (!runs[runId]) {
    runs[runId] = {
      startedAt: Date.now(),
      durationMs: RUN_DURATIONS[runId] ?? 5000,
      scriptId: scriptFixture.id,
    }
  }
  const r = runs[runId]
  const elapsed = Date.now() - r.startedAt
  const isDone = elapsed >= r.durationMs
  const startedIso = new Date(r.startedAt).toISOString()
  const finishedIso = isDone ? new Date(r.startedAt + r.durationMs).toISOString() : null
  return {
    id: runId,
    topicId: topicFixture.id,
    scriptId: r.scriptId ?? null,
    stage: RUN_STAGES[runId] ?? 'SCRIPT',
    status: isDone ? 'DONE' : 'RUNNING',
    startedAt: startedIso,
    finishedAt: finishedIso,
    errorMsg: null,
    lastCompletedIndex: isDone ? null : Math.floor((elapsed / r.durationMs) * 50),
    totalItems: null,
    pauseRequested: false,
    paramsJson: null,
    triggeredBy: 'demo',
    createdAt: startedIso,
    updatedAt: finishedIso ?? startedIso,
    progress: isDone ? 100 : Math.floor((elapsed / r.durationMs) * 100),
  }
}

/** 重置所有 run 状态(给 DemoBanner 的 "重新演示" 按钮用) */
export function resetRuns(): void {
  for (const k of Object.keys(runs)) delete runs[Number(k)]
}
