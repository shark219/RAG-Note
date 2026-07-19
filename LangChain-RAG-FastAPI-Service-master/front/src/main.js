import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import pinia from './store'
import axios from 'axios'
import { showToast } from 'vant'
import './config/api'

// 登录过期统一处理
function handleAuthExpired() {
  localStorage.removeItem('jwt_token')
  localStorage.removeItem('user-store')
  showToast({
    message: '登录过期，请重新登录',
    type: 'fail',
    duration: 2000,
    forbidClick: true
  })
  setTimeout(() => {
    router.push('/login')
  }, 1500)
}

// 全局 axios 响应拦截器：处理登录过期（401/403）
axios.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status
    if (status === 401 || status === 403) {
      handleAuthExpired()
    }
    return Promise.reject(error)
  }
)

// 全局 fetch 拦截器：处理登录过期（401/403）
const originalFetch = window.fetch
window.fetch = async (...args) => {
  const response = await originalFetch(...args)
  if (response.status === 401 || response.status === 403) {
    handleAuthExpired()
  }
  return response
}

// 导入Vant组件库
import { 
  Button, 
  NavBar, 
  Tabbar, 
  TabbarItem, 
  Tab, 
  Tabs, 
  List, 
  PullRefresh, 
  Cell, 
  CellGroup,
  Grid,
  GridItem,
  Empty,
  Form,
  Field,
  Image,
  Toast,
  Icon,
  Popup
} from 'vant'

// 导入Vant样式
import 'vant/lib/index.css'

// 导入全局样式
import './style.css'

// 引入国际化
import { setupI18n } from './i18n'

const app = createApp(App)

// 设置i18n
const i18n = setupI18n()
app.use(i18n)

// 注册Vant组件
app.use(Button)
app.use(NavBar)
app.use(Tabbar)
app.use(TabbarItem)
app.use(Tab)
app.use(Tabs)
app.use(List)
app.use(PullRefresh)
app.use(Cell)
app.use(CellGroup)
app.use(Grid)
app.use(GridItem)
app.use(Empty)
app.use(Form)
app.use(Field)
app.use(Image)
app.use(Toast)
app.use(Icon)
app.use(Popup)

// 使用路由和状态管理
app.use(router)
app.use(pinia)

app.mount('#app')

// 初始化主题
import { useThemeStore } from './store/theme'
const themeStore = useThemeStore()
themeStore.initTheme()
