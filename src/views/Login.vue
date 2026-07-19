<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <h1>RAG Note</h1>
        <p>AI 智能笔记助手</p>
      </div>
      <a-form :model="form" @submit-success="handleLogin" layout="vertical">
        <a-form-item field="username" label="用户名" :rules="[{ required: true, message: '请输入用户名' }]">
          <a-input v-model="form.username" placeholder="请输入用户名" size="large">
            <template #prefix><icon-user /></template>
          </a-input>
        </a-form-item>
        <a-form-item field="password" label="密码" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model="form.password" placeholder="请输入密码" size="large">
            <template #prefix><icon-lock /></template>
          </a-input-password>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" long size="large" :loading="loading">
            登录
          </a-button>
        </a-form-item>
        <div class="login-footer">
          还没有账号？<router-link to="/register">立即注册</router-link>
        </div>
      </a-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { IconUser, IconLock } from '@arco-design/web-vue/es/icon'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
})

async function handleLogin() {
  loading.value = true
  try {
    await userStore.login(form.username, form.password)
    Message.success('登录成功')
    router.push('/notes')
  } catch (e: any) {
    Message.error(e.response?.data?.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-card {
  width: 400px;
  padding: 40px;
  background: var(--color-bg-1);
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.login-header h1 {
  font-size: 28px;
  color: var(--color-primary);
  margin: 0 0 8px;
}

.login-header p {
  color: var(--color-text-3);
  margin: 0;
}

.login-footer {
  text-align: center;
  color: var(--color-text-3);
}

.login-footer a {
  color: var(--color-primary);
  text-decoration: none;
}
</style>
