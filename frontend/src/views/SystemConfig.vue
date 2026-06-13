<script setup lang="ts">
/**
 * 系统设置 — UI 编辑第三方密钥、对象存储、中转站等配置,落库到 app_config 表。
 *
 * 设计:
 *  - 按 category 分组(llm / tos / voice / bgm / extension),每组一个 card
 *  - secret 字段:initial value 是 mask(abcd****wxyz),用户点编辑才进入"输入新值"模式
 *  - 一次保存所有改动,只 PUT 用户实际改过的字段(避免把 mask 占位写回 DB)
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Loader2, Save, Settings, Eye, EyeOff, RotateCcw } from 'lucide-vue-next'
import { listAppConfig, updateAppConfig, type AppConfigItem } from '../api/appConfig'
import { extractError } from '../lib/format'
import ErrorBanner from '../components/ErrorBanner.vue'

const router = useRouter()
const items = ref<AppConfigItem[]>([])
const loading = ref(false)
const saving = ref(false)
const errorMsg = ref<string | null>(null)
const successMsg = ref<string | null>(null)

/** 用户在 UI 里输入的新值。key→value;mapping 里有 key 表示"用户改过这个值"。 */
const edits = ref<Record<string, string>>({})

/** secret 字段是否已展开输入(false=显示 mask,true=显示输入框) */
const editingSecret = ref<Record<string, boolean>>({})

const grouped = computed<Array<{ category: string; label: string; items: AppConfigItem[] }>>(() => {
  const order = ['llm', 'tos', 'voice', 'bgm', 'video', 'cover', 'agent', 'tuning', 'extension']
  const labels: Record<string, string> = {
    llm: 'AI 大模型接口',
    tos: '云端文件存储',
    voice: 'AI 语音合成',
    bgm: '背景音乐曲库',
    video: '视频合成参数',
    cover: '封面生成',
    agent: 'AI 助手对话',
    tuning: '业务质检规则',
    extension: '浏览器插件接入',
    custom: '其它',
  }
  const buckets: Record<string, AppConfigItem[]> = {}
  for (const it of items.value) {
    // category='model' 由专门的「AI 模型」页面管理,这里不展示,避免双入口编辑冲突
    if (it.category === 'model') continue
    if (!buckets[it.category]) buckets[it.category] = []
    buckets[it.category].push(it)
  }
  const out: Array<{ category: string; label: string; items: AppConfigItem[] }> = []
  for (const cat of order) {
    if (buckets[cat]?.length) out.push({ category: cat, label: labels[cat] || cat, items: buckets[cat] })
  }
  for (const [cat, list] of Object.entries(buckets)) {
    if (!order.includes(cat)) out.push({ category: cat, label: labels[cat] || cat, items: list })
  }
  return out
})

async function load() {
  loading.value = true
  errorMsg.value = null
  try {
    items.value = await listAppConfig()
    edits.value = {}
    editingSecret.value = {}
  } catch (e: any) {
    errorMsg.value = extractError(e, '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function startEditSecret(key: string) {
  editingSecret.value[key] = true
  edits.value[key] = ''
}

function cancelEditSecret(key: string) {
  delete editingSecret.value[key]
  delete edits.value[key]
}

function clearField(item: AppConfigItem) {
  // 显式清空 → 写空串到 DB,RuntimeConfig 回落 yml 兜底
  edits.value[item.configKey] = ''
  if (item.secret) editingSecret.value[item.configKey] = true
}

function onInput(key: string, value: string) {
  edits.value[key] = value
}

async function save() {
  // 只 PUT 用户实际改过的 key
  const dirty = { ...edits.value }
  if (Object.keys(dirty).length === 0) {
    successMsg.value = '没有改动'
    return
  }
  saving.value = true
  errorMsg.value = null
  successMsg.value = null
  try {
    const r = await updateAppConfig(dirty)
    successMsg.value = `已保存 ${r.updated} 项;部分配置(LLM RestClient / TOS 客户端)需重启后端才能生效。`
    await load()
  } catch (e: any) {
    errorMsg.value = extractError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

function valueOrEdit(item: AppConfigItem): string {
  if (item.configKey in edits.value) return edits.value[item.configKey]
  return item.displayValue
}

function isDirty(key: string): boolean {
  return key in edits.value
}
</script>

<template>
  <div class="min-h-full">
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1100px] mx-auto flex items-center gap-3">
        <button class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
                @click="router.push('/')">
          <ArrowLeft :size="14" /> 返回
        </button>
        <h1 class="text-lg font-semibold flex items-center gap-2">
          <Settings :size="16" class="text-accent" />
          系统设置
        </h1>
        <Loader2 v-if="loading" :size="14" class="animate-spin text-text-muted" />
        <div class="ml-auto flex items-center gap-2">
          <button class="btn" :disabled="saving || loading" @click="load">
            <RotateCcw :size="13" /> 重新加载
          </button>
          <button class="btn-primary" :disabled="saving || loading || Object.keys(edits).length === 0"
                  @click="save">
            <Loader2 v-if="saving" :size="13" class="animate-spin" />
            <Save v-else :size="13" />
            {{ saving ? '保存中…' : `保存${Object.keys(edits).length ? ` (${Object.keys(edits).length})` : ''}` }}
          </button>
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1100px] mx-auto space-y-5">
      <ErrorBanner :msg="errorMsg" />
      <div v-if="successMsg" class="card p-3 text-sm text-status-done bg-status-done/10 border-status-done/30">
        {{ successMsg }}
      </div>

      <div class="card p-4 bg-accent-soft border-accent/30">
        <div class="text-sm text-text-secondary leading-relaxed">
          除数据库地址外的所有第三方配置(LLM 中转、对象存储、语音/TTS、BGM 选曲、插件 token 等)都在这里填写。
          <strong>修改 LLM / TOS 等基础服务后需要重启应用才能生效</strong>;
          其它(语音、BGM、插件 token)立即生效。
        </div>
      </div>

      <div v-for="g in grouped" :key="g.category" class="card p-5 space-y-4">
        <h2 class="text-base font-semibold">{{ g.label }}</h2>
        <div v-for="item in g.items" :key="item.configKey" class="space-y-1.5">
          <label class="text-sm text-text-primary block flex items-center gap-2">
            <span>{{ item.description || item.configKey }}</span>
            <span v-if="item.secret" class="chip text-[9px] bg-status-paused/15 text-status-paused">敏感</span>
            <span v-if="item.hasDbValue" class="chip text-[9px] bg-status-done/15 text-status-done">已填写</span>
            <span v-else class="chip text-[9px] bg-status-failed/15 text-status-failed">未填写</span>
            <span v-if="isDirty(item.configKey)" class="chip text-[9px] bg-amber-500/15 text-amber-700">已改动</span>
          </label>

          <!-- secret 未展开:显示 mask + 编辑按钮 -->
          <div v-if="item.secret && !editingSecret[item.configKey]" class="flex gap-2 items-center">
            <input
              type="text"
              :value="item.displayValue || '(暂未填写)'"
              readonly
              class="form-input font-mono flex-1 text-text-muted"
            />
            <button class="btn text-xs" @click="startEditSecret(item.configKey)">
              <Eye :size="12" /> 编辑
            </button>
          </div>

          <!-- secret 展开 OR 非 secret:显示输入框 -->
          <div v-else class="flex gap-2 items-center">
            <input
              type="text"
              :value="valueOrEdit(item)"
              :placeholder="item.secret ? '输入新值(留空 = 使用默认值)' : ''"
              class="form-input font-mono flex-1"
              @input="onInput(item.configKey, ($event.target as HTMLInputElement).value)"
            />
            <button v-if="item.secret" class="btn-icon" title="收起" @click="cancelEditSecret(item.configKey)">
              <EyeOff :size="12" />
            </button>
            <button v-else-if="isDirty(item.configKey)" class="btn-icon" title="还原" @click="clearField(item)">
              <RotateCcw :size="12" />
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.form-input {
  @apply w-full px-3 py-1.5 text-sm bg-surface-primary border border-border rounded-md
         focus:outline-none focus:border-accent disabled:opacity-50 disabled:cursor-not-allowed;
}
</style>
