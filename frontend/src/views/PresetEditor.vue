<script setup lang="ts">
// Preset 编辑器。10 个 tab —
// 基本信息 / 输入字段 / 选题策划(选题) / 编剧(脚本) / 编剧自审(脚本自审) /
// 摄影指导(分镜) / 美术指导(画面) / 配音演员(配音字幕) / 作曲(BGM) / 制片(合成)。
// 沿用项目 Tailwind 风格,prompt 编辑用原生 textarea
// (font-mono + 大行距,基础够用;Monaco 后期再说,先不引依赖)。
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft, Loader2, Save, GitBranch, Download, History, AlertCircle, ShieldCheck, Sparkles,
} from 'lucide-vue-next'
import ErrorBanner from '../components/ErrorBanner.vue'
import PresetOptimizeDialog from '../components/PresetOptimizeDialog.vue'
import {
  getPreset, createPreset, updatePreset, saveAsNewVersion, listPresetVersions,
  rollbackPreset, type Preset, type PresetVersion,
} from '../api/presets'
import { extractError } from '../lib/format'
import { isAdmin, getOwnerName } from '../lib/admin'

const props = defineProps<{ id: number | null }>()
const router = useRouter()

const isNew = computed(() => props.id == null)
const adminMode = computed(() => isAdmin())

const loading = ref(false)
const saving = ref(false)
const errorMsg = ref<string | null>(null)
const successMsg = ref<string | null>(null)

const tabs = [
  { key: 'basic',     label: '基本信息' },
  { key: 'input',     label: '输入字段' },
  { key: 'brainstorm',label: '选题策划（选题）' },
  { key: 'script',    label: '编剧（脚本）' },
  { key: 'critic',    label: '编剧自审（脚本自审）' },
  { key: 'storyboard',label: '摄影指导（分镜）' },
  { key: 'image',     label: '美术指导（画面）' },
  { key: 'voice',     label: '配音演员（配音字幕）' },
  { key: 'bgm',       label: '作曲（BGM）' },
  { key: 'composition',label: '制片（合成）' },
] as const
type TabKey = typeof tabs[number]['key']
const activeTab = ref<TabKey>('basic')

function emptyPreset(): Preset {
  return {
    id: 0,
    name: '',
    displayName: '',
    description: '',
    visibility: 'private',
    ownerName: getOwnerName() || null,
    inputSchemaJson: null,
    brainstormPromptYaml: '',
    scriptPromptYaml: 'name: my_script\nmodel: claude-opus-4-7\ntemperature: 0.7\n\nsystem: |\n  你是 ...\n\nuser: |\n  请按主题 {{theme}} 生成 ...\n',
    scriptCriticPromptYaml: '',
    scriptCriticThreshold: 80,
    storyboardPromptYaml: 'name: my_storyboard\nmodel: claude-sonnet-4-6\ntemperature: 0.6\n\nsystem: |\n  你是分镜师 ...\n\nuser: |\n  脚本:{{script_full_text}}\n',
    storyboardMode: 'FREE',
    assistantDirectorPromptYaml: '',
    bgmMoodPromptYaml: '',
    imageConfigJson: null,
    voiceConfigJson: null,
    bgmEnabled: false,
    bgmLocked: false,
    compositionId: 'StoryHorizontal',
    formatWidth: 1920,
    formatHeight: 1080,
    watermarkText: null,
    hookSegmentEnabled: false,
    currentVersion: 1,
  }
}

const draft = ref<Preset>(emptyPreset())
const imageConfigText = ref<string>('')
const voiceConfigText = ref<string>('')
const inputSchemaText = ref<string>('')

const versions = ref<PresetVersion[]>([])
const showVersions = ref(false)

async function load() {
  if (isNew.value) {
    draft.value = emptyPreset()
    imageConfigText.value = ''
    voiceConfigText.value = ''
    inputSchemaText.value = ''
    return
  }
  loading.value = true
  try {
    const p = await getPreset(props.id!)
    draft.value = p
    imageConfigText.value = p.imageConfigJson ? JSON.stringify(p.imageConfigJson, null, 2) : ''
    voiceConfigText.value = p.voiceConfigJson ? JSON.stringify(p.voiceConfigJson, null, 2) : ''
    inputSchemaText.value = p.inputSchemaJson ? JSON.stringify(p.inputSchemaJson, null, 2) : ''
    errorMsg.value = null
  } catch (e: any) {
    errorMsg.value = extractError(e, '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.id, load)

async function loadVersions() {
  if (isNew.value) return
  try {
    versions.value = await listPresetVersions(props.id!)
  } catch (e: any) {
    errorMsg.value = extractError(e, '加载版本失败')
  }
}

function buildPayload(): Partial<Preset> {
  const payload: any = { ...draft.value }
  payload.imageConfigJson = imageConfigText.value.trim() ? JSON.parse(imageConfigText.value) : null
  payload.voiceConfigJson = voiceConfigText.value.trim() ? JSON.parse(voiceConfigText.value) : null
  payload.inputSchemaJson = inputSchemaText.value.trim() ? JSON.parse(inputSchemaText.value) : null
  if (isNew.value) {
    delete payload.id
    delete payload.createdAt
    delete payload.updatedAt
    delete payload.currentVersion
  }
  return payload
}

async function onSave() {
  errorMsg.value = null
  successMsg.value = null
  saving.value = true
  try {
    const payload = buildPayload()
    if (isNew.value) {
      const created = await createPreset(payload)
      successMsg.value = `创建成功 (id=${created.id})`
      router.replace(`/presets/${created.id}/edit`)
    } else {
      await updatePreset(props.id!, payload)
      successMsg.value = '已保存(覆盖当前版本,未写 snapshot)'
      await load()
    }
  } catch (e: any) {
    errorMsg.value = extractError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function onSaveAsNewVersion() {
  if (isNew.value) {
    errorMsg.value = '新建状态请先点"保存"创建后再走版本管理'
    return
  }
  const comment = prompt('版本说明(可选,如"调整 hook 句式"):', '')
  if (comment === null) return
  errorMsg.value = null
  successMsg.value = null
  saving.value = true
  try {
    const payload = buildPayload()
    const updated = await saveAsNewVersion(props.id!, payload, comment)
    successMsg.value = `已保存为 v${updated.currentVersion}`
    draft.value = updated
  } catch (e: any) {
    errorMsg.value = extractError(e, '保存版本失败')
  } finally {
    saving.value = false
  }
}

async function onRollback(v: number) {
  if (!confirm(`回滚到 v${v}?当前版本会自动 snapshot,可再次回滚回来。`)) return
  saving.value = true
  try {
    const updated = await rollbackPreset(props.id!, v)
    draft.value = updated
    imageConfigText.value = updated.imageConfigJson ? JSON.stringify(updated.imageConfigJson, null, 2) : ''
    voiceConfigText.value = updated.voiceConfigJson ? JSON.stringify(updated.voiceConfigJson, null, 2) : ''
    inputSchemaText.value = updated.inputSchemaJson ? JSON.stringify(updated.inputSchemaJson, null, 2) : ''
    successMsg.value = `已回滚至 v${v},当前版本号 = v${updated.currentVersion}`
    showVersions.value = false
  } catch (e: any) {
    errorMsg.value = extractError(e, '回滚失败')
  } finally {
    saving.value = false
  }
}

function exportJson() {
  const blob = new Blob([JSON.stringify(draft.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `preset-${draft.value.name || 'untitled'}-v${draft.value.currentVersion}.json`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

async function toggleVersions() {
  showVersions.value = !showVersions.value
  if (showVersions.value) await loadVersions()
}

// ========== AI 沟通优化 ==========
// 与后端 PresetOptimizeService.SECTION_FIELDS 对齐;改这里时记得同步后端。
const SECTION_FIELDS: Record<TabKey, string[]> = {
  basic:       ['displayName', 'description', 'visibility', 'ownerName'],
  input:       ['inputSchemaJson'],
  brainstorm:  ['brainstormPromptYaml'],
  script:      ['scriptPromptYaml'],
  critic:      ['scriptCriticPromptYaml', 'scriptCriticThreshold'],
  storyboard:  ['storyboardPromptYaml', 'storyboardMode', 'assistantDirectorPromptYaml'],
  image:       ['imageConfigJson'],
  voice:       ['voiceConfigJson'],
  bgm:         ['bgmMoodPromptYaml', 'bgmEnabled', 'bgmLocked'],
  composition: ['compositionId', 'formatWidth', 'formatHeight', 'watermarkText', 'hookSegmentEnabled'],
}

const optimizeSection = ref<TabKey | null>(null)

function tabLabel(k: TabKey): string {
  return tabs.find((t) => t.key === k)?.label ?? k
}

const TAB_HINTS: Record<TabKey, string> = {
  basic: '预设元信息(显示名/描述/可见性)',
  input: '创建 topic 时的表单字段(JSON Schema)',
  brainstorm: '选题阶段的 LLM Prompt(YAML)',
  script: '编剧阶段的 LLM Prompt(YAML,必填)',
  critic: '脚本自审 Prompt + 阈值',
  storyboard: '分镜 Prompt(YAML)+ 模式',
  image: '图像生成模型/风格/锁脸配置(JSON)',
  voice: '语音合成 voice/速率/音量(JSON)',
  bgm: 'BGM 选曲开关 + mood 推荐 Prompt',
  composition: 'Remotion composition / 分辨率 / 水印 / hook 段',
}

function tabHint(k: TabKey): string {
  return TAB_HINTS[k] ?? ''
}

/** 收集当前 section 在编辑器里的草稿值,JSON tab 用 textarea 文本反序列化。 */
function collectSectionValues(section: TabKey): Record<string, any> {
  const values: Record<string, any> = {}
  for (const f of SECTION_FIELDS[section]) {
    if (f === 'inputSchemaJson') {
      values[f] = parseJsonOrNull(inputSchemaText.value)
    } else if (f === 'imageConfigJson') {
      values[f] = parseJsonOrNull(imageConfigText.value)
    } else if (f === 'voiceConfigJson') {
      values[f] = parseJsonOrNull(voiceConfigText.value)
    } else {
      values[f] = (draft.value as any)[f]
    }
  }
  return values
}

function parseJsonOrNull(s: string): any {
  if (!s || !s.trim()) return null
  try {
    return JSON.parse(s)
  } catch {
    // 解析不了就把原始文本送过去,让 LLM 看出格式问题
    return s
  }
}

function openOptimize(section: TabKey) {
  if (isNew.value) {
    errorMsg.value = 'AI 优化需要先创建预设。请先点"创建"再使用。'
    return
  }
  optimizeSection.value = section
}

function applyOptimize(fields: Record<string, any>) {
  // 写回 draft + 三个 JSON textarea 的镜像。
  for (const [k, v] of Object.entries(fields)) {
    if (k === 'inputSchemaJson') {
      inputSchemaText.value = v == null ? '' : JSON.stringify(v, null, 2)
    } else if (k === 'imageConfigJson') {
      imageConfigText.value = v == null ? '' : JSON.stringify(v, null, 2)
    } else if (k === 'voiceConfigJson') {
      voiceConfigText.value = v == null ? '' : JSON.stringify(v, null, 2)
    } else {
      ;(draft.value as any)[k] = v
    }
  }
  successMsg.value = 'AI 已写回编辑器,请检查并保存。'
  errorMsg.value = null
}

const optimizeCurrentValues = computed(() =>
  optimizeSection.value ? collectSectionValues(optimizeSection.value) : {},
)
const optimizeSectionLabel = computed(() =>
  optimizeSection.value ? tabLabel(optimizeSection.value) : '',
)
</script>

<template>
  <div class="min-h-full">
    <!-- sticky 顶栏 -->
    <div class="sticky top-0 z-20 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex items-center gap-3 flex-wrap">
        <button
          class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
          @click="$router.push('/presets')"
        >
          <ArrowLeft :size="14" /> 预设库
        </button>
        <h1 class="text-lg font-semibold">
          {{ isNew ? '新建预设' : `编辑:${draft.displayName || draft.name}` }}
        </h1>
        <span v-if="!isNew" class="chip text-[11px] bg-surface-tertiary text-text-muted">
          v{{ draft.currentVersion }}
        </span>
        <span v-if="adminMode" class="chip text-[11px] bg-accent-soft text-accent flex items-center gap-1">
          <ShieldCheck :size="11" /> admin
        </span>
        <div v-if="!adminMode" class="chip text-[11px] bg-status-paused/15 text-status-paused flex items-center gap-1">
          <AlertCircle :size="11" /> 只读 — 启用 admin 才能保存
        </div>
        <div class="ml-auto flex gap-2">
          <button class="btn" @click="exportJson"><Download :size="13" /> 导出</button>
          <button v-if="!isNew" class="btn" @click="toggleVersions"><History :size="13" /> 版本</button>
          <button v-if="adminMode" class="btn" :disabled="saving" @click="onSaveAsNewVersion">
            <GitBranch :size="13" /> 保存为新版本
          </button>
          <button v-if="adminMode" class="btn-primary" :disabled="saving" @click="onSave">
            <Loader2 v-if="saving" :size="13" class="animate-spin" />
            <Save v-else :size="13" />
            {{ saving ? '保存中…' : (isNew ? '创建' : '保存') }}
          </button>
        </div>
      </div>

      <div class="px-8 max-w-[1400px] mx-auto flex items-center gap-1 overflow-x-auto">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="px-3 py-2 text-sm whitespace-nowrap border-b-2 transition-colors"
          :class="activeTab === t.key
            ? 'border-accent text-accent'
            : 'border-transparent text-text-muted hover:text-text-primary'"
          @click="activeTab = t.key"
        >{{ t.label }}</button>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />
      <div v-if="successMsg" class="card p-3 mb-4 text-sm text-status-done bg-status-done/10 border-status-done/30">
        {{ successMsg }}
      </div>

      <div v-if="loading" class="card p-3 mb-4 flex items-center gap-2 text-xs text-text-muted">
        <Loader2 :size="14" class="animate-spin" /> 加载中…
      </div>

      <!-- 当前 tab 的标题栏 + AI 优化按钮(显眼位置) -->
      <div v-if="!loading" class="flex items-center gap-3 mb-3">
        <h2 class="text-base font-semibold text-text-primary">
          {{ tabLabel(activeTab) }}
        </h2>
        <span class="text-xs text-text-muted">
          {{ tabHint(activeTab) }}
        </span>
        <button
          v-if="adminMode && !isNew"
          class="ml-auto inline-flex items-center gap-1.5 px-3.5 py-2 rounded-md text-sm font-medium
                 bg-accent text-white hover:bg-accent-hover shadow-sm hover:shadow transition-all
                 ring-1 ring-accent/40"
          :title="`让 LLM 根据你的反馈重新生成【${tabLabel(activeTab)}】配置`"
          @click="openOptimize(activeTab)"
        >
          <Sparkles :size="15" />
          <span>AI 优化此节</span>
        </button>
        <span
          v-else-if="isNew"
          class="ml-auto text-xs text-text-muted italic"
        >
          先创建预设后可使用 AI 优化
        </span>
      </div>

      <!-- ========== 基本信息 ========== -->
      <div v-show="activeTab === 'basic'" class="card p-5 space-y-5">
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">内部标识 (name)</label>
          <div class="text-xs text-text-muted">全局唯一,只允许字母/数字/下划线;routing 用,创建后建议不改</div>
          <input v-model="draft.name" :disabled="!adminMode" class="form-input font-mono" placeholder="lifecopy / freeform" />
        </div>
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">显示名称</label>
          <div class="text-xs text-text-muted">UI 显示名,可中文</div>
          <input v-model="draft.displayName" :disabled="!adminMode" class="form-input" placeholder="自由创作 / 我的预设" />
        </div>
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">描述</label>
          <div class="text-xs text-text-muted">一句话说明这种视频形态适合什么</div>
          <textarea v-model="draft.description" :disabled="!adminMode" class="form-textarea" rows="2" />
        </div>
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">可见性</label>
          <div class="text-xs text-text-muted">私有 = 仅 owner 看见(localStorage admin 模式下可见)</div>
          <div class="flex gap-3 text-sm">
            <label class="flex items-center gap-1.5"><input type="radio" v-model="draft.visibility" :disabled="!adminMode" value="private" /> 私有</label>
            <label class="flex items-center gap-1.5"><input type="radio" v-model="draft.visibility" :disabled="!adminMode" value="public" /> 公开</label>
          </div>
        </div>
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">所有者</label>
          <div class="text-xs text-text-muted">软标记,无鉴权</div>
          <input v-model="draft.ownerName" :disabled="!adminMode" class="form-input" placeholder="我" />
        </div>
      </div>

      <!-- ========== 输入字段 ========== -->
      <div v-show="activeTab === 'input'" class="card p-5 space-y-3">
        <div class="text-sm text-text-secondary">
          JSON Schema 描述创建 topic 时的表单字段。前端 DynamicForm 据此渲染。空 = 不填字段(适合极简 freeform)。
        </div>
        <textarea
          v-model="inputSchemaText"
          :disabled="!adminMode"
          class="form-textarea font-mono text-xs"
          rows="22"
          placeholder='{"type":"object","required":["theme"],"properties":{"theme":{"type":"string","title":"主题"}}}'
        />
      </div>

      <!-- ========== 选题 ========== -->
      <div v-show="activeTab === 'brainstorm'" class="card p-5 space-y-3">
        <div class="text-sm text-text-secondary">
          选题 prompt yaml(空 = 该预设不支持头脑风暴)。yaml 必含 system / user 字段。
        </div>
        <textarea v-model="draft.brainstormPromptYaml" :disabled="!adminMode" class="form-textarea font-mono text-xs" rows="22" />
      </div>

      <!-- ========== 编剧 ========== -->
      <div v-show="activeTab === 'script'" class="card p-5 space-y-3">
        <div class="text-sm text-text-secondary">
          编剧 prompt yaml(必填)。可用 <code v-pre>{{key}}</code> 引用 input_schema 字段。
        </div>
        <textarea v-model="draft.scriptPromptYaml" :disabled="!adminMode" class="form-textarea font-mono text-xs" rows="26" />
      </div>

      <!-- ========== 自审 ========== -->
      <div v-show="activeTab === 'critic'" class="card p-5 space-y-3">
        <div class="text-sm text-text-secondary">
          脚本自审 prompt(空 = 不跑 critic)。LLM 给的分数低于 threshold 时触发"带反馈重投"。
        </div>
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">阈值 (threshold)</label>
          <div class="text-xs text-text-muted">0-100,典型 80;低于此分数触发 REWRITE</div>
          <input v-model.number="draft.scriptCriticThreshold" :disabled="!adminMode" type="number" class="form-input w-24" min="0" max="100" />
        </div>
        <textarea v-model="draft.scriptCriticPromptYaml" :disabled="!adminMode" class="form-textarea font-mono text-xs" rows="22" />
      </div>

      <!-- ========== 摄影 ========== -->
      <div v-show="activeTab === 'storyboard'" class="card p-5 space-y-3">
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">分镜模式 (storyboard_mode)</label>
          <div class="text-xs text-text-muted">PRECISE_BY_CUE 强制 anchor_cue_indices 严格连续覆盖;FREE 只校验 shot 数量合理</div>
          <div class="flex gap-3 text-sm">
            <label class="flex items-center gap-1.5"><input type="radio" v-model="draft.storyboardMode" :disabled="!adminMode" value="FREE" /> FREE(自由)</label>
            <label class="flex items-center gap-1.5"><input type="radio" v-model="draft.storyboardMode" :disabled="!adminMode" value="PRECISE_BY_CUE" /> PRECISE_BY_CUE(严格按 cue)</label>
          </div>
        </div>
        <div class="text-sm text-text-secondary">摄影 prompt yaml(必填)。</div>
        <textarea v-model="draft.storyboardPromptYaml" :disabled="!adminMode" class="form-textarea font-mono text-xs" rows="22" />
      </div>

      <!-- ========== 美术 ========== -->
      <div v-show="activeTab === 'image'" class="card p-5 space-y-3">
        <div class="text-sm text-text-secondary">
          美术配置 JSON。字段:
          <code class="text-xs">model / identity_lock_text / reference_image_path / style_suffix / style_tag / negative_prompt / image_size</code>。
          所有字段可空(空 = 用 ImageGenService 内置默认)。
        </div>
        <textarea
          v-model="imageConfigText"
          :disabled="!adminMode"
          class="form-textarea font-mono text-xs"
          rows="18"
          placeholder='{"model":"Doubao-Seedream-5.0-lite","identity_lock_text":"...","style_suffix":"...","negative_prompt":"...","image_size":"2560x1440"}'
        />
      </div>

      <!-- ========== 录音 ========== -->
      <div v-show="activeTab === 'voice'" class="card p-5 space-y-3">
        <div class="text-sm text-text-secondary">
          录音配置 JSON。字段:<code class="text-xs">voice_id / speed_ratio / volume_ratio</code>。
          整体留空 = 该预设不要旁白(纯 BGM 风景片)。
        </div>
        <textarea
          v-model="voiceConfigText"
          :disabled="!adminMode"
          class="form-textarea font-mono text-xs"
          rows="12"
          placeholder='{"voice_id":"BV001_streaming","speed_ratio":1.0,"volume_ratio":1.0}'
        />
      </div>

      <!-- ========== BGM ========== -->
      <div v-show="activeTab === 'bgm'" class="card p-5 space-y-3">
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">启用 BGM (bgm_enabled)</label>
          <div class="text-xs text-text-muted">启用 BGM 选曲(Jamendo)</div>
          <input type="checkbox" v-model="draft.bgmEnabled" :disabled="!adminMode" />
        </div>
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">锁定 BGM (bgm_locked)</label>
          <div class="text-xs text-text-muted">勾上后:BgmPicker 隐藏选曲 UI,后端按 preset 兜底逻辑出曲。仅 bgm_enabled=true 时生效。</div>
          <input type="checkbox" v-model="draft.bgmLocked" :disabled="!adminMode || !draft.bgmEnabled" />
        </div>
        <div class="text-sm text-text-secondary">BGM mood 推荐 prompt yaml(空 = 用默认 BGM 推荐逻辑)。</div>
        <textarea v-model="draft.bgmMoodPromptYaml" :disabled="!adminMode" class="form-textarea font-mono text-xs" rows="14" />
      </div>

      <!-- ========== 合成 ========== -->
      <div v-show="activeTab === 'composition'" class="card p-5 space-y-5">
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">合成组件 (composition_id)</label>
          <div class="text-xs text-text-muted">Remotion composition 名,如 StoryHorizontal / StoryVertical / LifeCopy</div>
          <input v-model="draft.compositionId" :disabled="!adminMode" class="form-input font-mono" />
        </div>
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">成片分辨率 (宽 × 高)</label>
          <div class="text-xs text-text-muted">成片分辨率;横屏 1920×1080,竖屏 1080×1920</div>
          <div class="flex gap-2 items-center text-sm">
            <input v-model.number="draft.formatWidth" :disabled="!adminMode" type="number" class="form-input w-28" />
            <span class="text-text-muted">×</span>
            <input v-model.number="draft.formatHeight" :disabled="!adminMode" type="number" class="form-input w-28" />
          </div>
        </div>
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">水印文本 (watermark_text)</label>
          <div class="text-xs text-text-muted">左上角水印文本;空 = 不加</div>
          <input v-model="draft.watermarkText" :disabled="!adminMode" class="form-input" placeholder="虚构演绎 仅供娱乐" />
        </div>
        <div class="space-y-1.5">
          <label class="text-sm text-text-primary block">开场 hook 段 (hook_segment_enabled)</label>
          <div class="text-xs text-text-muted">开场 4s hook 段(快切 + 钩子旁白)</div>
          <input type="checkbox" v-model="draft.hookSegmentEnabled" :disabled="!adminMode" />
        </div>
      </div>
    </div>

    <!-- 版本侧栏 -->
    <div
      v-if="showVersions"
      class="fixed inset-y-0 right-0 w-96 bg-surface-secondary border-l border-border-subtle shadow-xl z-30 overflow-y-auto"
    >
      <div class="sticky top-0 bg-surface-secondary border-b border-border-subtle px-4 py-3 flex items-center gap-2">
        <History :size="14" />
        <span class="font-medium">历史版本</span>
        <button class="btn-icon ml-auto" @click="showVersions = false">×</button>
      </div>
      <div v-if="versions.length === 0" class="p-4 text-sm text-text-muted">暂无历史版本</div>
      <div v-for="v in versions" :key="v.id" class="border-b border-border-subtle px-4 py-3">
        <div class="flex items-center gap-2">
          <span class="font-mono text-sm">v{{ v.version }}</span>
          <span class="text-xs text-text-muted ml-auto">{{ v.createdAt }}</span>
        </div>
        <div v-if="v.comment" class="text-xs text-text-secondary mt-1">{{ v.comment }}</div>
        <button v-if="adminMode" class="btn mt-2 text-xs" @click="onRollback(v.version)">回滚到此版</button>
      </div>
    </div>

    <!-- AI 沟通优化对话框 -->
    <PresetOptimizeDialog
      :preset-id="props.id"
      :section="optimizeSection"
      :section-label="optimizeSectionLabel"
      :current-values="optimizeCurrentValues"
      @close="optimizeSection = null"
      @applied="applyOptimize"
    />
  </div>
</template>

<style scoped>
.form-input {
  @apply w-full px-3 py-1.5 text-sm bg-surface-primary border border-border rounded-md
         focus:outline-none focus:border-accent disabled:opacity-50 disabled:cursor-not-allowed;
}
.form-textarea {
  @apply w-full px-3 py-2 text-sm bg-surface-primary border border-border rounded-md
         focus:outline-none focus:border-accent disabled:opacity-50 disabled:cursor-not-allowed
         resize-y;
  line-height: 1.6;
}
</style>
