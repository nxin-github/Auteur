<!--
  Demo 模式下顶部黄色提示条
  - 只在 isDemoMode() 为 true 时挂载
  - 点击 "退出 Demo" 清掉持久化标记并刷新
  - 点击 "重新演示" 重置长任务状态(让用户可以多次点 "生成" 看完整流程)
-->

<script setup lang="ts">
import { exitDemoMode } from './demoMode'
import { resetRuns } from './fixtures'

function handleExit() {
  exitDemoMode()
  // 退出后强制刷新,让 main.ts 重新初始化(没有 demo interceptor 的状态)
  window.location.reload()
}

function handleReset() {
  resetRuns()
  // 给个轻量提示
  console.info('[demo] 长任务状态已重置,可以重新点击「生成」演示完整流程')
}
</script>

<template>
  <div class="demo-banner">
    <span class="demo-banner-icon">🎭</span>
    <span class="demo-banner-text">
      <strong>你正在浏览 Demo 演示数据</strong>
      ——
      所有"生成"都是预设好的 mock 响应,真实部署需要
      <a href="https://github.com/nxin-github/Auteur#-推荐路径docker-compose-一键启动"
         target="_blank" rel="noopener noreferrer">本地 docker compose 启动</a>
      并填 LLM key。
    </span>
    <div class="demo-banner-actions">
      <button class="demo-banner-btn" @click="handleReset" title="重置长任务状态,可以重新演示生成流程">
        🔄 重新演示
      </button>
      <button class="demo-banner-btn demo-banner-btn-exit" @click="handleExit">
        退出 Demo
      </button>
    </div>
  </div>
</template>

<style scoped>
.demo-banner {
  background: linear-gradient(90deg, #fef3c7 0%, #fde68a 100%);
  border-bottom: 2px solid #f59e0b;
  color: #78350f;
  padding: 8px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  line-height: 1.5;
  /* 不 sticky:页面里很多工作台都有 sticky top:0 的顶栏,banner 一 sticky 就互相挡。
     用户滚动后 banner 消失也没关系——他第一次进来时已经看到。 */
  position: relative;
  z-index: 50;
}

.demo-banner-icon {
  font-size: 18px;
  flex-shrink: 0;
}

.demo-banner-text {
  flex: 1;
  min-width: 0;
}

.demo-banner-text a {
  color: #92400e;
  text-decoration: underline;
  font-weight: 600;
}

.demo-banner-text a:hover {
  color: #451a03;
}

.demo-banner-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.demo-banner-btn {
  background: rgba(255, 255, 255, 0.6);
  border: 1px solid #f59e0b;
  color: #78350f;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s;
  white-space: nowrap;
}

.demo-banner-btn:hover {
  background: rgba(255, 255, 255, 0.9);
}

.demo-banner-btn-exit {
  background: #f59e0b;
  color: white;
  border-color: #d97706;
}

.demo-banner-btn-exit:hover {
  background: #d97706;
}

/* dark mode 兼容 —— 项目用 .dark / .light 切换主题 */
:global(.dark) .demo-banner {
  background: linear-gradient(90deg, #78350f 0%, #92400e 100%);
  color: #fef3c7;
  border-bottom-color: #f59e0b;
}

:global(.dark) .demo-banner-text a {
  color: #fde68a;
}

:global(.dark) .demo-banner-btn {
  background: rgba(0, 0, 0, 0.3);
  color: #fef3c7;
  border-color: #f59e0b;
}

:global(.dark) .demo-banner-btn:hover {
  background: rgba(0, 0, 0, 0.5);
}

@media (max-width: 640px) {
  .demo-banner {
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
  }

  .demo-banner-actions {
    align-self: stretch;
    justify-content: flex-end;
  }
}
</style>
