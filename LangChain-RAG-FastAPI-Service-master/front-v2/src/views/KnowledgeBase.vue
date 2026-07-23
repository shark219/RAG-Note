<template>
  <div class="knowledge-base">
    <div class="kb-header">
      <h2>知识库管理</h2>
      <a-space>
        <a-upload
          :show-file-list="false"
          :custom-request="handleUpload"
          accept=".pdf,.doc,.docx,.txt,.md"
        >
          <a-button type="primary">
            <template #icon><icon-upload /></template>
            上传文档
          </a-button>
        </a-upload>
        <a-button @click="handleClean">
          <template #icon><icon-delete /></template>
          清空向量库
        </a-button>
      </a-space>
    </div>

    <a-spin :loading="loading">
      <a-table :data="documents" :pagination="false">
        <template #columns>
          <a-table-column title="文件名" data-index="filename" />
          <a-table-column title="MD5" data-index="md5">
            <template #cell="{ record }">
              <a-tag>{{ record.md5?.slice(0, 8) }}...</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="切片数" data-index="chunkCount" />
          <a-table-column title="上传时间" data-index="createdAt">
            <template #cell="{ record }">
              {{ formatDate(record.createdAt) }}
            </template>
          </a-table-column>
          <a-table-column title="状态" data-index="status">
            <template #cell="{ record }">
              <a-tag :color="getStatusColor(record.status)">
                {{ getStatusText(record.status) }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="操作">
            <template #cell="{ record }">
              <a-space>
                <a-button type="text" size="small" @click="handleViewChunks(record)">
                  查看切片
                </a-button>
                <a-button type="text" size="small" status="danger" @click="handleDelete(record)">
                  删除
                </a-button>
              </a-space>
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-spin>

    <!-- 切片详情弹窗 -->
    <a-modal v-model:visible="chunkModalVisible" title="文档切片" :width="700" :footer="false">
      <div v-if="currentDoc" class="chunk-list">
        <div v-for="chunk in chunks" :key="chunk.chunk_id" class="chunk-item">
          <div class="chunk-header">
            <a-tag>切片 {{ chunk.index }}</a-tag>
            <span class="chunk-id">{{ chunk.chunk_id }}</span>
          </div>
          <div class="chunk-content">{{ chunk.content }}</div>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { IconUpload, IconDelete } from '@arco-design/web-vue/es/icon'
import { knowledgeApi } from '@/api'
import dayjs from 'dayjs'

const loading = ref(false)
const documents = ref<any[]>([])
const chunkModalVisible = ref(false)
const currentDoc = ref<any>(null)
const chunks = ref<any[]>([])

onMounted(() => {
  fetchDocuments()
})

async function fetchDocuments() {
  loading.value = true
  try {
    const res: any = await knowledgeApi.list()
    documents.value = res || []
  } catch (e) {
    console.error('获取文档列表失败', e)
  } finally {
    loading.value = false
  }
}

async function handleUpload(option: any) {
  const file = option.fileItem.file
  loading.value = true
  try {
    await knowledgeApi.upload(file)
    Message.success('上传成功')
    fetchDocuments()
  } catch (e) {
    Message.error('上传失败')
  } finally {
    loading.value = false
  }
}

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

async function handleViewChunks(doc: any) {
  currentDoc.value = doc
  chunkModalVisible.value = true
  // 假设 API 返回切片数据
  chunks.value = doc.chunks || []
}

async function handleDelete(doc: any) {
  Message.info('删除功能开发中')
}

function formatDate(date: string) {
  return dayjs(date).format('YYYY-MM-DD HH:mm')
}

function getStatusColor(status: string) {
  const map: Record<string, string> = {
    completed: 'green',
    processing: 'orange',
    vector_failed: 'red',
  }
  return map[status] || 'gray'
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    completed: '已完成',
    processing: '处理中',
    vector_failed: '向量化失败',
  }
  return map[status] || status
}
</script>

<style scoped>
.knowledge-base {
  max-width: 1200px;
  margin: 0 auto;
}

.kb-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.kb-header h2 {
  margin: 0;
}

.chunk-list {
  max-height: 500px;
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
}
</style>
