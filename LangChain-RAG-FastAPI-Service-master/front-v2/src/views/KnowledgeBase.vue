<template>
  <div class="knowledge-base">
    <div class="kb-header">
      <h2>知识库管理</h2>
      <a-space wrap>
        <a-button type="primary" @click="triggerUpload">
          <template #icon><icon-upload /></template>
          上传文档
        </a-button>
        <a-button @click="handleClean">
          <template #icon><icon-delete /></template>
          清空向量库
        </a-button>
      </a-space>
    </div>

    <!-- 拖拽上传区域 -->
    <div
      class="upload-zone"
      :class="{ 'upload-zone--active': isDragging }"
      @dragenter.prevent="onDragEnter"
      @dragover.prevent="onDragOver"
      @dragleave.prevent="onDragLeave"
      @drop.prevent="onDrop"
    >
      <input
        ref="fileInputRef"
        type="file"
        multiple
        accept=".pdf,.doc,.docx,.txt,.md"
        style="display: none"
        @change="onFileSelected"
      />
      <div v-if="isDragging" class="upload-zone__hint">
        <icon-upload style="font-size: 32px" />
        <p>松开鼠标上传文件</p>
      </div>
      <div v-else class="upload-zone__hint upload-zone__hint--idle">
        <icon-upload style="font-size: 20px; opacity: 0.5" />
        <p>拖拽文件到此处上传，或点击上方"上传文档"按钮</p>
      </div>
    </div>

    <!-- 上传进度面板 -->
    <div v-if="uploadState.uploading" class="upload-progress">
      <div class="upload-progress__header">
        <span>正在上传 {{ uploadState.total }} 个文件</span>
        <a-button type="text" size="small" @click="cancelUpload">取消</a-button>
      </div>
      <div v-for="(file, idx) in uploadState.files" :key="idx" class="upload-file-item">
        <div class="upload-file-item__name">{{ file.name }}</div>
        <a-progress
          :percent="file.progress / 100"
          :status="file.status === 'error' ? 'danger' : file.status === 'done' ? 'success' : 'normal'"
          :show-text="false"
          size="small"
        />
        <div class="upload-file-item__status">
          <span v-if="file.status === 'done'" class="status-done">完成</span>
          <span v-else-if="file.status === 'error'" class="status-error">{{ file.error || '失败' }}</span>
          <span v-else class="status-processing">{{ file.message || '处理中...' }}</span>
        </div>
      </div>
    </div>

    <!-- 文档列表 -->
    <a-spin :loading="loading" :style="{ width: '100%', flex: 1, minHeight: 0 }">
      <a-table
        v-if="documents.length > 0 || loading"
        :data="documents"
        :pagination="pagination"
        :bordered="false"
        :stripe="true"
        class="kb-table"
      >
        <template #columns>
          <a-table-column title="文件名" data-index="filename" :width="200" ellipsis tooltip />
          <a-table-column title="MD5" :width="120">
            <template #cell="{ record }">
              <a-tag size="small">{{ record.md5?.slice(0, 8) || '-' }}...</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="切片数" data-index="chunkCount" :width="80" align="center" />
          <a-table-column title="上传时间" :width="160">
            <template #cell="{ record }">
              {{ formatDate(record.createdAt) }}
            </template>
          </a-table-column>
          <a-table-column title="状态" :width="100" align="center">
            <template #cell="{ record }">
              <a-tag :color="getStatusColor(record.status)" size="small">
                {{ getStatusText(record.status) }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="操作" :width="280" fixed="right">
            <template #cell="{ record }">
              <a-space :size="4">
                <a-button type="text" size="mini" @click="handleViewDetail(record)">
                  详情
                </a-button>
                <a-button type="text" size="mini" @click="handleViewChunks(record)">
                  切片
                </a-button>
                <a-button
                  v-if="record.status === 'vector_failed'"
                  type="text"
                  size="mini"
                  status="warning"
                  @click="handleRetry(record)"
                >
                  重试
                </a-button>
                <a-button type="text" size="mini" @click="handleViewImages(record)">
                  图片
                </a-button>
                <a-button type="text" size="mini" status="danger" @click="handleDelete(record)">
                  删除
                </a-button>
              </a-space>
            </template>
          </a-table-column>
        </template>
      </a-table>
      <a-empty v-if="!loading && documents.length === 0" description="暂无文档，请上传文件" style="padding: 40px 0" />
    </a-spin>

    <!-- 文档详情弹窗 -->
    <a-modal v-model:visible="detailModalVisible" title="文档详情" :width="800" :footer="false">
      <a-spin :loading="detailLoading">
        <div v-if="documentDetail" class="detail-content">
          <a-descriptions :column="2" bordered size="small">
            <a-descriptions-item label="文件名">{{ documentDetail.filename }}</a-descriptions-item>
            <a-descriptions-item label="切片数">{{ documentDetail.chunkCount }}</a-descriptions-item>
            <a-descriptions-item label="状态">
              <a-tag :color="getStatusColor(documentDetail.status)" size="small">
                {{ getStatusText(documentDetail.status) }}
              </a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="上传时间">{{ formatDate(documentDetail.createdAt) }}</a-descriptions-item>
          </a-descriptions>
          <div v-if="documentDetail.preview" class="detail-preview">
            <h4>内容预览</h4>
            <div class="detail-preview__text">{{ documentDetail.preview }}</div>
          </div>
        </div>
      </a-spin>
    </a-modal>

    <!-- 切片列表弹窗 -->
    <a-modal v-model:visible="chunkModalVisible" title="文档切片" :width="800" :footer="false">
      <a-spin :loading="chunkLoading">
        <div v-if="chunks.length > 0" class="chunk-list">
          <div v-for="chunk in chunks" :key="chunk.chunk_id" class="chunk-item">
            <div class="chunk-header">
              <a-tag size="small">切片 {{ chunk.index ?? chunk.chunk_id }}</a-tag>
              <span class="chunk-id">{{ chunk.chunk_id }}</span>
              <a-tag v-if="chunk.score !== undefined" size="small" color="arcoblue">
                相似度: {{ (chunk.score * 100).toFixed(1) }}%
              </a-tag>
            </div>
            <div class="chunk-content">{{ chunk.content }}</div>
          </div>
        </div>
        <a-empty v-else description="暂无切片数据" />
      </a-spin>
    </a-modal>

    <!-- 图片查看弹窗 -->
    <a-modal v-model:visible="imageModalVisible" title="文档图片" :width="900" :footer="false">
      <a-spin :loading="imageLoading">
        <div v-if="images.length > 0" class="image-grid">
          <div v-for="(img, idx) in images" :key="idx" class="image-item">
            <img :src="img.url || img.base64" :alt="img.filename || `图片${idx + 1}`" />
            <div class="image-item__name">{{ img.filename || `图片 ${idx + 1}` }}</div>
          </div>
        </div>
        <a-empty v-else description="该文档暂无提取的图片" />
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { IconUpload, IconDelete } from '@arco-design/web-vue/es/icon'
import { knowledgeApi } from '@/api'
import dayjs from 'dayjs'

// ========== 基础状态 ==========
const loading = ref(false)
const documents = ref<any[]>([])
const pagination = reactive({
  pageSize: 20,
  showTotal: true,
  showPageSize: true,
})

// ========== 拖拽上传 ==========
const isDragging = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
let dragCounter = 0

// ========== 上传进度 ==========
const uploadState = reactive({
  uploading: false,
  total: 0,
  files: [] as Array<{
    name: string
    progress: number
    status: 'pending' | 'processing' | 'done' | 'error'
    message: string
    error: string
  }>,
})

// ========== 文档详情弹窗 ==========
const detailModalVisible = ref(false)
const detailLoading = ref(false)
const documentDetail = ref<any>(null)

// ========== 切片弹窗 ==========
const chunkModalVisible = ref(false)
const chunkLoading = ref(false)
const chunks = ref<any[]>([])

// ========== 图片弹窗 ==========
const imageModalVisible = ref(false)
const imageLoading = ref(false)
const images = ref<any[]>([])

// ========== 生命周期 ==========
onMounted(() => {
  fetchDocuments()
})

// ========== 文档列表 ==========
async function fetchDocuments() {
  loading.value = true
  try {
    const res: any = await knowledgeApi.list()
    const data = res?.data || res
    documents.value = data?.documents || []
  } catch (e: any) {
    console.error('获取文档列表失败', e)
    Message.error('获取文档列表失败: ' + (e?.response?.data?.message || e?.message || '未知错误'))
    documents.value = []
  } finally {
    loading.value = false
  }
}

// ========== 拖拽上传 ==========
function onDragEnter() {
  dragCounter++
  isDragging.value = true
}

function onDragOver() {
  // required for drop to work
}

function onDragLeave() {
  dragCounter--
  if (dragCounter <= 0) {
    isDragging.value = false
    dragCounter = 0
  }
}

function onDrop(event: DragEvent) {
  isDragging.value = false
  dragCounter = 0
  const files = event.dataTransfer?.files
  if (files && files.length > 0) {
    const fileList = Array.from(files).filter((f) => {
      const ext = f.name.toLowerCase()
      return ext.endsWith('.pdf') || ext.endsWith('.doc') || ext.endsWith('.docx') || ext.endsWith('.txt') || ext.endsWith('.md')
    })
    if (fileList.length === 0) {
      Message.warning('仅支持 .pdf, .doc, .docx, .txt, .md 格式')
      return
    }
    startUpload(fileList)
  }
}

function triggerUpload() {
  fileInputRef.value?.click()
}

function onFileSelected(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    startUpload(Array.from(input.files))
    input.value = ''
  }
}

// ========== SSE 流式上传 ==========
let abortController: AbortController | null = null

async function startUpload(files: File[]) {
  if (files.length === 0) return

  uploadState.uploading = true
  uploadState.total = files.length
  uploadState.files = files.map((f) => ({
    name: f.name,
    progress: 0,
    status: 'pending' as const,
    message: '等待上传...',
    error: '',
  }))

  abortController = new AbortController()

  try {
    const response = await knowledgeApi.uploadMultipleStream(files)

    if (!response.ok) {
      throw new Error(`上传失败: HTTP ${response.status}`)
    }

    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('无法读取响应流')
    }

    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const jsonStr = line.slice(5).trim()
          if (!jsonStr) continue
          try {
            const event = JSON.parse(jsonStr)
            handleSseEvent(event)
          } catch (e) {
            // ignore parse errors for non-JSON data
          }
        }
      }
    }

    // 上传完成后刷新列表
    await fetchDocuments()
  } catch (e: any) {
    if (e.name !== 'AbortError') {
      Message.error('上传失败: ' + (e.message || '未知错误'))
    }
  } finally {
    uploadState.uploading = false
    abortController = null
  }
}

function handleSseEvent(event: any) {
  const { event_type, filename, message, progress, success_count, failed_count, skipped_count } = event

  // 全局完成事件
  if (event_type === 'finish') {
    const parts = []
    if (success_count > 0) parts.push(`${success_count} 个成功`)
    if (skipped_count > 0) parts.push(`${skipped_count} 个已存在`)
    if (failed_count > 0) parts.push(`${failed_count} 个失败`)
    if (parts.length > 0) {
      Message.info(`上传完成：${parts.join('，')}`)
    }
    return
  }

  // 找到对应的文件
  const fileIdx = uploadState.files.findIndex((f) => f.name === filename)
  if (fileIdx >= 0) {
    const fileItem = uploadState.files[fileIdx]

    if (event_type === 'loading' || event_type === 'splitting' || event_type === 'storing') {
      // 同步处理阶段：立即显示处理中状态，不再显示"等待上传"
      fileItem.status = 'processing'
      fileItem.message = event_type === 'loading' ? '读取文件中...'
        : event_type === 'splitting' ? '文本切片中...'
        : '存储到数据库...'
    } else if (event_type === 'processing' || event_type === 'vectorizing') {
      fileItem.status = 'processing'
      fileItem.message = message || '向量化中...'
      if (progress !== undefined) {
        fileItem.progress = progress
      }
    } else if (event_type === 'completed') {
      fileItem.status = 'done'
      fileItem.progress = 100
      fileItem.message = '完成'
    } else if (event_type === 'skipped') {
      fileItem.status = 'done'
      fileItem.progress = 100
      fileItem.message = '已存在，跳过'
    } else if (event_type === 'error') {
      fileItem.status = 'error'
      fileItem.error = message || '处理失败'
    }
  }
}

function cancelUpload() {
  abortController?.abort()
  uploadState.uploading = false
}

// ========== 文档详情 ==========
async function handleViewDetail(doc: any) {
  detailModalVisible.value = true
  detailLoading.value = true
  documentDetail.value = null

  try {
    const res: any = await knowledgeApi.getDetail(doc.filename)
    documentDetail.value = res?.data || res || doc
  } catch (e) {
    console.error('获取文档详情失败', e)
    documentDetail.value = doc
  } finally {
    detailLoading.value = false
  }
}

// ========== 切片查看 ==========
async function handleViewChunks(doc: any) {
  chunkModalVisible.value = true
  chunkLoading.value = true
  chunks.value = []

  try {
    const res: any = await knowledgeApi.getChunks(doc.filename)
    const data = res?.data || res
    chunks.value = data?.chunks || []
  } catch (e) {
    console.error('获取切片失败', e)
    Message.error('获取切片列表失败')
  } finally {
    chunkLoading.value = false
  }
}

// ========== 图片查看 ==========
async function handleViewImages(doc: any) {
  imageModalVisible.value = true
  imageLoading.value = true
  images.value = []

  try {
    // 需要从文档详情中获取 md5
    let md5 = doc.md5
    if (!md5) {
      const detailRes: any = await knowledgeApi.getDetail(doc.filename)
      const detailData = detailRes?.data || detailRes
      md5 = detailData?.md5
    }

    if (!md5) {
      Message.warning('无法获取文档MD5，无法加载图片')
      imageLoading.value = false
      return
    }

    const res: any = await knowledgeApi.getBatchImages(md5)
    const data = res?.data || res
    images.value = data?.images || []
  } catch (e) {
    console.error('获取图片失败', e)
  } finally {
    imageLoading.value = false
  }
}

// ========== 文档删除 ==========
async function handleDelete(doc: any) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除文档「${doc.filename || doc.originalFilename}」吗？此操作不可恢复。`,
    onOk: async () => {
      try {
        await knowledgeApi.deleteByFilename(doc.filename)
        Message.success('删除成功')
        fetchDocuments()
      } catch (e) {
        Message.error('删除失败')
      }
    },
  })
}

// ========== 重试向量化 ==========
async function handleRetry(doc: any) {
  try {
    await knowledgeApi.retryVectorization(doc.id)
    Message.success('重试任务已提交')
    fetchDocuments()
  } catch (e) {
    Message.error('重试失败')
  }
}

// ========== 清空向量库 ==========
async function handleClean() {
  Modal.confirm({
    title: '确认清空',
    content: '确定要清空所有向量数据吗？此操作不可恢复。',
    onOk: async () => {
      try {
        await knowledgeApi.clean()
        Message.success('清空成功')
        fetchDocuments()
      } catch (e) {
        Message.error('清空失败')
      }
    },
  })
}

// ========== 工具函数 ==========
function formatDate(date: string) {
  if (!date) return '-'
  return dayjs(date).format('YYYY-MM-DD HH:mm')
}

function getStatusColor(status: string) {
  const map: Record<string, string> = {
    completed: 'green',
    processing: 'orange',
    vector_failed: 'red',
    unknown: 'gray',
  }
  return map[status] || 'gray'
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    completed: '已完成',
    processing: '处理中',
    vector_failed: '向量化失败',
    unknown: '状态未知',
  }
  return map[status] || status || '-'
}
</script>

<style scoped>
.knowledge-base {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.kb-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}

.kb-header h2 {
  margin: 0;
  font-size: 18px;
}

/* 拖拽上传区域 */
.upload-zone {
  border: 2px dashed var(--color-border);
  border-radius: 8px;
  padding: 20px;
  text-align: center;
  transition: all 0.2s;
  flex-shrink: 0;
}

.upload-zone--active {
  border-color: var(--color-primary);
  background: var(--color-primary-light-1);
}

.upload-zone__hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--color-text-3);
  font-size: 14px;
}

.upload-zone__hint--idle {
  font-size: 13px;
}

.upload-zone__hint p {
  margin: 0;
}

/* 上传进度面板 */
.upload-progress {
  background: var(--color-bg-1);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 16px;
  flex-shrink: 0;
}

.upload-progress__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  font-weight: 500;
}

.upload-file-item {
  margin-bottom: 10px;
}

.upload-file-item__name {
  font-size: 13px;
  margin-bottom: 4px;
  color: var(--color-text-2);
}

.upload-file-item__status {
  font-size: 12px;
  margin-top: 2px;
}

.status-done {
  color: var(--color-green-6);
}

.status-error {
  color: var(--color-red-6);
}

.status-processing {
  color: var(--color-text-3);
}

/* 表格 */
.kb-table {
  flex: 1;
  min-height: 0;
  overflow-x: auto;
}

@media (max-width: 768px) {
  .kb-header {
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
  }

  .kb-header h2 {
    margin: 0;
  }
}

/* 详情弹窗 */
.detail-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-preview {
  margin-top: 8px;
}

.detail-preview h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.detail-preview__text {
  background: var(--color-fill-2);
  padding: 12px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  max-height: 400px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

/* 切片列表 */
.chunk-list {
  max-height: 600px;
  overflow-y: auto;
}

.chunk-item {
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  margin-bottom: 8px;
}

.chunk-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.chunk-id {
  font-size: 12px;
  color: var(--color-text-3);
}

.chunk-content {
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text-2);
  white-space: pre-wrap;
  word-break: break-all;
}

/* 图片网格 */
.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  max-height: 600px;
  overflow-y: auto;
}

.image-item {
  border: 1px solid var(--color-border);
  border-radius: 6px;
  overflow: hidden;
}

.image-item img {
  width: 100%;
  height: 160px;
  object-fit: cover;
  display: block;
}

.image-item__name {
  padding: 6px 8px;
  font-size: 12px;
  color: var(--color-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
