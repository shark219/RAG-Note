<template>
  <router-view />
</template>

<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useAppStore } from '@/store/app'

const appStore = useAppStore()

function applyTheme(theme: 'light' | 'dark') {
  const html = document.documentElement
  const body = document.body
  const isDark = theme === 'dark'

  html.classList.toggle('dark', isDark)
  html.setAttribute('data-theme', theme)

  if (isDark) {
    body.setAttribute('arco-theme', 'dark')
  } else {
    body.removeAttribute('arco-theme')
  }
}

onMounted(() => {
  applyTheme(appStore.theme)
})

watch(() => appStore.theme, (t) => {
  applyTheme(t)
})
</script>

<style>
html, body {
  margin: 0;
  padding: 0;
  height: 100%;
}

#app {
  height: 100%;
}
</style>
