import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAppStore = defineStore('app', () => {
  const collapsed = ref(false)
  const theme = ref<'light' | 'dark'>('light')
  const locale = ref('zh-CN')

  function toggleCollapsed() {
    collapsed.value = !collapsed.value
  }

  function setTheme(t: 'light' | 'dark') {
    theme.value = t
    document.documentElement.setAttribute('data-theme', t)
  }

  function toggleTheme() {
    setTheme(theme.value === 'light' ? 'dark' : 'light')
  }

  return {
    collapsed,
    theme,
    locale,
    toggleCollapsed,
    setTheme,
    toggleTheme,
  }
}, {
  persist: {
    key: 'rag-note-app',
    pick: ['theme', 'locale', 'collapsed'],
  }
})
