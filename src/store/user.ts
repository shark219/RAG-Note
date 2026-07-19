import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { userApi } from '@/api'

export const useUserStore = defineStore('user', () => {
  const token = ref<string | null>(localStorage.getItem('token'))
  const userInfo = ref<any>(null)

  const isLoggedIn = computed(() => !!token.value)

  async function login(username: string, password: string) {
    const res: any = await userApi.login({ username, password })
    const data = res.data || res
    token.value = data.token
    localStorage.setItem('token', data.token)
    await fetchProfile()
    return data
  }

  async function register(username: string, password: string, confirmPassword: string, email: string) {
    return await userApi.register({ username, password, confirmPassword, email })
  }

  async function fetchProfile() {
    try {
      const res: any = await userApi.getProfile()
      userInfo.value = res.data || res
    } catch (e) {
      console.error('获取用户信息失败', e)
    }
  }

  function logout() {
    token.value = null
    userInfo.value = null
    localStorage.removeItem('token')
  }

  return {
    token,
    userInfo,
    isLoggedIn,
    login,
    register,
    fetchProfile,
    logout,
  }
}, {
  persist: {
    key: 'rag-note-user',
    pick: ['token'],
  }
})
