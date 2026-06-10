<script setup lang="ts">
/**
 * Markdown 渲染组件,用于 assistant 消息内容。
 *
 * 配置:
 *   html: false        — 不解析原始 HTML,杜绝 XSS
 *   linkify: true      — 裸 URL 自动变链接
 *   breaks: false      — 单行换行不强转 <br>(GFM 默认行为)
 *   typographer: false — 不做引号智能替换,中文场景容易把键名内的 ' 替换错
 *
 * GFM 表格、代码块、列表、加粗 默认开启,无需额外插件。
 *
 * 样式靠 .md-content 类下的 CSS 选择器(写在本文件 style 里)统一,
 * 不引入 prose 插件以减依赖。
 */
import MarkdownIt from 'markdown-it'
import { computed } from 'vue'

const props = defineProps<{ source: string | null | undefined }>()

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: false,
  typographer: false,
})

// 把 <table> 包一层 .md-table-wrap,让列太多时横向滚动只发生在表格内,
// 不会撑大整条 assistant 气泡。
const defaultTableOpen = md.renderer.rules.table_open
  || ((tokens, idx, opts, _env, self) => self.renderToken(tokens, idx, opts))
const defaultTableClose = md.renderer.rules.table_close
  || ((tokens, idx, opts, _env, self) => self.renderToken(tokens, idx, opts))

md.renderer.rules.table_open = (tokens, idx, opts, env, self) =>
  '<div class="md-table-wrap">' + defaultTableOpen(tokens, idx, opts, env, self)
md.renderer.rules.table_close = (tokens, idx, opts, env, self) =>
  defaultTableClose(tokens, idx, opts, env, self) + '</div>'

const html = computed(() => {
  const s = props.source ?? ''
  if (!s) return ''
  return md.render(s)
})
</script>

<template>
  <div class="md-content" v-html="html" />
</template>

<style scoped>
.md-content :deep(h1),
.md-content :deep(h2),
.md-content :deep(h3),
.md-content :deep(h4) {
  font-weight: 600;
  color: rgb(var(--color-text-primary));
  margin: 0.6em 0 0.3em;
  line-height: 1.3;
}
.md-content :deep(h1) { font-size: 1.15em; }
.md-content :deep(h2) { font-size: 1.08em; }
.md-content :deep(h3) { font-size: 1.02em; }
.md-content :deep(p) {
  margin: 0.4em 0;
  line-height: 1.55;
}
.md-content :deep(strong) { font-weight: 600; color: rgb(var(--color-text-primary)); }
.md-content :deep(em) { font-style: italic; }
.md-content :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  background: rgb(var(--color-surface-tertiary));
  padding: 0.1em 0.35em;
  border-radius: 4px;
  font-size: 0.9em;
}
.md-content :deep(pre) {
  background: rgb(var(--color-surface-primary) / 0.6);
  border: 1px solid rgb(var(--color-border-subtle));
  border-radius: 6px;
  padding: 0.6em 0.8em;
  margin: 0.5em 0;
  overflow-x: auto;
  font-size: 0.85em;
}
.md-content :deep(pre code) {
  background: transparent;
  padding: 0;
  border-radius: 0;
}
.md-content :deep(ul),
.md-content :deep(ol) {
  margin: 0.4em 0;
  padding-left: 1.4em;
}
.md-content :deep(li) {
  margin: 0.2em 0;
  line-height: 1.5;
}
.md-content :deep(ul) { list-style: disc; }
.md-content :deep(ol) { list-style: decimal; }
.md-content :deep(blockquote) {
  border-left: 3px solid rgb(var(--color-border-default));
  padding-left: 0.8em;
  margin: 0.5em 0;
  color: rgb(var(--color-text-secondary));
}
.md-content :deep(a) {
  color: rgb(var(--color-accent));
  text-decoration: underline;
}
.md-content :deep(table) {
  border-collapse: collapse;
  margin: 0.6em 0;
  font-size: 0.88em;
  width: 100%;
  table-layout: auto;
}
.md-content :deep(.md-table-wrap) {
  overflow-x: auto;
  margin: 0.6em 0;
  border: 1px solid rgb(var(--color-border-subtle));
  border-radius: 6px;
}
.md-content :deep(.md-table-wrap > table) {
  margin: 0;
  border: 0;
  border-radius: 0;
}
.md-content :deep(th),
.md-content :deep(td) {
  border-bottom: 1px solid rgb(var(--color-border-subtle));
  border-right: 1px solid rgb(var(--color-border-subtle));
  padding: 0.4em 0.7em;
  text-align: left;
  vertical-align: top;
  word-break: break-word;
  overflow-wrap: anywhere;
  line-height: 1.45;
}
.md-content :deep(th:last-child),
.md-content :deep(td:last-child) {
  border-right: 0;
}
.md-content :deep(tr:last-child td) {
  border-bottom: 0;
}
.md-content :deep(thead th) {
  background: rgb(var(--color-surface-tertiary));
  font-weight: 600;
  white-space: nowrap;
}
.md-content :deep(hr) {
  border: 0;
  border-top: 1px solid rgb(var(--color-border-subtle));
  margin: 0.8em 0;
}
.md-content :deep(:first-child) { margin-top: 0; }
.md-content :deep(:last-child) { margin-bottom: 0; }
</style>
