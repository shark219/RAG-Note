<template>
  <a-layout class="layout">
    <a-layout-sider
      :collapsed="appStore.collapsed"
      :width="170"
      :collapsed-width="50"
      breakpoint="lg"
      collapsible
      :hide-trigger="true"
      @collapse="appStore.toggleCollapsed"
      class="sider"
      @mouseenter="handleMouseEnter"
      @mouseleave="handleMouseLeave"
    >
      <div class="logo">
        <icon-book />
        <span v-show="!appStore.collapsed">RAG Note</span>
      </div>
      <a-menu
        :selected-keys="selectedKeys"
        :open-keys="openKeys"
        @menu-item-click="handleMenuClick"
        @sub-menu-click="handleSubMenuClick"
        :style="{ width: '100%' }"
      >
        <a-menu-item key="/notes">
          <template #icon><icon-file /></template>
          笔记
        </a-menu-item>
        <a-menu-item key="/chat">
          <template #icon><icon-robot /></template>
          AI 助手
        </a-menu-item>
        <a-menu-item key="/knowledge">
          <template #icon><icon-book /></template>
          知识库
        </a-menu-item>
        <a-menu-item key="/review">
          <template #icon><icon-calendar /></template>
          每日复习
        </a-menu-item>
        <a-menu-item key="/evaluation">
          <template #icon><icon-bar-chart /></template>
          评估
        </a-menu-item>
        <a-sub-menu key="system">
          <template #icon><icon-settings /></template>
          <template #title>系统配置</template>
          <a-menu-item key="/system/llm">
            <template #icon><icon-common /></template>
            LLM 配置
          </a-menu-item>
          <a-menu-item key="/system/prompt">
            <template #icon><icon-file /></template>
            提示词管理
          </a-menu-item>
          <a-menu-item key="/system/mcp">
            <template #icon><icon-command /></template>
            MCP 工具
          </a-menu-item>
          <a-menu-item key="/system/skills">
            <template #icon><icon-trophy /></template>
            Skills 管理
          </a-menu-item>
        </a-sub-menu>
      </a-menu>
    </a-layout-sider>
    <a-layout>
      <a-layout-header class="header">
        <div class="header-left">
          <a-breadcrumb>
            <a-breadcrumb-item>{{ currentTitle }}</a-breadcrumb-item>
          </a-breadcrumb>
        </div>
        <div class="header-right">
          <a-button type="text" @click="appStore.toggleTheme">
            <icon-moon-fill v-if="appStore.theme === 'light'" />
            <icon-sun-fill v-else />
          </a-button>
          <a-dropdown>
            <a-button type="text">
              <icon-user />
              <span style="margin-left: 8px">{{ userStore.userInfo?.username || '用户' }}</span>
            </a-button>
            <template #content>
              <a-doption @click="$router.push('/settings')">设置</a-doption>
              <a-doption @click="handleLogout">退出登录</a-doption>
            </template>
          </a-dropdown>
        </div>
      </a-layout-header>
      <a-layout-content class="content">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAppStore } from '@/store/app'
import { useUserStore } from '@/store/user'
import {
  IconBook,
  IconFile,
  IconRobot,
  IconCalendar,
  IconBarChart,
  IconSettings,
  IconMoonFill,
  IconSunFill,
  IconUser,
  IconCommon,
  IconCommand,
  IconTrophy,
} from '@arco-design/web-vue/es/icon'

const router = useRouter()
const route = useRoute()
const appStore = useAppStore()
const userStore = useUserStore()

const openKeys = ref<string[]>([])

// 强制刷新后侧边栏收起
onMounted(() => {
  appStore.collapsed = true
})

const menuTitleMap: Record<string, string> = {
  '/notes': '笔记',
  '/chat': 'AI 助手',
  '/knowledge': '知识库',
  '/review': '每日复习',
  '/evaluation': '评估',
  '/system/llm': 'LLM 配置',
  '/system/prompt': '提示词管理',
  '/system/mcp': 'MCP 工具',
  '/system/skills': 'Skills 管理',
  '/settings': '个人设置',
}

const selectedKeys = computed(() => {
  const path = route.path
  if (path.startsWith('/system')) {
    if (!openKeys.value.includes('system')) {
      openKeys.value = ['system']
    }
    return [path]
  }
  return [path]
})

const currentTitle = computed(() => {
  return menuTitleMap[route.path] || ''
})

function handleMenuClick(path: string) {
  router.push(path)
}

function handleSubMenuClick(key: string) {
  if (openKeys.value.includes(key)) {
    openKeys.value = openKeys.value.filter(k => k !== key)
  } else {
    openKeys.value.push(key)
  }
}

let hoverTimer: ReturnType<typeof setTimeout> | null = null

function handleMouseEnter() {
  if (hoverTimer) clearTimeout(hoverTimer)
  appStore.collapsed = false
}

function handleMouseLeave() {
  hoverTimer = setTimeout(() => {
    appStore.collapsed = true
  }, 1200)
}

function handleLogout() {
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout {
  height: 100vh;
}

.sider {
  background: var(--color-bg-1);
  border-right: 1px solid var(--color-border);
}

.logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 600;
  color: var(--color-primary);
  border-bottom: 1px solid var(--color-border);
  white-space: nowrap;
  overflow: hidden;
}

.sider :deep(.arco-menu-collapsed) {
  width: 50px;
}


/* 收起时所有菜单项居中对齐 */
.sider :deep(.arco-menu-collapsed) :deep(.arco-menu-item) {
  display: flex !important;
  justify-content: center !important;
  padding: 0 !important;
}

.sider :deep(.arco-menu-collapsed) :deep(.arco-menu-icon) {
  margin: 0 !important;
}

/* 收起时隐藏子菜单箭头 */
.sider :deep(.arco-menu-collapsed) :deep(.arco-menu-icon-suffix) {
  display: none !important;
}

.sider :deep(.arco-menu-collapsed) :deep(.arco-menu-inline-header) {
  display: flex !important;
  justify-content: center !important;
  padding: 0 !important;
}

/* 隐藏内置折叠按钮 */
.sider :deep(.arco-menu-collapse-button) {
  display: none;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: var(--color-bg-1);
  border-bottom: 1px solid var(--color-border);
}

.header-left {
  display: flex;
  align-items: center;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.content {
  padding: 16px;
  background: var(--color-bg-2);
  overflow: auto;
}
</style>
