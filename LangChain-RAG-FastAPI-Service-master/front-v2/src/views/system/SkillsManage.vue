<template>
  <div class="skills-manage">
    <div class="page-header">
      <div>
        <h2>Skills 管理</h2>
        <p class="page-description">管理已安装 Skills，并从 Skill 中心、Git 或 zip 包导入。</p>
      </div>
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

    <a-spin :loading="loadingSkills" class="skills-spin">
      <div v-if="skills.length === 0" class="empty-state">
        <div class="empty-icon">
          <icon-apps style="font-size: 64px" />
        </div>
        <h3>暂无 Skills</h3>
        <p>点击上方按钮上传或从 Skill 中心安装技能插件。</p>
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

      <div v-else class="skills-grid">
        <div v-for="skill in skills" :key="skill.id" class="skill-card" :class="{ disabled: skill.enabled === false }">
          <div class="skill-header">
            <div class="skill-icon" :style="{ background: skill.color || '#165dff' }">
              {{ skill.icon || '📦' }}
            </div>
            <div class="skill-info">
              <div class="skill-name">{{ skill.name }}</div>
              <div class="skill-version">v{{ skill.version || '1.0.0' }}</div>
            </div>
            <a-switch
              :model-value="skill.enabled !== false"
              size="small"
              @change="(value) => handleToggle(skill, value as boolean)"
            />
          </div>
          <div class="skill-desc">{{ skill.description || '暂无描述' }}</div>
          <div class="skill-meta">
            <span v-if="skill.author">作者：{{ skill.author }}</span>
            <span>来源：{{ skill.sourceType || '未知' }}</span>
          </div>
          <div class="skill-footer">
            <a-tag size="small">{{ skill.sourceType || '工具' }}</a-tag>
            <a-space>
              <a-button type="text" size="mini" @click="handleViewContent(skill)">查看</a-button>
              <a-button type="text" size="mini" status="danger" @click="handleUninstall(skill)">卸载</a-button>
            </a-space>
          </div>
        </div>
      </div>
    </a-spin>

    <a-modal v-model:visible="showSkillCenter" title="Skill 中心" :width="920" :footer="false" :mask-closable="false" :unmount-on-close="false">
      <div class="skill-store">
        <div class="store-toolbar">
          <a-space wrap>
            <a-select
              v-model="activeSourceId"
              :style="{ width: '260px' }"
              placeholder="选择源"
              @change="onSourceChange"
            >
              <a-option
                v-for="source in allSources"
                :key="source.id"
                :value="source.id"
                :label="source.name"
              />
            </a-select>
            <a-button @click="showSourceManage = true">
              <template #icon><icon-settings /></template>
              源管理
            </a-button>
            <a-button :loading="loadingManifest" @click="loadManifest()">
              <template #icon><icon-refresh /></template>
              刷新
            </a-button>
          </a-space>
          <a-input v-model="searchKeyword" placeholder="按名称或描述搜索" allow-clear :style="{ width: '240px' }">
            <template #prefix><icon-search /></template>
          </a-input>
        </div>

        <div v-if="filteredCenterSkills.length > 0" class="select-bar">
          <a-checkbox
            :model-value="allSelected"
            :indeterminate="someSelected && !allSelected"
            @change="toggleSelectAll"
          >
            全选（{{ filteredCenterSkills.length }}）
          </a-checkbox>
          <span v-if="selectedCount > 0" class="selected-count">已选 {{ selectedCount }} 个</span>
        </div>

        <a-spin class="store-content-spin" :loading="loadingManifest">
          <div v-if="manifestError" class="store-empty">
            <icon-exclamation-circle style="font-size: 40px; color: var(--color-danger-light-4)" />
            <p class="empty-text">{{ manifestError }}</p>
            <a-button type="primary" size="small" @click="loadManifest()">重试</a-button>
          </div>

          <div v-else-if="!loadingManifest && filteredCenterSkills.length === 0" class="store-empty">
            <icon-empty style="font-size: 48px; color: #c0c4cc" />
            <p class="empty-text">{{ searchKeyword ? '没有匹配的 Skill' : '该源下暂无 Skill' }}</p>
          </div>

          <div v-else class="store-grid">
            <div
              v-for="state in filteredCenterSkills"
              :key="state.key"
              class="store-card"
              :class="{
                selected: state.selected,
                installed: state.installed,
                busy: state.status === 'installing' || state.status === 'uninstalling',
                error: state.status === 'error',
              }"
              @click="toggleSelect(state)"
            >
              <div class="card-top">
                <a-checkbox
                  :model-value="state.selected"
                  @click.stop
                  @change="(value) => onCheckboxChange(state, value as boolean)"
                />
                <div class="card-title">{{ state.displayName }}</div>
                <a-tag v-if="state.installed" color="green" size="small">已安装</a-tag>
              </div>
              <div class="card-desc">{{ state.description }}</div>
              <div class="card-meta">
                <span v-if="state.version" class="meta-item"><icon-tag /> v{{ state.version }}</span>
                <span v-if="state.author" class="meta-item"><icon-user /> {{ state.author }}</span>
              </div>
              <div v-if="state.tags.length > 0" class="card-tags">
                <a-tag v-for="tag in state.tags" :key="tag" size="small">{{ tag }}</a-tag>
              </div>
              <div class="card-footer">
                <a-space size="mini">
                  <a-button v-if="state.readmePath" type="text" size="mini" @click.stop="openReadme(state)">预览</a-button>
                  <a-button
                    v-if="!state.installed"
                    type="text"
                    size="mini"
                    @click.stop="handleInstall(state)"
                  >安装</a-button>
                </a-space>
                <span v-if="state.status === 'installing'" class="status-indicator"><icon-loading /> 安装中</span>
                <span v-else-if="state.status === 'uninstalling'" class="status-indicator"><icon-loading /> 卸载中</span>
                <span v-else-if="state.status === 'ok'" class="status-indicator ok"><icon-check-circle-fill /> 完成</span>
                <span v-else-if="state.status === 'error'" class="status-indicator err" :title="state.error"><icon-close-circle-fill /> 失败</span>
              </div>
            </div>
          </div>
        </a-spin>

        <div v-if="storeStates.length > 0" class="store-footer">
          <div class="footer-left">
            <a-progress
              v-if="batchTotal > 0"
              :percent="batchProgressRatio"
              :status="batchProgressStatus"
              :show-text="true"
              :style="{ width: '260px' }"
            />
          </div>
          <div class="footer-right">
            <a-button :disabled="batchRunning" @click="showSkillCenter = false">关闭</a-button>
            <a-button type="outline" status="danger" :disabled="batchRunning || selectedInstalledCount === 0" @click="handleBatchUninstall">
              批量卸载（{{ selectedInstalledCount }}）
            </a-button>
            <a-button type="primary" :disabled="batchRunning || selectedNotInstalledCount === 0" @click="handleBatchInstall">
              批量安装（{{ selectedNotInstalledCount }}）
            </a-button>
          </div>
        </div>
      </div>
    </a-modal>

    <a-modal v-model:visible="showReadme" :title="currentReadmeTitle" :width="720" :footer="false" :unmount-on-close="true">
      <a-spin :loading="loadingReadme">
        <pre class="readme-content">{{ currentReadmeContent }}</pre>
      </a-spin>
    </a-modal>

    <a-modal v-model:visible="showSourceManage" title="管理 Skill 中心源" :width="640" :footer="false" :unmount-on-close="true">
      <div class="source-manager">
        <div class="section-title">默认源</div>
        <div v-if="defaultSource" class="default-source">
          <div class="source-name">
            <icon-star-fill style="color: rgb(var(--gold-6))" />
            {{ defaultSource.name }}
            <a-tag size="small" color="gold">内置</a-tag>
          </div>
          <div class="source-url">{{ defaultSource.baseUrl }}</div>
          <div class="source-hint">默认源只读，来自当前系统配置。</div>
        </div>
        <div v-else class="source-hint">当前未配置默认源。</div>

        <a-divider />

        <div class="section-header">
          <span class="section-title">自定义源</span>
          <a-button type="primary" size="small" @click="openAddSourceForm">
            <template #icon><icon-plus /></template>
            添加源
          </a-button>
        </div>

        <a-form v-if="sourceFormVisible" :model="sourceForm" layout="vertical" class="source-form">
          <a-form-item label="显示名称" required>
            <a-input v-model="sourceForm.name" placeholder="例如：公司内网 Skill 中心" :max-length="40" />
          </a-form-item>
          <a-form-item label="基础 URL" required>
            <a-input v-model="sourceForm.baseUrl" placeholder="https://example.com/path/to/store/" />
            <template #extra>
              <span class="form-tip">必须为 HTTPS，且应是包含 manifest.json 的目录。</span>
            </template>
          </a-form-item>
          <a-space>
            <a-button type="primary" @click="saveSource">保存</a-button>
            <a-button @click="cancelSourceForm">取消</a-button>
          </a-space>
        </a-form>

        <div v-if="customSources.length === 0 && !sourceFormVisible" class="empty-custom">暂无自定义源。</div>

        <div class="custom-list">
          <div v-for="source in customSources" :key="source.id" class="custom-item">
            <div class="source-info">
              <div class="source-name">{{ source.name }}</div>
              <div class="source-url">{{ source.baseUrl }}</div>
            </div>
            <a-space>
              <a-button type="text" size="mini" @click="openEditSourceForm(source)">编辑</a-button>
              <a-popconfirm content="确定删除此自定义源？" @ok="removeSource(source.id)">
                <a-button type="text" size="mini" status="danger">删除</a-button>
              </a-popconfirm>
            </a-space>
          </div>
        </div>
      </div>
    </a-modal>

    <a-modal v-model:visible="showContentModal" :title="currentSkillContent?.name || 'Skill 内容'" :width="720" :footer="false">
      <div v-if="currentSkillContent" class="content-view">
        <div class="content-description">{{ currentSkillContent.description }}</div>
        <a-divider />
        <pre class="content-body">{{ currentSkillContent.content }}</pre>
      </div>
    </a-modal>

    <a-modal v-model:visible="showGitImport" title="从 Git 导入 Skill" :width="500" @before-ok="handleGitImport">
      <a-form :model="gitForm" layout="vertical">
        <a-form-item label="Git 仓库地址" required>
          <a-input v-model="gitForm.url" placeholder="https://github.com/username/repo" />
        </a-form-item>
        <a-form-item label="分支 (Branch)">
          <a-input v-model="gitForm.branch" placeholder="main" />
          <div class="form-tip">默认拉取 main 主分支。</div>
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:visible="showUpload" title="上传 Skill" :width="500" :footer="false">
      <div class="upload-area">
        <a-upload
          :show-file-list="false"
          accept=".zip"
          :custom-request="(option: any) => handleUpload(option) as any"
          draggable
        >
          <div class="upload-content">
            <icon-upload style="font-size: 48px; color: var(--color-text-3)" />
            <p>将 .zip 文件拖拽到此处，或点击上传</p>
            <p class="upload-tip">支持单个或多个 Skill 打包上传。</p>
          </div>
        </a-upload>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import {
  IconApps,
  IconBook,
  IconBranch,
  IconCheckCircleFill,
  IconCloseCircleFill,
  IconEmpty,
  IconExclamationCircle,
  IconLoading,
  IconPlus,
  IconRefresh,
  IconSearch,
  IconSettings,
  IconStarFill,
  IconTag,
  IconUpload,
  IconUser,
} from '@arco-design/web-vue/es/icon'
import { skillApi } from '@/api'

type SkillRecord = {
  id: number
  name: string
  description?: string
  version?: string
  author?: string
  sourceType?: string
  icon?: string
  color?: string
  enabled?: boolean
}

type SkillSource = {
  id: string
  name: string
  baseUrl: string
  isDefault?: boolean
}

type ManifestSkill = {
  id?: string
  name: string
  name_en?: string
  description?: string
  description_en?: string
  version?: string
  author?: string
  tags?: string[]
  zip_path?: string
  zipPath?: string
  readme_path?: string
  readmePath?: string
  sha256?: string
  icon?: string
}

type StoreItemState = {
  key: string
  item: ManifestSkill
  displayName: string
  description: string
  version: string
  author: string
  tags: string[]
  zipPath: string
  readmePath: string
  sha256: string
  selected: boolean
  installed: boolean
  installedId: number | null
  status: 'idle' | 'installing' | 'uninstalling' | 'ok' | 'error'
  error: string
}

const STORAGE_CUSTOM_SOURCES_KEY = 'rag_note_skill_store_custom_sources'
const STORAGE_ACTIVE_SOURCE_KEY = 'rag_note_skill_store_active_source_id'
const DEFAULT_SOURCE_ID = '__default__'

const showSkillCenter = ref(false)
const showSourceManage = ref(false)
const showGitImport = ref(false)
const showUpload = ref(false)
const showReadme = ref(false)
const showContentModal = ref(false)

const loadingSkills = ref(false)
const loadingManifest = ref(false)
const loadingReadme = ref(false)
const batchRunning = ref(false)

const skills = ref<SkillRecord[]>([])
const customSources = ref<SkillSource[]>([])
const manifestError = ref('')
const searchKeyword = ref('')
const storeStates = ref<StoreItemState[]>([])
const batchTotal = ref(0)
const batchProcessed = ref(0)
const batchFailed = ref(0)
const currentReadmeTitle = ref('README')
const currentReadmeContent = ref('')
const currentSkillContent = ref<{ name: string; description: string; content: string } | null>(null)

const defaultSource = ref<SkillSource | null>(null)
const activeSourceId = ref(DEFAULT_SOURCE_ID)
const editingSourceId = ref<string | null>(null)
const sourceFormVisible = ref(false)

const sourceForm = reactive({ name: '', baseUrl: '' })
const gitForm = reactive({ url: '', branch: 'main' })

const allSources = computed(() => {
  const list: SkillSource[] = []
  if (defaultSource.value) list.push(defaultSource.value)
  list.push(...customSources.value)
  return list
})

const activeSource = computed(() => {
  return allSources.value.find((item) => item.id === activeSourceId.value) || defaultSource.value
})

const filteredCenterSkills = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  if (!keyword) return storeStates.value
  return storeStates.value.filter((state) => {
    return state.displayName.toLowerCase().includes(keyword) || state.description.toLowerCase().includes(keyword)
  })
})

const selectedCount = computed(() => filteredCenterSkills.value.filter((item) => item.selected).length)
const selectedInstalledCount = computed(() => filteredCenterSkills.value.filter((item) => item.selected && item.installed).length)
const selectedNotInstalledCount = computed(() => filteredCenterSkills.value.filter((item) => item.selected && !item.installed).length)
const allSelected = computed(() => filteredCenterSkills.value.length > 0 && filteredCenterSkills.value.every((item) => item.selected))
const someSelected = computed(() => filteredCenterSkills.value.some((item) => item.selected))
const batchProgressRatio = computed(() => {
  if (batchTotal.value === 0) return 0
  return Number((batchProcessed.value / batchTotal.value).toFixed(2))
})
const batchProgressStatus = computed(() => {
  if (batchRunning.value) return 'normal'
  if (batchFailed.value > 0) return 'warning'
  return 'success'
})

function normalizeBaseUrl(url: string) {
  const trimmed = (url || '').trim()
  if (!trimmed) return ''
  return trimmed.endsWith('/') ? trimmed : `${trimmed}/`
}

function readCustomSources() {
  try {
    const raw = localStorage.getItem(STORAGE_CUSTOM_SOURCES_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function writeCustomSources(sources: SkillSource[]) {
  localStorage.setItem(STORAGE_CUSTOM_SOURCES_KEY, JSON.stringify(sources))
}

function getActiveSourceId() {
  return localStorage.getItem(STORAGE_ACTIVE_SOURCE_KEY) || DEFAULT_SOURCE_ID
}

function setActiveSourceId(sourceId: string) {
  localStorage.setItem(STORAGE_ACTIVE_SOURCE_KEY, sourceId)
}

function loadSources() {
  customSources.value = readCustomSources()
}

function ensureActiveSource() {
  const exists = allSources.value.some((item) => item.id === activeSourceId.value)
  if (!exists) {
    activeSourceId.value = DEFAULT_SOURCE_ID
    setActiveSourceId(DEFAULT_SOURCE_ID)
  }
}

function getErrorMessage(error: any, fallback: string) {
  return error?.response?.data?.message || error?.response?.data?.msg || error?.message || fallback
}

async function ensureStoreConfig() {
  if (defaultSource.value) return
  try {
    const res: any = await skillApi.storeConfig()
    const config = res?.data || res
    defaultSource.value = {
      id: DEFAULT_SOURCE_ID,
      name: config?.name || 'RAG Note 官方 Skill 中心',
      baseUrl: config?.baseUrl || 'https://raw.githubusercontent.com/MGdaasLab/WHartTest/master/WHartTest_Skills/',
      isDefault: true,
    }
  } catch {
    defaultSource.value = {
      id: DEFAULT_SOURCE_ID,
      name: 'RAG Note 官方 Skill 中心',
      baseUrl: 'https://raw.githubusercontent.com/MGdaasLab/WHartTest/master/WHartTest_Skills/',
      isDefault: true,
    }
  }
}

function buildStoreState(item: ManifestSkill, index: number): StoreItemState {
  const zipPath = item.zip_path || item.zipPath || ''
  const readmePath = item.readme_path || item.readmePath || ''
  const installed = skills.value.find((skill) => skill.name === item.name) || null
  return {
    key: item.id || `${item.name}-${index}`,
    item,
    displayName: item.name_en || item.name,
    description: item.description_en || item.description || '',
    version: item.version || '',
    author: item.author || '',
    tags: Array.isArray(item.tags) ? item.tags : [],
    zipPath,
    readmePath,
    sha256: item.sha256 || '',
    selected: false,
    installed: Boolean(installed),
    installedId: installed?.id ?? null,
    status: 'idle',
    error: '',
  }
}

async function loadSkills() {
  loadingSkills.value = true
  try {
    const res: any = await skillApi.list()
    const data = Array.isArray(res?.data) ? res.data : Array.isArray(res) ? res : []
    skills.value = data
    reconcileInstalledState()
  } catch (error: any) {
    Message.error(getErrorMessage(error, '加载 Skill 失败'))
  } finally {
    loadingSkills.value = false
  }
}

function reconcileInstalledState() {
  const installedMap = new Map(skills.value.map((skill) => [skill.name, skill.id]))
  storeStates.value = storeStates.value.map((state) => ({
    ...state,
    installed: installedMap.has(state.item.name),
    installedId: installedMap.get(state.item.name) ?? null,
  }))
}

async function loadManifest() {
  const source = activeSource.value
  if (!source) {
    manifestError.value = '未找到可用 Skill 源'
    return
  }
  loadingManifest.value = true
  manifestError.value = ''
  try {
    const res: any = await skillApi.storeManifest(source.baseUrl)
    const data = res?.data || res
    const list = Array.isArray(data?.skills) ? data.skills : []
    storeStates.value = list.map((item: ManifestSkill, index: number) => buildStoreState(item, index))
    batchTotal.value = 0
    batchProcessed.value = 0
    batchFailed.value = 0
  } catch (error: any) {
    storeStates.value = []
    manifestError.value = getErrorMessage(error, 'Skill 中心加载失败')
  } finally {
    loadingManifest.value = false
  }
}

function onSourceChange(value: any) {
  activeSourceId.value = String(value || DEFAULT_SOURCE_ID)
  setActiveSourceId(activeSourceId.value)
  loadManifest()
}

function toggleSelect(state: StoreItemState) {
  if (batchRunning.value) return
  if (state.status === 'installing' || state.status === 'uninstalling') return
  state.selected = !state.selected
}

function onCheckboxChange(state: StoreItemState, value: boolean) {
  if (batchRunning.value) return
  state.selected = value
}

function toggleSelectAll(value: boolean | (string | number | boolean)[]) {
  if (batchRunning.value) return
  const checked = typeof value === 'boolean' ? value : false
  filteredCenterSkills.value.forEach((state) => {
    if (state.status === 'installing' || state.status === 'uninstalling') return
    state.selected = checked
  })
}

async function handleInstall(state: StoreItemState) {
  if (!state.zipPath) {
    Message.warning('该 Skill 缺少安装包地址')
    return
  }
  state.status = 'installing'
  state.error = ''
  try {
    await skillApi.storeImport({
      baseUrl: activeSource.value?.baseUrl || '',
      zipPath: state.zipPath,
      sha256: state.sha256 || undefined,
    })
    state.status = 'ok'
    state.installed = true
    await loadSkills()
    Message.success(`已安装 ${state.displayName}`)
  } catch (error: any) {
    state.status = 'error'
    state.error = getErrorMessage(error, '安装失败')
    Message.error(state.error)
  }
}

async function handleBatchInstall() {
  const targets = storeStates.value.filter((item) => item.selected && !item.installed)
  if (targets.length === 0) return
  batchRunning.value = true
  batchTotal.value = targets.length
  batchProcessed.value = 0
  batchFailed.value = 0
  for (const state of targets) {
    state.status = 'installing'
    state.error = ''
    try {
      await skillApi.storeImport({
        baseUrl: activeSource.value?.baseUrl || '',
        zipPath: state.zipPath,
        sha256: state.sha256 || undefined,
      })
      state.status = 'ok'
      state.installed = true
    } catch (error: any) {
      state.status = 'error'
      state.error = getErrorMessage(error, '安装失败')
      batchFailed.value += 1
    }
    batchProcessed.value += 1
  }
  batchRunning.value = false
  await loadSkills()
  Message.info(`安装完成：成功 ${batchTotal.value - batchFailed.value}，失败 ${batchFailed.value}`)
}

async function handleBatchUninstall() {
  const targets = storeStates.value.filter((item) => item.selected && item.installed && item.installedId != null)
  if (targets.length === 0) return
  Modal.warning({
    title: '确认卸载',
    content: `确定要卸载已选中的 ${targets.length} 个已安装 Skill 吗？`,
    hideCancel: false,
    onOk: async () => {
      batchRunning.value = true
      batchTotal.value = targets.length
      batchProcessed.value = 0
      batchFailed.value = 0
      for (const state of targets) {
        state.status = 'uninstalling'
        state.error = ''
        try {
          await skillApi.delete(state.installedId!)
          state.status = 'ok'
          state.installed = false
          state.installedId = null
        } catch (error: any) {
          state.status = 'error'
          state.error = getErrorMessage(error, '卸载失败')
          batchFailed.value += 1
        }
        batchProcessed.value += 1
      }
      batchRunning.value = false
      await loadSkills()
      Message.info(`卸载完成：成功 ${batchTotal.value - batchFailed.value}，失败 ${batchFailed.value}`)
    },
  })
}

async function openReadme(state: StoreItemState) {
  if (!state.readmePath || !activeSource.value) return
  showReadme.value = true
  loadingReadme.value = true
  currentReadmeTitle.value = state.displayName
  currentReadmeContent.value = ''
  try {
    const source = activeSource.value
    const res: any = await skillApi.storeReadme(source.baseUrl, state.readmePath)
    currentReadmeContent.value = typeof res === 'string' ? res : (res?.data || res)
  } catch (error: any) {
    currentReadmeContent.value = `README 加载失败：${getErrorMessage(error, '未知错误')}`
  } finally {
    loadingReadme.value = false
  }
}

async function handleToggle(skill: SkillRecord, enabled: boolean) {
  const previous = skill.enabled !== false
  skill.enabled = enabled
  try {
    if (enabled) {
      await skillApi.enable(skill.id)
    } else {
      await skillApi.disable(skill.id)
    }
    Message.success(enabled ? '已启用' : '已禁用')
  } catch (error: any) {
    skill.enabled = previous
    Message.error(getErrorMessage(error, '状态更新失败'))
  }
}

async function handleViewContent(skill: SkillRecord) {
  try {
    const res: any = await skillApi.getContent(skill.id)
    currentSkillContent.value = res?.data || res
    showContentModal.value = true
  } catch (error: any) {
    Message.error(getErrorMessage(error, '获取 Skill 内容失败'))
  }
}

function handleUninstall(skill: SkillRecord) {
  Modal.confirm({
    title: '确认卸载',
    content: `确定要卸载技能「${skill.name}」吗？`,
    onOk: async () => {
      try {
        await skillApi.delete(skill.id)
        Message.success('已卸载')
        await loadSkills()
      } catch (error: any) {
        Message.error(getErrorMessage(error, '卸载失败'))
      }
    },
  })
}

function openAddSourceForm() {
  editingSourceId.value = null
  sourceForm.name = ''
  sourceForm.baseUrl = ''
  sourceFormVisible.value = true
}

function openEditSourceForm(source: SkillSource) {
  editingSourceId.value = source.id
  sourceForm.name = source.name
  sourceForm.baseUrl = source.baseUrl
  sourceFormVisible.value = true
}

function cancelSourceForm() {
  editingSourceId.value = null
  sourceForm.name = ''
  sourceForm.baseUrl = ''
  sourceFormVisible.value = false
}

function saveSource() {
  const name = sourceForm.name.trim()
  const baseUrl = normalizeBaseUrl(sourceForm.baseUrl)
  if (!name) {
    Message.warning('请输入显示名称')
    return
  }
  if (!baseUrl) {
    Message.warning('请输入基础 URL')
    return
  }
  if (!/^https:\/\//i.test(baseUrl)) {
    Message.warning('基础 URL 必须为 HTTPS')
    return
  }

  if (editingSourceId.value) {
    customSources.value = customSources.value.map((item) => {
      if (item.id !== editingSourceId.value) return item
      return { ...item, name, baseUrl }
    })
  } else {
    customSources.value.push({
      id: `custom_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
      name,
      baseUrl,
    })
  }
  writeCustomSources(customSources.value)
  cancelSourceForm()
  ensureActiveSource()
  Message.success('源已保存')
}

function removeSource(id: string) {
  customSources.value = customSources.value.filter((item) => item.id !== id)
  writeCustomSources(customSources.value)
  if (activeSourceId.value === id) {
    activeSourceId.value = DEFAULT_SOURCE_ID
    setActiveSourceId(DEFAULT_SOURCE_ID)
  }
  ensureActiveSource()
  Message.success('源已删除')
}

async function handleGitImport() {
  if (!gitForm.url.trim()) {
    Message.warning('请输入 Git 仓库地址')
    return false
  }
  try {
    const res: any = await skillApi.importGit({ url: gitForm.url.trim(), branch: gitForm.branch.trim() || 'main' })
    const payload = res?.data || res
    const count = payload?.count || (Array.isArray(payload?.skills) ? payload.skills.length : 1)
    Message.success(`导入成功${count ? `，共 ${count} 个 Skill` : ''}`)
    gitForm.url = ''
    gitForm.branch = 'main'
    showGitImport.value = false
    await loadSkills()
  } catch (error: any) {
    Message.error(getErrorMessage(error, '导入失败'))
  }
  return false
}

async function handleUpload(option: any) {
  const file = option.fileItem?.file
  if (!file) return
  try {
    const res: any = await skillApi.upload(file)
    const payload = res?.data || res
    const count = payload?.count || (Array.isArray(payload?.skills) ? payload.skills.length : 1)
    Message.success(`上传成功${count ? `，共 ${count} 个 Skill` : ''}`)
    showUpload.value = false
    await loadSkills()
  } catch (error: any) {
    Message.error(getErrorMessage(error, '上传失败'))
  }
}

watch(showSkillCenter, async (visible) => {
  if (!visible) return
  await ensureStoreConfig()
  activeSourceId.value = getActiveSourceId()
  ensureActiveSource()
  await loadManifest()
})

onMounted(async () => {
  loadSources()
  activeSourceId.value = getActiveSourceId()
  await ensureStoreConfig()
  ensureActiveSource()
  await loadSkills()
})
</script>

<style scoped>
.skills-manage {
  max-width: 1400px;
  margin: 0 auto;
}

.skills-spin {
  display: block;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 24px;
}

.page-header h2 {
  margin: 0 0 6px;
}

.page-description {
  margin: 0;
  color: var(--color-text-2);
}

.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

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

.skill-card.disabled {
  opacity: 0.72;
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

.skill-meta {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--color-text-3);
  margin-bottom: 12px;
}

.skill-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid var(--color-border);
}

.skill-store {
  display: flex;
  flex-direction: column;
  height: min(680px, calc(100vh - 180px));
  min-height: 340px;
}

.store-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.select-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px 0;
  border-bottom: 1px solid var(--color-border-2);
  margin-bottom: 12px;
}

.selected-count {
  color: var(--color-text-3);
  font-size: 13px;
}

.store-content-spin {
  display: block;
  flex: 1;
  min-height: 0;
}

.store-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
  gap: 12px;
  color: var(--color-text-3);
}

.empty-text {
  margin: 0;
  font-size: 14px;
}

.store-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(260px, 100%), 1fr));
  gap: 12px;
  height: 100%;
  overflow-y: auto;
  padding: 4px;
}

.store-card {
  background: var(--color-bg-2);
  border: 2px solid var(--color-border-2);
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.15s;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.store-card:hover {
  border-color: var(--color-primary-light-2);
}

.store-card.selected {
  border-color: rgb(var(--primary-6));
  background: var(--color-fill-1);
}

.store-card.installed {
  background: var(--color-fill-1);
}

.store-card.busy {
  opacity: 0.72;
  pointer-events: none;
}

.store-card.error {
  border-color: rgb(var(--danger-6));
}

.card-top {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.card-title {
  font-weight: 600;
  font-size: 14px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-desc {
  font-size: 12px;
  color: var(--color-text-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 32px;
}

.card-meta {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: var(--color-text-3);
  flex-wrap: wrap;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.card-tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 4px;
  font-size: 12px;
}

.status-indicator {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--color-text-3);
}

.status-indicator.ok {
  color: rgb(var(--success-6));
}

.status-indicator.err {
  color: rgb(var(--danger-6));
}

.store-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding-top: 12px;
  margin-top: 12px;
  border-top: 1px solid var(--color-border-2);
  flex-wrap: wrap;
}

.footer-right {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.readme-content,
.content-body {
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--color-fill-2);
  padding: 16px;
  border-radius: 4px;
  font-size: 13px;
  max-height: 500px;
  overflow-y: auto;
  margin: 0;
}

.content-description {
  color: var(--color-text-2);
}

.source-manager {
  padding: 4px 0;
}

.section-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 8px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.default-source {
  background: var(--color-fill-2);
  border-radius: 6px;
  padding: 12px;
}

.source-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
  margin-bottom: 4px;
}

.source-url {
  font-size: 12px;
  color: var(--color-text-3);
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.source-hint,
.empty-custom,
.form-tip {
  font-size: 12px;
  color: var(--color-text-3);
}

.source-form {
  margin-bottom: 16px;
}

.custom-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.custom-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  border: 1px solid var(--color-border-2);
  border-radius: 6px;
  padding: 12px;
}

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
</style>
