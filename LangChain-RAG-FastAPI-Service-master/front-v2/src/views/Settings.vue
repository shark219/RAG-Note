<template>
  <div class="settings">
    <h2>设置</h2>

    <a-card title="主题设置" style="margin-bottom: 16px">
      <a-form :model="themeForm" layout="vertical">
        <a-form-item label="主题模式">
          <a-radio-group v-model="themeForm.theme" @change="handleThemeChange">
            <a-radio value="light">浅色</a-radio>
            <a-radio value="dark">深色</a-radio>
          </a-radio-group>
        </a-form-item>
      </a-form>
    </a-card>

    <a-card title="用户信息" style="margin-bottom: 16px">
      <a-descriptions :data="userInfo" :column="1" />
    </a-card>

    <a-card title="系统信息">
      <a-descriptions :data="systemInfo" :column="1" />
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, computed, onMounted } from 'vue'
import { useAppStore } from '@/store/app'
import { useUserStore } from '@/store/user'

const appStore = useAppStore()
const userStore = useUserStore()

const themeForm = reactive({
  theme: appStore.theme,
})

const userInfo = computed(() => [
  { label: '用户名', value: userStore.userInfo?.username || '-' },
  { label: '邮箱', value: userStore.userInfo?.email || '-' },
])

const systemInfo = [
  { label: '应用版本', value: '1.0.0' },
  { label: '技术栈', value: 'Vue 3 + Arco Design + Spring Boot' },
]

function handleThemeChange(value: string | number | boolean) {
  appStore.setTheme(value === 'dark' ? 'dark' : 'light')
}

onMounted(() => {
  userStore.fetchProfile()
})
</script>

<style scoped>
.settings {
  max-width: 800px;
  margin: 0 auto;
}

.settings h2 {
  margin-bottom: 20px;
}
</style>
