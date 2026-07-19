import { defineStore } from 'pinia';
import axios from 'axios';
import { apiConfig } from '../config/api';
import { getCsrfToken } from '../utils/csrf';

export const useUserStore = defineStore('user', {
  state: () => ({
    userInfo: null,
    token: '',
    isLogin: false,
    userBio: '这是我的个人简介'
  }),
  
  getters: {
    getUserInfo: (state) => state.userInfo,
    getToken: (state) => state.token,
    getLoginStatus: (state) => state.isLogin,
    getUserBio: (state) => state.userInfo?.bio || state.userBio
  },
  
  actions: {
    async login(userData) {
      try {
        const response = await axios.post(apiConfig.endpoints.login, {
          username: userData.username,
          password: userData.password
        }, {
          headers: {
            'X-CSRFTOKEN': getCsrfToken()
          }
        });
        
        // 检查响应状态
        if (response.status === 200 && response.data.code === 200) {
          // 登录成功
          const data = response.data.data;
          const userInfo = data.user;
          const token = data.token;
          // 将token存入到localStorage
          localStorage.setItem('jwt_token', token);

          this.userInfo = userInfo;
          this.token = token;
          this.isLogin = true;

          return {
            success: true,
            message: data.message
          };
        } else {
          // 登录失败
          return {
            success: false,
            message: response.data.message || '登录失败'
          };
        }
      } catch (error) {
        console.error('登录请求失败:', error);
        return {
          success: false,
          message: error.response?.data?.message || '登录请求失败，请稍后再试'
        };
      }
    },
    
    async logout() {
      try {
        // 发送注销请求
        const token = localStorage.getItem('jwt_token') || this.token;
        if (token) {
          await axios.post(apiConfig.endpoints.logout, {}, {
            headers: {
              Authorization: `Bearer ${token}`,
              'X-CSRFTOKEN': getCsrfToken()
            }
          });
        }
      } catch (error) {
        console.error('注销请求失败:', error);
      } finally {
        // 清除本地状态
        this.userInfo = null;
        this.token = '';
        this.isLogin = false;
        // 从localStorage中清除token
        localStorage.removeItem('jwt_token');
      }
    },
    
    // 获取用户信息
    async getUserInfoDetail() {
      try {
        // 从localStorage获取token
        const token = localStorage.getItem('jwt_token') || this.token;
        // 检查是否有token
        if (!token) {
          return {
            success: false,
            message: '未登录'
          };
        }
        
        // 发送获取用户信息请求
        const response = await axios.get(apiConfig.endpoints.profile, {
          headers: {
            Authorization: `Bearer ${token}`,
            'X-CSRFTOKEN': getCsrfToken()
          }
        });
        
        // 检查响应状态
        if (response.status === 200) {
          // 更新用户信息
          this.userInfo = response.data.data;
          
          return {
            success: true,
            message: response.data.message,
            data: response.data.data
          };
        } else {
          return {
            success: false,
            message: response.data.message || '获取用户信息失败'
          };
        }
      } catch (error) {
        console.error('获取用户信息请求失败:', error);
        return {
          success: false,
          message: error.response?.data?.message || '获取用户信息请求失败，请稍后再试'
        };
      }
    },
    
    // 更新用户信息
    async updateUserInfo(userData) {
      try {
        // 从localStorage获取token
        const token = localStorage.getItem('jwt_token') || this.token;
        // 检查是否有token
        if (!token) {
          return {
            success: false,
            message: '未登录'
          };
        }
        
        // 发送更新用户信息请求
        console.log('更新用户信息请求参数:', userData);
        const response = await axios.put('/user/update', userData, {
          headers: {
            Authorization: `Bearer ${token}`,
            'X-CSRFTOKEN': getCsrfToken(),
            'Content-Type': 'application/json'
          }
        });
        
        // 检查响应状态（后端返回 ApiResponse 包装格式）
        if (response.status === 200 && response.data.code === 200) {
          const data = response.data.data;
          // 更新用户信息
          this.userInfo = data.user;

          // 如果返回了新的token，更新token
          if (data.token) {
            this.token = data.token;
            localStorage.setItem('jwt_token', data.token);
          }

          return {
            success: true,
            message: data.message
          };
        } else {
          return {
            success: false,
            message: response.data.message || '更新用户信息失败'
          };
        }
      } catch (error) {
        console.error('更新用户信息请求失败:', error);
        console.error('错误响应:', error.response?.data);
        console.error('错误状态:', error.response?.status);
        return {
          success: false,
          message: error.response?.data?.message || error.response?.data?.detail || '更新用户信息请求失败，请稍后再试'
        };
      }
    },
    
    // 更新密码
    async updatePassword(oldPassword, newPassword) {
      try {
        // 从localStorage获取token
        const token = localStorage.getItem('jwt_token') || this.token;
        // 检查是否有token
        if (!token) {
          return {
            success: false,
            message: '未登录'
          };
        }
        
        // 发送更新密码请求
        const response = await axios.post('/user/reset-password', {
          oldPassword: oldPassword,
          newPassword: newPassword,
          confirmPassword: newPassword
        }, {
          headers: {
            Authorization: `Bearer ${token}`,
            'X-CSRFTOKEN': getCsrfToken(),
            'Content-Type': 'application/json'
          }
        });
        
        // 检查响应状态（后端返回 ApiResponse 包装格式）
        if (response.status === 200 && response.data.code === 200) {
          return {
            success: true,
            message: response.data.data?.message || response.data.message
          };
        } else {
          return {
            success: false,
            message: response.data.message || '更新密码失败'
          };
        }
      } catch (error) {
        console.error('更新密码请求失败:', error);
        return {
          success: false,
          message: error.response?.data?.message || '更新密码请求失败，请稍后再试'
        };
      }
    },
    
    // 用户注册
    async register(userData) {
      try {
        console.log('=== 开始注册请求 ===');
        console.log('请求数据:', userData);
        
        // 发送注册请求到用户服务
        const response = await axios.post(apiConfig.endpoints.register, {
          username: userData.username,
          email: userData.email,
          telephone: userData.telephone || '',
          password: userData.password,
          confirmPassword: userData.confirmPassword || userData.confirm_password
        }, {
          headers: {
            'X-CSRFTOKEN': getCsrfToken(),
            'Content-Type': 'application/json'
          }
        });
        
        console.log('=== 注册响应 ===');
        console.log('响应状态码:', response.status);
        console.log('响应数据:', response.data);
        
        // 根据后端返回的数据格式判断注册是否成功
        // 后端返回格式: { code: 200, message: "注册成功", data: { user: {...}, token: "..." } }
        if (response.data.code === 200 && response.data.data?.token) {
          // 注册成功
          const data = response.data.data;
          const token = data.token;
          const userInfo = data.user;

          // 保存token到localStorage
          localStorage.setItem('jwt_token', token);

          // 更新store状态
          this.userInfo = userInfo;
          this.token = token;
          this.isLogin = true;

          console.log('注册成功，已保存用户信息和token');
          return {
            success: true,
            message: data.message || '注册成功'
          };
        } else {
          // 注册失败
          console.log('注册失败:', response.data.message || '未知错误');
          return {
            success: false,
            message: response.data.message || '注册失败'
          };
        }
      } catch (error) {
        console.error('=== 注册请求异常 ===');
        console.error('错误:', error);
        
        // 处理错误响应
        let errorMessage = '注册失败，请稍后重试';
        if (error.response?.data?.message) {
          errorMessage = error.response.data.message;
        } else if (error.response?.data?.detail) {
          errorMessage = error.response.data.detail;
        }
        
        return {
          success: false,
          message: errorMessage
        };
      }
    }
  },
  
  // 添加持久化配置
  persist: {
    key: 'user-store',
    storage: localStorage
  }
});