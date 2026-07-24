import axios from 'axios'
import { Message } from '@arco-design/web-vue'
import router from '@/router'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

// 请求拦截器
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器
api.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status
    if (status === 401 || status === 403) {
      localStorage.removeItem('token')
      Message.error('登录已过期，请重新登录')
      router.push('/login')
    } else if (status === 500) {
      Message.error('服务器错误，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default api

// API endpoints
export const userApi = {
  login: (data: { username: string; password: string }) =>
    api.post('/user/login', data),
  register: (data: { username: string; password: string; confirmPassword: string; email: string }) =>
    api.post('/user/register', data),
  getProfile: () => api.get('/user/detail'),
}

export const noteApi = {
  list: (params?: { page?: number; pageSize?: number; category?: string }) =>
    api.get('/note/list', { params }),
  get: (id: string) => api.get(`/note/${id}`),
  create: (data: { title: string; content: string; category?: string }) =>
    api.post('/note/create', data),
  update: (id: string, data: { title: string; content: string }) =>
    api.put(`/note/${id}`, data),
  delete: (id: string) => api.delete(`/note/${id}`),
  search: (q: string) =>
    api.get('/note/search', { params: { q } }),
  assist: (data: { content: string; action: string }) =>
    api.post('/note/assist/stream', data),
  getRelated: (id: string) => api.get(`/note/${id}/related`),
  autoTag: (id: string) => api.post(`/note/${id}/auto-tag`),
}

export const chatApi = {
  // 会话管理
  getSessions: () => api.get('/chat/sessions'),
  createSession: () => api.post('/chat/session/create'),
  getSession: (id: string) => api.get(`/chat/session/${id}`),
  updateSessionTitle: (id: string, title: string) =>
    api.put(`/chat/session/${id}/title`, { title }),
  deleteSession: (id: string) => api.delete(`/chat/session/${id}`),
  deleteSessions: (sessionIds: string[]) =>
    api.delete('/chat/sessions/batch', { data: { session_ids: sessionIds } }),
  clearAllSessions: () => api.delete('/chat/sessions/clear'),

  // Token 统计
  getSessionTokens: (id: string) => api.get(`/chat/session/${id}/tokens`),

  // 消息操作
  deleteMessage: (messageId: number) => api.delete(`/chat/message/${messageId}`),
  regenerateMessage: (messageId: number) =>
    fetch(`/api/chat/message/${messageId}/regenerate`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${localStorage.getItem('token')}`,
      },
    }),

  // 附件管理
  uploadAttachment: (file: File, sessionId?: string) => {
    const formData = new FormData()
    formData.append('file', file)
    if (sessionId) formData.append('session_id', sessionId)
    return api.post('/chat/attachment/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  getSessionAttachments: (sessionId: string) =>
    api.get(`/chat/session/${sessionId}/attachments`),
  deleteAttachment: (attachmentId: string) =>
    api.delete(`/chat/attachment/${attachmentId}`),

  // 提示词
  getPrompts: () => api.get('/chat/prompts'),
  getDefaultPrompt: () => api.get('/chat/prompts/default'),

  // 流式对话
  sendStream: (data: { query: string; sessionId?: string; regenerate?: boolean; enableKnowledge?: boolean; enableNotes?: boolean; fileIds?: string[] }) =>
    fetch('/api/chat/agent/query/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${localStorage.getItem('token')}`,
      },
      body: JSON.stringify(data),
    }),
}

export const knowledgeApi = {
  list: () => api.get('/knowledge/list'),
  upload: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return api.post('/knowledge/add/single', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  uploadMultiple: (files: File[]) => {
    const formData = new FormData()
    files.forEach((file) => formData.append('files', file))
    return api.post('/knowledge/add/multiple', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  uploadMultipleStream: (files: File[]) => {
    const formData = new FormData()
    files.forEach((file) => formData.append('files', file))
    return fetch('/api/knowledge/add/multiple/stream', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${localStorage.getItem('token')}`,
      },
      body: formData,
    })
  },
  clean: () => api.delete('/knowledge/clean'),
  getDetail: (filename: string) =>
    api.get('/knowledge/detail', { params: { filename } }),
  getChunks: (filename: string) =>
    api.get('/knowledge/chunks', { params: { filename } }),
  deleteByFilename: (filename: string) =>
    api.delete('/knowledge/delete/filename', { params: { filename } }),
  retryVectorization: (docId: string) =>
    api.post(`/knowledge/retry-vectorization/${docId}`),
  getBatchImages: (md5: string) =>
    api.get(`/knowledge/images/all/${md5}`),
}

export const reviewApi = {
  getToday: () => api.get('/review/today'),
  markDone: (noteId: string) => api.post(`/review/done/${noteId}`),
  getQuestion: (noteId: string) => api.get(`/review/question/${noteId}`),
}

export const evaluationApi = {
  getStats: () => api.get('/evaluation/stats'),
  getReports: (params?: { page?: number; size?: number }) =>
    api.get('/evaluation/reports', { params }),
  getReport: (traceId: string) => api.get(`/evaluation/report/${traceId}`),
  runBatch: (days?: number) => api.post('/evaluation/batch', null, { params: { days: days || 0 } }),
  submitFeedback: (data: { traceId: string; score: number; reason?: string }) =>
    api.post('/evaluation/feedback', data),
  getTrend: (days?: number) => api.get('/evaluation/trend', { params: { days: days || 14 } }),
  getDistribution: (days?: number) => api.get('/evaluation/distribution', { params: { days: days || 30 } }),
  getDiagnosisStats: (days?: number) => api.get('/evaluation/diagnosis-stats', { params: { days: days || 30 } }),
  getLowScores: () => api.get('/evaluation/low-scores'),
  generateTestCases: (count?: number) =>
    api.post('/evaluation/test-cases/generate', null, { params: { count: count || 10 } }),
  runRegression: () => api.post('/evaluation/regression'),
}
