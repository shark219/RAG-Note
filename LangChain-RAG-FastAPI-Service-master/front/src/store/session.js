import { defineStore } from 'pinia';
import axios from 'axios';
import { apiConfig } from '../config/api';

export const useSessionStore = defineStore('session', {
  state: () => ({
    sessions: [],
    currentSession: null,
    loading: false
  }),
  
  getters: {
    getSessions: (state) => state.sessions,
    getCurrentSession: (state) => state.currentSession,
    isLoading: (state) => state.loading
  },
  
  actions: {
    // 获取用户的所有会话
    async getUserSessions(userId) {
      try {
        this.loading = true;
        const token = localStorage.getItem('jwt_token');
        
        const response = await axios.get(`${apiConfig.endpoints.getUserSessions}/${userId}`, {
          headers: {
            Authorization: `Bearer ${token}`
          }
        });
        
        // 正确处理响应数据，从 response.data.data.sessions 获取会话列表
        const sessionsData = response.data.data?.sessions || [];
        
        // 将会话数据转换为前端需要的格式
        this.sessions = sessionsData.map(session => ({
          session_id: session.session_id || session.sessionId || session.id,
          title: session.title,
          created_at: session.created_at,
          updated_at: session.updated_at
        }));
        
        // 按照更新时间和创建时间综合排序（优先按更新时间，其次按创建时间）
        this.sessions.sort((a, b) => {
          const dateA = new Date(a.updated_at || a.created_at);
          const dateB = new Date(b.updated_at || b.created_at);
          return dateB - dateA; // 降序排列，最新的在前面
        });
        

        return {
          success: true,
          data: this.sessions
        };
      } catch (error) {
        console.error('获取用户会话失败:', error);
        return {
          success: false,
          message: error.response?.data?.message || '获取会话失败'
        };
      } finally {
        this.loading = false;
      }
    },
    
    // 获取单个会话详情
    async getSession(sessionId) {
      try {
        this.loading = true;
        const token = localStorage.getItem('jwt_token');
        
        const response = await axios.get(`${apiConfig.endpoints.getSession}${sessionId}`, {
          headers: {
            Authorization: `Bearer ${token}`
          }
        });
        
        // 处理可能的包装格式，支持 {code, message, data} 格式
        const sessionData = response.data.data || response.data;
        // 兼容 Java 后端 camelCase 和 snake_case
        if (sessionData && sessionData.sessionId && !sessionData.session_id) {
          sessionData.session_id = sessionData.sessionId;
        }
        this.currentSession = sessionData;
        return {
          success: true,
          data: sessionData
        };
      } catch (error) {
        console.error('获取会话详情失败:', error);
        return {
          success: false,
          message: error.response?.data?.message || '获取会话详情失败'
        };
      } finally {
        this.loading = false;
      }
    },
    
    // 删除会话
    async deleteSession(sessionId) {
      try {
        this.loading = true;
        const token = localStorage.getItem('jwt_token');
        
        await axios.delete(`${apiConfig.endpoints.deleteSession}${sessionId}`, {
          headers: {
            Authorization: `Bearer ${token}`
          }
        });
        
        // 从本地会话列表中移除
        if (Array.isArray(this.sessions)) {
          this.sessions = this.sessions.filter(session => session.session_id !== sessionId);
        } else {
          // 如果sessions不是数组，重置为空数组
          this.sessions = [];
        }
        
        // 如果删除的是当前会话，清除当前会话
        if (this.currentSession && this.currentSession.session_id === sessionId) {
          this.currentSession = null;
        }
        
        return {
          success: true,
          message: '会话删除成功'
        };
      } catch (error) {
        console.error('删除会话失败:', error);
        return {
          success: false,
          message: error.response?.data?.message || '删除会话失败'
        };
      } finally {
        this.loading = false;
      }
    },
    
    // 创建新会话（通过发送第一个消息）
    // 只从 SSE 流中拿到 sessionId 就立刻返回，不等整个流结束
    async createSession(query) {
      try {
        this.loading = true;
        const token = localStorage.getItem('jwt_token');

        const response = await fetch(apiConfig.endpoints.agentQueryStream, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`
          },
          body: JSON.stringify({
            query: query
          })
        });

        if (!response.ok) {
          const error = await response.json().catch(() => ({}));
          throw new Error(error.message || `HTTP error! status: ${response.status}`);
        }

        // 从 SSE 流中提取 sessionId，拿到就立刻返回
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let sessionId = null;

        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
              if (line.startsWith('data:')) {
                const data = line.startsWith('data: ') ? line.slice(6) : line.slice(5);
                if (!data) continue;

                try {
                  const raw = JSON.parse(data);
                  const json = raw.data || raw;
                  const sid = json.session_id || json.sessionId;
                  if (sid) {
                    sessionId = sid;
                    break;
                  }
                } catch (e) {
                  // 忽略解析错误，继续读取
                }
              }
            }

            if (sessionId) break;
          }
        } finally {
          reader.releaseLock();
        }

        if (sessionId) {
          return {
            success: true,
            data: { session_id: sessionId }
          };
        } else {
          throw new Error('创建会话失败，未获取到会话ID');
        }
      } catch (error) {
        console.error('创建会话失败:', error);
        return {
          success: false,
          message: error.message || '创建会话失败'
        };
      } finally {
        this.loading = false;
      }
    },
    
    // 设置当前会话
    setCurrentSession(session) {
      this.currentSession = session;
    },
    
    // 清除所有会话
    clearSessions() {
      this.sessions = [];
      this.currentSession = null;
    }
  }
});