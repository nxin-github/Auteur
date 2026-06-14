<script setup lang="ts">
// DirectorNoteOptimizeDialog —— DirectorNoteDrawer 上的 "AI 智能填充" 子弹窗。
// 两阶段:用户输入诉求 → LLM 返回完整 DirectorNote → 预览 → 应用回 form。
// 不直接落库;applied 后父组件仍需点保存。
//
// 镜像 PresetOptimizeDialog 的骨架,主要区别:
//   - 没有 section 概念(导演笔记是整体重写)
//   - 预览阶段按 DirectorNote 的语义分组渲染,而不是泛型 fields map
import { computed, ref, watch } from 'vue'
import { Loader2, Sparkles, X, Check, RotateCcw } from 'lucide-vue-next'
import { optimizeDirectorNote } from '../api/topics'
import { extractError } from '../lib/format'
import type { DirectorNote } from '../types'

const props = defineProps<{
  topicId: number | null
  open: boolean
  /** 当前抽屉里的 form,可能含未保存改动 */
  currentValues: DirectorNote
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'applied', note: DirectorNote): void
}>()

const userFeedback = ref('')
const busy = ref(false)
const error = ref<string | null>(null)
const result = ref<{ note: DirectorNote; explanation: string | null } | null>(null)

watch(
  () => props.open,
  (v) => {
    if (v) {
      userFeedback.value = ''
      error.value = null
      result.value = null
    }
  },
)

const canSubmit = computed(() => !busy.value)

async function submit() {
  if (!props.topicId || !canSubmit.value) return
  busy.value = true
  error.value = null
  try {
    const resp = await optimizeDirectorNote(props.topicId, {
      userFeedback: userFeedback.value.trim(),
      currentValues: props.currentValues,
    })
    result.value = { note: resp.note, explanation: resp.explanation }
  } catch (e: any) {
    error.value = extractError(e, '优化失败,LLM 可能超时,稍后再试')
  } finally {
    busy.value = false
  }
}

function apply() {
  if (!result.value) return
  emit('applied', result.value.note)
  emit('close')
}

function regenerate() {
  result.value = null
  error.value = null
}

function close() {
  if (busy.value) return
  emit('close')
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-[60] bg-black/60 flex items-center justify-center p-4"
    @click.self="close"
  >
    <div class="bg-white rounded-lg shadow-2xl max-w-[720px] w-full max-h-[90vh] overflow-y-auto p-6">
      <header class="flex items-center justify-between mb-1">
        <h2 class="text-lg font-semibold flex items-center gap-2">
          <Sparkles :size="18" class="text-amber-500" />
          AI 智能填充【导演笔记】
        </h2>
        <button
          class="text-gray-400 hover:text-gray-700 disabled:opacity-50"
          :disabled="busy"
          @click="close"
        >
          <X :size="20" />
        </button>
      </header>
      <p class="text-xs text-gray-500 mb-4">
        描述你希望这份导演笔记体现什么风格 / 重点,LLM 会综合你已填的内容重写整份。
        生成结果不会直接落库,你可以预览后选择"应用到编辑器"再决定是否保存。
      </p>

      <div
        v-if="error"
        class="rounded-md p-3 mb-3 bg-red-50 border border-red-200 text-xs text-red-700"
      >
        {{ error }}
      </div>

      <!-- 阶段一:输入诉求 -->
      <template v-if="!result">
        <label class="flex flex-col gap-1">
          <span class="text-xs text-gray-500">你希望怎么改?(可选,留空 = 让模型按 Topic 上下文自行判断)</span>
          <textarea
            v-model="userFeedback"
            rows="6"
            :disabled="busy"
            placeholder="例:整体偏沉痛与留白;keyMoments 加一段反转;tone 别再写&quot;压抑&quot;,改用孤独少年皇帝。&#10;&#10;留空也可以提交 — 模型会按 Topic 题材 + 当前草稿自行判断,适合首次生成或没有明确想法的场景。"
            class="w-full px-3 py-2 border rounded text-sm font-sans resize-y focus:outline-none focus:ring-1 focus:ring-blue-400"
          />
        </label>

        <div class="flex items-center gap-2 mt-5">
          <button
            class="px-4 py-2 border rounded text-sm hover:bg-gray-50 disabled:opacity-50"
            :disabled="busy"
            @click="close"
          >
            取消
          </button>
          <button
            class="ml-auto px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white rounded text-sm flex items-center gap-1.5"
            :disabled="!canSubmit"
            @click="submit"
          >
            <Loader2 v-if="busy" :size="14" class="animate-spin" />
            <Sparkles v-else :size="14" />
            {{ busy ? 'LLM 生成中…(约 20-40s)' : '生成方案' }}
          </button>
        </div>
      </template>

      <!-- 阶段二:预览方案 -->
      <template v-else>
        <div class="rounded-md p-3 mb-3 bg-amber-50 border border-amber-200 text-sm">
          <div class="text-xs text-amber-700 mb-1 flex items-center gap-1.5">
            <Sparkles :size="11" /> AI 改动说明
          </div>
          <div class="text-gray-800 leading-relaxed">
            {{ result.explanation || '(LLM 未给出说明)' }}
          </div>
        </div>

        <div class="text-xs text-gray-500 mb-3">应用后会覆盖编辑器里所有字段,你仍可手动微调再保存。</div>

        <div class="space-y-3 mb-4">
          <!-- tone / pacing -->
          <div class="grid grid-cols-2 gap-3">
            <div class="rounded p-2 bg-gray-50">
              <div class="text-[10px] font-mono text-gray-500 mb-1">tone</div>
              <div class="text-sm">{{ result.note.tone }}</div>
            </div>
            <div class="rounded p-2 bg-gray-50">
              <div class="text-[10px] font-mono text-gray-500 mb-1">pacing</div>
              <div class="text-sm">{{ result.note.pacing }}</div>
            </div>
          </div>

          <!-- narrativeArc -->
          <div class="rounded p-3 bg-gray-50">
            <div class="text-[10px] font-mono text-gray-500 mb-2">narrativeArc(五段节奏)</div>
            <div class="space-y-1.5">
              <div v-for="arc in result.note.narrativeArc" :key="arc.section" class="text-xs leading-relaxed">
                <span class="font-semibold text-gray-700">{{ arc.section }}</span>
                <span class="ml-2 text-gray-800">{{ arc.guidance }}</span>
              </div>
            </div>
          </div>

          <!-- visualStyle -->
          <div class="rounded p-3 bg-gray-50">
            <div class="text-[10px] font-mono text-gray-500 mb-2">visualStyle</div>
            <div class="text-xs space-y-1">
              <div><span class="text-gray-500">palette:</span> {{ result.note.visualStyle.palette || '—' }}</div>
              <div><span class="text-gray-500">depthOfField:</span> {{ result.note.visualStyle.depthOfField || '—' }}</div>
              <div><span class="text-gray-500">lighting:</span> {{ result.note.visualStyle.lighting || '—' }}</div>
              <div v-if="result.note.visualStyle.avoidWords?.length">
                <span class="text-gray-500">avoidWords:</span>
                <span
                  v-for="(w, i) in result.note.visualStyle.avoidWords"
                  :key="i"
                  class="inline-block ml-1 px-1.5 py-0.5 bg-red-100 text-red-700 rounded text-[10px]"
                >{{ w }}</span>
              </div>
            </div>
          </div>

          <!-- protagonistVibe -->
          <div class="rounded p-3 bg-gray-50">
            <div class="text-[10px] font-mono text-gray-500 mb-2">protagonistVibe</div>
            <div class="text-xs space-y-1">
              <div><span class="text-gray-500">appearance:</span> {{ result.note.protagonistVibe.appearance || '—' }}</div>
              <div><span class="text-gray-500">voiceVibe:</span> {{ result.note.protagonistVibe.voiceVibe || '—' }}</div>
              <div><span class="text-gray-500">speakingPace:</span> {{ result.note.protagonistVibe.speakingPace || '—' }}</div>
            </div>
          </div>

          <!-- keyMoments -->
          <div class="rounded p-3 bg-gray-50" v-if="result.note.keyMoments?.length">
            <div class="text-[10px] font-mono text-gray-500 mb-2">keyMoments({{ result.note.keyMoments.length }})</div>
            <div class="space-y-1">
              <div v-for="(m, i) in result.note.keyMoments" :key="i" class="text-xs">
                <span class="font-mono text-gray-600">{{ m.time }}</span>
                <span class="ml-2 text-gray-800">{{ m.what }}</span>
              </div>
            </div>
          </div>

          <!-- highlightThemes -->
          <div class="rounded p-3 bg-gray-50" v-if="result.note.highlightThemes?.length">
            <div class="text-[10px] font-mono text-gray-500 mb-2">highlightThemes</div>
            <div class="flex flex-wrap gap-1.5">
              <span
                v-for="(t, i) in result.note.highlightThemes"
                :key="i"
                class="px-2 py-0.5 bg-amber-100 text-amber-800 rounded text-xs"
              >{{ t }}</span>
            </div>
          </div>

          <!-- directorNotes -->
          <div class="rounded p-3 bg-gray-50" v-if="result.note.directorNotes">
            <div class="text-[10px] font-mono text-gray-500 mb-2">directorNotes</div>
            <pre class="text-xs text-gray-800 whitespace-pre-wrap font-sans leading-relaxed">{{ result.note.directorNotes }}</pre>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <button
            class="px-4 py-2 border rounded text-sm hover:bg-gray-50"
            :disabled="busy"
            @click="close"
          >
            放弃
          </button>
          <button
            class="px-4 py-2 border rounded text-sm hover:bg-gray-50 flex items-center gap-1.5"
            :disabled="busy"
            @click="regenerate"
          >
            <RotateCcw :size="13" /> 换个说法再来
          </button>
          <button
            class="ml-auto px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded text-sm flex items-center gap-1.5"
            @click="apply"
          >
            <Check :size="14" /> 应用到编辑器
          </button>
        </div>
      </template>
    </div>
  </div>
</template>
