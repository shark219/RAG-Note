import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册', public: true }
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/notes',
    children: [
      {
        path: 'notes',
        name: 'NoteList',
        component: () => import('@/views/NoteList.vue'),
        meta: { title: '笔记', icon: 'icon-file' }
      },
      {
        path: 'notes/:id',
        name: 'NoteEditor',
        component: () => import('@/views/NoteEditor.vue'),
        meta: { title: '编辑笔记', hidden: true }
      },
      {
        path: 'chat',
        name: 'AIChat',
        component: () => import('@/views/AIChat.vue'),
        meta: { title: 'AI 助手', icon: 'icon-robot' }
      },
      {
        path: 'chat/:sessionId',
        name: 'AIChatSession',
        component: () => import('@/views/AIChat.vue'),
        meta: { title: 'AI 助手', hidden: true }
      },
      {
        path: 'knowledge',
        name: 'KnowledgeBase',
        component: () => import('@/views/KnowledgeBase.vue'),
        meta: { title: '知识库', icon: 'icon-book' }
      },
      {
        path: 'review',
        name: 'DailyReview',
        component: () => import('@/views/DailyReview.vue'),
        meta: { title: '每日复习', icon: 'icon-calendar' }
      },
      {
        path: 'evaluation',
        redirect: '/system/debug',
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/Settings.vue'),
        meta: { title: '个人设置', hidden: true }
      },
      {
        path: 'system/llm',
        name: 'LLMConfig',
        component: () => import('@/views/system/LLMConfig.vue'),
        meta: { title: 'LLM 配置' }
      },
      {
        path: 'system/prompt',
        name: 'PromptManage',
        component: () => import('@/views/system/PromptManage.vue'),
        meta: { title: '提示词管理' }
      },
      {
        path: 'system/mcp',
        name: 'MCPTools',
        component: () => import('@/views/system/MCPTools.vue'),
        meta: { title: 'MCP 工具' }
      },
      {
        path: 'system/skills',
        name: 'SkillsManage',
        component: () => import('@/views/system/SkillsManage.vue'),
        meta: { title: 'Skills 管理' }
      },
      {
        path: 'system/agent-tasks',
        name: 'AgentTasks',
        component: () => import('@/views/system/AgentTasks.vue'),
        meta: { title: 'Agent 任务' }
      },
      {
        path: 'system/debug',
        name: 'DebugEvaluation',
        component: () => import('@/views/system/DebugEvaluation.vue'),
        meta: { title: '开发者调试' }
      },
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/notes'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, _from, next) => {
  document.title = `${to.meta.title || 'RAG Note'} - AI 智能笔记`

  const token = localStorage.getItem('token')
  const isPublic = to.meta.public

  if (!isPublic && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
