<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listWhiteBgImages, generateWhiteBg, deleteFile } from '../api/aiImage'
import type { WhiteBgImage } from '../types'

const props = defineProps<{ productDir: string }>()
const emit = defineEmits<{ (e: 'add'): void }>()
const images = ref<WhiteBgImage[]>([])
const loading = ref(false)
const timer = ref(0)

const imgUrl = (img: WhiteBgImage) =>
  `/api/ai-image/image-file?path=${encodeURIComponent(img.path)}&t=${Date.now()}`

onMounted(async () => {
  try {
    const data = await listWhiteBgImages(props.productDir)
    images.value = data.images || []
  } catch (e) {}
})

async function doGenerate() {
  if (loading.value) return
  loading.value = true
  timer.value = 0
  const interval = setInterval(() => timer.value++, 1000)
  try {
    await generateWhiteBg(props.productDir, true)
    const data = await listWhiteBgImages(props.productDir)
    images.value = data.images || []
  } catch (e: any) {
    alert('生成失败: ' + e.message)
  } finally {
    clearInterval(interval)
    loading.value = false
  }
}

async function doDelete(path: string) {
  if (!confirm('确定删除这张白底图吗？')) return
  try {
    const res = await deleteFile(path)
    if (res.success) {
      const data = await listWhiteBgImages(props.productDir)
      images.value = data.images || []
    }
  } catch (e: any) {
    alert('删除失败: ' + e.message)
  }
}
</script>
<template>
  <div class="wb-section" v-if="productDir">
    <div class="wb-header">
      <span class="wb-title">⬜ 白底图</span>
      <button class="wb-refresh" @click="doGenerate">🔄 重新生成</button>
    </div>
    <div v-if="loading" class="wb-loading">
      <span class="spinner"></span> 正在生成白底图... {{ timer }}s
    </div>
    <div v-else class="wb-grid">
      <div v-for="img in images" :key="img.path" class="wb-item">
        <img :src="imgUrl(img)" class="wb-img" />
        <div class="wb-name">{{ img.name }}</div>
        <button class="wb-del" @click="doDelete(img.path)">🗑️ 删除</button>
      </div>
      <div class="wb-add" @click="emit('add')">
        <div class="wb-add-box"><span class="wb-add-icon">+</span><span>添加图</span></div>
      </div>
    </div>
  </div>
</template>
<style scoped>
.wb-section { margin-top: 12px; padding-top: 12px; border-top: 1px solid #d6e4ff; }
.wb-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.wb-title { font-size: 12px; font-weight: 600; color: #1d39c4; }
.wb-refresh { font-size: 11px; padding: 2px 10px; border: 1px solid #1d39c4; border-radius: 4px; background: #fff; color: #1d39c4; cursor: pointer; }
.wb-loading { text-align: center; padding: 15px; color: #999; font-size: 12px; }
.wb-grid { display: flex; gap: 8px; flex-wrap: wrap; }
.wb-item { text-align: center; }
.wb-img { width: 100px; height: 100px; object-fit: cover; border-radius: 6px; border: 1px solid #e0e0e0; cursor: pointer; }
.wb-name { font-size: 10px; color: #999; margin-top: 2px; }
.wb-del { margin-top: 4px; padding: 2px 8px; border: 1px solid #ff4d4f; border-radius: 4px; font-size: 10px; background: #fff; color: #ff4d4f; cursor: pointer; }
.wb-add { text-align: center; cursor: pointer; }
.wb-add-box { width: 100px; height: 100px; border: 2px dashed #d9d9d9; border-radius: 8px; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #999; font-size: 12px; background: #fafafa; }
.wb-add-icon { font-size: 24px; }
.spinner { display: inline-block; width: 14px; height: 14px; border: 2px solid #e0e0e0; border-top-color: #1d39c4; border-radius: 50%; animation: spin 0.8s linear infinite; vertical-align: middle; margin-right: 6px; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
