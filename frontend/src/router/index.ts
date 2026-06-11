import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  // base 路径与 vite.config.ts 的 base 配置同步:
  // - 本地 dev / Vercel / Docker 都是 '/'
  // - GitHub Pages 是 '/Auteur/'(由 build 时的 VITE_BASE_PATH 传递,
  //   Vite 会自动塞进 import.meta.env.BASE_URL)
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/home' },

    // 工作台首页
    {
      path: '/home',
      name: 'home',
      component: () => import('../views/Home.vue'),
      meta: { title: '工作台' },
    },

    // Agent 控制台:对话式管理预设/系统配置(并存,Home 不变)
    {
      path: '/chat',
      name: 'chat',
      component: () => import('../views/Chat.vue'),
      meta: { title: 'Agent 控制台' },
    },

    // 选题
    {
      path: '/topics',
      name: 'topics',
      component: () => import('../views/TopicList.vue'),
      meta: { title: '选题池' },
    },
    {
      path: '/topics/:id',
      name: 'topic-detail',
      component: () => import('../views/TopicDetail.vue'),
      meta: { title: '选题详情' },
      props: (route) => ({ id: Number(route.params.id) }),
    },

    // 脚本
    {
      path: '/scripts',
      name: 'scripts',
      component: () => import('../views/ScriptList.vue'),
      meta: { title: '脚本工作台' },
    },
    {
      path: '/scripts/:id',
      name: 'script-detail',
      component: () => import('../views/ScriptDetail.vue'),
      meta: { title: '脚本详情' },
      props: (route) => ({ id: Number(route.params.id) }),
    },
    {
      path: '/cover',
      name: 'cover',
      component: () => import('../views/CoverList.vue'),
      meta: { title: '封面生成' },
    },
    {
      path: '/scripts/:scriptId/cover',
      name: 'cover-designer',
      component: () => import('../views/CoverDesigner.vue'),
      meta: { title: '封面设计器' },
      props: (route) => ({ scriptId: Number(route.params.scriptId) }),
    },

    // 事实核查（按 scriptId 查；列表先靠最近脚本聚合）
    {
      path: '/factcheck',
      name: 'factcheck',
      component: () => import('../views/FactCheckList.vue'),
      meta: { title: '事实核查' },
    },
    {
      path: '/factcheck/:scriptId',
      name: 'factcheck-detail',
      component: () => import('../views/FactCheckDetail.vue'),
      meta: { title: '事实核查详情' },
      props: (route) => ({ scriptId: Number(route.params.scriptId) }),
    },

    // 分镜
    {
      path: '/storyboard',
      name: 'storyboard',
      component: () => import('../views/StoryboardList.vue'),
      meta: { title: '分镜工作台' },
    },
    {
      path: '/storyboard/:scriptId',
      name: 'storyboard-detail',
      component: () => import('../views/StoryboardDetail.vue'),
      meta: { title: '分镜详情' },
      props: (route) => ({ scriptId: Number(route.params.scriptId) }),
    },
    // 生图
    {
      path: '/images',
      name: 'images',
      component: () => import('../views/ImageList.vue'),
      meta: { title: '生图工作台' },
    },
    {
      path: '/images/:scriptId',
      name: 'image-detail',
      component: () => import('../views/ImageDetail.vue'),
      meta: { title: '生图详情' },
      props: (route) => ({ scriptId: Number(route.params.scriptId) }),
    },

    // 配音字幕
    {
      path: '/voice',
      name: 'voice',
      component: () => import('../views/VoiceStudio.vue'),
      meta: { title: '配音字幕工作台' },
    },

    // 复盘组
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('../views/Dashboard.vue'),
      meta: { title: '数据看板' },
    },
    {
      path: '/review/weekly',
      name: 'review-weekly',
      component: () => import('../views/ReviewWeekly.vue'),
      meta: { title: '周复盘' },
    },
    {
      path: '/review/compare',
      name: 'review-compare',
      component: () => import('../views/ReviewCompare.vue'),
      meta: { title: '跨视频对比' },
    },
    {
      path: '/review/:videoId',
      name: 'review-video',
      component: () => import('../views/ReviewVideo.vue'),
      meta: { title: '单条视频复盘' },
      props: (route) => ({ videoId: Number(route.params.videoId) }),
    },
    {
      path: '/insights',
      name: 'insights',
      component: () => import('../views/Insights.vue'),
      meta: { title: '数据洞察 · 反向选题' },
    },
    {
      path: '/published-videos',
      name: 'published-videos',
      component: () => import('../views/PublishedVideoAdmin.vue'),
      meta: { title: '已发布视频' },
    },
    {
      path: '/genre-stats',
      name: 'genre-stats',
      component: () => import('../views/GenreStatsAdmin.vue'),
      meta: { title: '体裁基准 · 投稿作品聚合' },
    },

    // 视频组装
    {
      path: '/assembly',
      name: 'assembly',
      component: () => import('../views/VideoAssemblyList.vue'),
      meta: { title: '视频组装' },
    },
    {
      path: '/assembly/:scriptId',
      name: 'assembly-detail',
      component: () => import('../views/VideoAssemblyPreview.vue'),
      meta: { title: '视频组装预览' },
      props: (route) => ({ scriptId: Number(route.params.scriptId) }),
    },
    {
      path: '/series',
      name: 'series',
      component: () => import('../views/SeriesView.vue'),
      meta: { title: '系列管理' },
    },
    {
      path: '/series/:id',
      name: 'series-detail',
      component: () => import('../views/SeriesDetail.vue'),
      meta: { title: '系列详情' },
      props: (route) => ({ id: Number(route.params.id) }),
    },

    // Preset 管理(admin 模式 + 公开预设浏览)
    {
      path: '/presets',
      name: 'preset-library',
      component: () => import('../views/PresetLibrary.vue'),
      meta: { title: '预设库' },
    },
    // 系统设置(LLM 中转 / 对象存储 / 语音 / BGM 等密钥)
    {
      path: '/config',
      name: 'system-config',
      component: () => import('../views/SystemConfig.vue'),
      meta: { title: '系统设置' },
    },
    {
      path: '/presets/:id/edit',
      name: 'preset-editor',
      component: () => import('../views/PresetEditor.vue'),
      meta: { title: '编辑预设' },
      props: (route) => ({ id: Number(route.params.id) }),
    },
    {
      path: '/presets/new',
      name: 'preset-new',
      component: () => import('../views/PresetEditor.vue'),
      meta: { title: '新建预设' },
      props: () => ({ id: null }),
    },
    // admin 模式开关入口 — 访问 /admin?token=xxx 写 localStorage,/admin?logout=1 清除
    {
      path: '/admin',
      name: 'admin-toggle',
      component: () => import('../views/AdminToggle.vue'),
      meta: { title: 'Admin 模式' },
    },

    // 兜底 404 → 回首页
    {
      path: '/:pathMatch(.*)*',
      redirect: '/home',
    },
  ],
})

export default router
