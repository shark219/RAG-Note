<template>
  <div class="register-container">
    <div class="register-card">
      <div class="register-header">
        <h1>注册账号</h1>
        <p>创建您的 RAG Note 账号</p>
      </div>
      <a-form :model="form" @submit-success="handleRegister" layout="vertical">
        <a-form-item field="username" label="用户名" :rules="[{ required: true, message: '请输入用户名' }]">
          <a-input v-model="form.username" placeholder="请输入用户名" size="large">
            <template #prefix><icon-user /></template>
          </a-input>
        </a-form-item>
        <a-form-item field="password" label="密码" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model="form.password" placeholder="请输入密码（6-20位）" size="large">
            <template #prefix><icon-lock /></template>
          </a-input-password>
        </a-form-item>
        <a-form-item field="confirmPassword" label="确认密码" :rules="[{ required: true, message: '请再次输入密码' }]">
          <a-input-password v-model="form.confirmPassword" placeholder="请再次输入密码" size="large">
            <template #prefix><icon-lock /></template>
          </a-input-password>
        </a-form-item>
        <a-form-item field="email" label="邮箱">
          <a-input v-model="form.email" placeholder="请输入邮箱" size="large">
            <template #prefix><icon-email /></template>
          </a-input>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" long size="large" :loading="loading">
            注册
          </a-button>
        </a-form-item>
        <div class="register-footer">
          已有账号？<router-link to="/login">立即登录</router-link>
        </div>
      </a-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { IconUser, IconLock, IconEmail } from '@arco-design/web-vue/es/icon'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  email: '',
})

async function handleRegister() {
  if (form.password !== form.confirmPassword) {
    Message.error('两次输入的密码不一致')
    return
  }
  if (form.password.length < 6) {
    Message.error('密码长度至少6位')
    return
  }
  loading.value = true
  try {
    await userStore.register(form.username, form.password, form.confirmPassword, form.email)
    Message.success('注册成功，请登录')
    router.push('/login')
  } catch (e: any) {
    Message.error(e.response?.data?.message || '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-container {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.register-card {
  width: 400px;
  padding: 40px;
  background: var(--color-bg-1);
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.register-header {
  text-align: center;
  margin-bottom: 32px;
}

.register-header h1 {
  font-size: 28px;
  color: var(--color-primary);
  margin: 0 0 8px;
}

.register-header p {
  color: var(--color-text-3);
  margin: 0;
}

.register-footer {
  text-align: center;
  color: var(--color-text-3);
}

.register-footer a {
  color: var(--color-primary);
  text-decoration: none;
}
</style>
