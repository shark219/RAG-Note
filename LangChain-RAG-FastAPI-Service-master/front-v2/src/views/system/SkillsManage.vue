<template>
  <div class="skills-manage">
    <!-- 页面头部 -->
    <div class="page-header">
      <h2>Skills 管理</h2>
      <div class="header-actions">
        <a-button @click="showSkillCenter = true">
          <template #icon><icon-book /></template>
          Skill 中心
        </a-button>
        <a-button @click="showGitImport = true">
          <template #icon><icon-branch /></template>
          从 Git 导入
        </a-button>
        <a-button type="primary" @click="showUpload = true">
          <template #icon><icon-upload /></template>
          上传 Skill
        </a-button>
      </div>
    </div>

    <!-- Skills 列表 -->
    <div class="skills-content">
      <!-- 空状态 -->
      <div v-if="skills.length === 0" class="empty-state">
        <div class="empty-icon">
          <icon-apps style="font-size: 64px" />
        </div>
        <h3>暂无 Skills</h3>
        <p>点击上方按钮上传或从 Skill 中心安装技能插件</p>
        <a-space>
          <a-button type="primary" @click="showSkillCenter = true">
            <template #icon><icon-book /></template>
            Skill 中心
          </a-button>
          <a-button @click="showUpload = true">
            <template #icon><icon-upload /></template>
            上传 Skill
          </a-button>
        </a-space>
      </div>

      <!-- Skills 网格 -->
      <div v-else class="skills-grid">
        <div v-for="skill in skills" :key="skill.id" class="skill-card">
          <div class="skill-header">
            <div class="skill-icon" :style="{ background: skill.color || '#165dff' }">
              {{ skill.icon || '📦' }}
            </div>
            <div class="skill-info">
              <div class="skill-name">{{ skill.name }}</div>
              <div class="skill-version">v{{ skill.version || '1.0.0' }}</div>
            </div>
            <a-switch v-model="skill.enabled" size="small" />
          </div>
          <div class="skill-desc">{{ skill.description || '暂无描述' }}</div>
          <div class="skill-footer">
            <a-tag size="small">{{ skill.type || '工具' }}</a-tag>
            <a-space>
              <a-button type="text" size="mini" @click="handleUninstall(skill)">卸载</a-button>
            </a-space>
          </div>
        </div>
      </div>
    </div>

    <!-- Skill 中心弹窗 -->
    <a-modal v-model:visible="showSkillCenter" title="Skill 中心" :width="800" :footer="false">
      <div class="skill-center">
        <!-- 搜索和源管理 -->
        <div class="center-header">
          <a-input-search placeholder="搜索技能..." style="width: 300px" />
          <a-button size="small" @click="showSourceManage = true">
            <template #icon><icon-settings /></template>
            源管理
          </a-button>
        </div>

        <!-- 技能列表 -->
        <div class="center-grid">
          <div v-for="item in centerSkills" :key="item.id" class="center-item">
            <div class="center-item-header">
              <span class="center-item-icon">{{ item.icon }}</span>
              <div class="center-item-info">
                <div class="center-item-name">{{ item.name }}</div>
                <div class="center-item-author">{{ item.author }}</div>
              </div>
              <a-tag size="small">{{ item.downloads }} 次下载</a-tag>
            </div>
            <div class="center-item-desc">{{ item.description }}</div>
            <div class="center-item-footer">
              <a-button type="primary" size="small" @click="handleInstall(item)">
                安装
              </a-button>
            </div>
          </div>
        </div>
      </div>
    </a-modal>

    <!-- 源管理弹窗 -->
    <a-modal v-model:visible="showSourceManage" title="技能源管理" :width="600">
      <div class="source-manage">
        <div v-for="(source, index) in skillSources" :key="index" class="source-item">
          <div class="source-info">
            <div class="source-name">
              {{ source.name }}
              <a-tag v-if="source.isDefault" size="small" color="blue">默认</a-tag>
            </div>
            <div class="source-url">{{ source.url }}</div>
          </div>
          <a-space v-if="!source.isDefault">
            <a-button type="text" size="mini" @click="handleEditSource(index)">编辑</a-button>
            <a-button type="text" size="mini" status="danger" @click="handleDeleteSource(index)">删除</a-button>
          </a-space>
        </div>
        <a-button type="outline" long style="margin-top: 12px" @click="handleAddSource">
          <template #icon><icon-plus /></template>
          添加源
        </a-button>
      </div>
    </a-modal>

    <!-- 添加源弹窗 -->
    <a-modal v-model:visible="showSourceForm" title="添加技能源" :width="500" @before-ok="handleSaveSource">
      <a-form :model="sourceForm" layout="vertical">
        <a-form-item label="显示名称" required>
          <a-input v-model="sourceForm.name" placeholder="例如：公司内网 Skill 中心" />
        </a-form-item>
        <a-form-item label="基础 URL" required>
          <a-input v-model="sourceForm.url" placeholder="https://example.com/skills/" />
          <div class="form-tip">该目录下必须包含 manifest.json 技能清单索引文件</div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- Git 导入弹窗 -->
    <a-modal v-model:visible="showGitImport" title="从 Git 导入 Skill" :width="500" @before-ok="handleGitImport">
      <a-form :model="gitForm" layout="vertical">
        <a-form-item label="Git 仓库地址" required>
          <a-input v-model="gitForm.url" placeholder="https://github.com/username/repo" />
        </a-form-item>
        <a-form-item label="分支 (Branch)">
          <a-input v-model="gitForm.branch" placeholder="main" />
          <div class="form-tip">默认拉取 main 主分支</div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 上传 Skill 弹窗 -->
    <a-modal v-model:visible="showUpload" title="上传 Skill" :width="500" :footer="false">
      <div class="upload-area">
        <a-upload
          :show-file-list="false"
          accept=".zip"
          :custom-request="handleUpload"
          draggable
        >
          <div class="upload-content">
            <icon-upload style="font-size: 48px; color: var(--color-text-3)" />
            <p>将 .zip 文件拖拽到此处，或点击上传</p>
            <p class="upload-tip">支持单个或多个 Skill 打包上传</p>
          </div>
        </a-upload>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import {
  IconBook,
  IconUpload,
  IconSettings,
  IconPlus,
  IconBranch,
  IconApps,
} from '@arco-design/web-vue/es/icon'

const showSkillCenter = ref(false)
const showSourceManage = ref(false)
const showSourceForm = ref(false)
const showGitImport = ref(false)
const showUpload = ref(false)

// 已安装的 Skills
const skills = ref<any[]>([])

// Skill 中心的 Skills
const centerSkills = ref([
  {
    id: 1,
    name: 'playwright-skill',
    icon: '🎭',
    description: '基于 Playwright 的浏览器自动化技能，支持网页操作、截图、录制等',
    author: '官方',
    downloads: 1280,
  },
  {
    id: 2,
    name: 'api-test-skill',
    icon: '🔗',
    description: 'API 接口测试技能，支持 RESTful 和 GraphQL 接口的自动化测试',
    author: '官方',
    downloads: 856,
  },
  {
    id: 3,
    name: 'data-analysis',
    icon: '📊',
    description: '数据分析技能，支持 CSV/Excel 数据处理、统计分析和可视化',
    author: '社区',
    downloads: 642,
  },
  {
    id: 4,
    name: 'file-manager',
    icon: '📁',
    description: '文件管理技能，支持文件读写、目录遍历、批量重命名等操作',
    author: '官方',
    downloads: 1024,
  },
])

// 技能源
const skillSources = ref([
  {
    name: 'RAG Note 官方 Skill 中心',
    url: 'https://raw.githubusercontent.com/rag-note/skills/main/',
    isDefault: true,
  },
])

const sourceForm = reactive({
  name: '',
  url: '',
})

const gitForm = reactive({
  url: '',
  branch: 'main',
})

function handleInstall(item: any) {
  Modal.confirm({
    title: '安装技能',
    content: `确定要安装「${item.name}」吗？`,
    onOk: () => {
      skills.value.push({
        id: Date.now(),
        name: item.name,
        icon: item.icon,
        description: item.description,
        version: '1.0.0',
        type: '工具',
        enabled: true,
        color: '#165dff',
      })
      Message.success(`已安装 ${item.name}`)
      showSkillCenter.value = false
    },
  })
}

function handleUninstall(skill: any) {
  Modal.confirm({
    title: '确认卸载',
    content: `确定要卸载技能「${skill.name}」吗？`,
    onOk: () => {
      skills.value = skills.value.filter(s => s.id !== skill.id)
      Message.success('已卸载')
    },
  })
}

function handleAddSource() {
  sourceForm.name = ''
  sourceForm.url = ''
  showSourceForm.value = true
}

function handleEditSource(index: number) {
  sourceForm.name = skillSources.value[index].name
  sourceForm.url = skillSources.value[index].url
  showSourceForm.value = true
}

function handleDeleteSource(index: number) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除技能源「${skillSources.value[index].name}」吗？`,
    onOk: () => {
      skillSources.value.splice(index, 1)
      Message.success('已删除')
    },
  })
}

function handleSaveSource() {
  if (!sourceForm.name || !sourceForm.url) {
    Message.warning('请填写必填项')
    return false
  }
  skillSources.value.push({
    name: sourceForm.name,
    url: sourceForm.url,
    isDefault: false,
  })
  Message.success('添加成功')
  return true
}

async function handleGitImport() {
  if (!gitForm.url) {
    Message.warning('请输入 Git 仓库地址')
    return false
  }
  Message.info('正在导入...')
  // 模拟导入
  setTimeout(() => {
    skills.value.push({
      id: Date.now(),
      name: gitForm.url.split('/').pop()?.replace('.git', '') || 'git-skill',
      icon: '🐱',
      description: '从 Git 仓库导入的技能',
      version: '1.0.0',
      type: '工具',
      enabled: true,
      color: '#722ed1',
    })
    Message.success('导入成功')
    gitForm.url = ''
    gitForm.branch = 'main'
  }, 1500)
  return true
}

async function handleUpload(option: any) {
  const file = option.fileItem.file
  Message.info(`正在上传 ${file.name}...`)
  setTimeout(() => {
    skills.value.push({
      id: Date.now(),
      name: file.name.replace('.zip', ''),
      icon: '📦',
      description: '本地上传的技能',
      version: '1.0.0',
      type: '工具',
      enabled: true,
      color: '#13c2c2',
    })
    Message.success('上传成功')
    showUpload.value = false
  }, 1000)
}
</script>

<style scoped>
.skills-manage {
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.page-header h2 {
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 8px;
}

/* 空状态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 100px 20px;
  background: var(--color-bg-1);
  border-radius: 8px;
  border: 1px dashed var(--color-border);
}

.empty-icon {
  width: 100px;
  height: 100px;
  border-radius: 20px;
  background: var(--color-bg-2);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
  color: var(--color-text-3);
}

.empty-state h3 {
  margin: 0 0 8px;
  font-size: 18px;
}

.empty-state p {
  color: var(--color-text-3);
  margin-bottom: 24px;
}

/* Skills 网格 */
.skills-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.skill-card {
  background: var(--color-bg-1);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 16px;
  transition: all 0.2s;
}

.skill-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}

.skill-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.skill-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
}

.skill-info {
  flex: 1;
}

.skill-name {
  font-weight: 600;
  font-size: 15px;
}

.skill-version {
  font-size: 12px;
  color: var(--color-text-3);
}

.skill-desc {
  font-size: 13px;
  color: var(--color-text-2);
  line-height: 1.5;
  margin-bottom: 12px;
  min-height: 40px;
}

.skill-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid var(--color-border);
}

/* Skill 中心 */
.skill-center {
  max-height: 600px;
  overflow-y: auto;
}

.center-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.center-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.center-item {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 12px;
}

.center-item-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.center-item-icon {
  font-size: 24px;
}

.center-item-info {
  flex: 1;
}

.center-item-name {
  font-weight: 500;
}

.center-item-author {
  font-size: 12px;
  color: var(--color-text-3);
}

.center-item-desc {
  font-size: 12px;
  color: var(--color-text-3);
  line-height: 1.5;
  margin-bottom: 8px;
  min-height: 36px;
}

.center-item-footer {
  display: flex;
  justify-content: flex-end;
}

/* 源管理 */
.source-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  margin-bottom: 8px;
}

.source-name {
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 8px;
}

.source-url {
  font-size: 12px;
  color: var(--color-text-3);
  margin-top: 4px;
}

/* 上传区域 */
.upload-area {
  padding: 40px 20px;
}

.upload-content {
  text-align: center;
}

.upload-content p {
  margin: 12px 0 4px;
}

.upload-tip {
  font-size: 12px;
  color: var(--color-text-3);
}

.form-tip {
  font-size: 12px;
  color: var(--color-text-3);
  margin-top: 4px;
}
</style>
