<script setup lang="ts">
import { ref, watch, inject } from 'vue'
import { useAiState } from '../composables/useAiState'
import { saveRefCache, getRefCache, listRefImages, deleteFile } from '../api/aiImage'
import UploadModal from './UploadModal.vue'

const { productDir, refImages } = useAiState()
const refResults = ref<{ path: string; name: string }[]>([])
const uploadRef = ref<InstanceType<typeof UploadModal>>()
const previewImage = inject<(url: string) => void>("previewImage") || ((u: string) => { window.open(u, "_blank"); })

function getResultUrl(path: string) { return "/api/ai-image/image-file?path=" + encodeURIComponent(path) + "&t=" + Date.now() }

watch(productDir, async (dir) => { if (!dir) return; await loadExisting() }, { immediate: true })

async function loadExisting() {
  if (!productDir.value) return
  try {
    const cache = await getRefCache(productDir.value)
    if (cache.success && cache.images?.length) { refImages.value = cache.images }
  } catch {}
  try {
    const data = await listRefImages(productDir.value)
    if (data.success && data.images) { refResults.value = data.images }
  } catch {}
}

function openUpload() { uploadRef.value?.open() }
function onUploadConfirm(data: string) {
  refImages.value.push(data)
  saveRefCache(productDir.value, refImages.value).catch(() => {})
}
function removeImage(idx: number) {
  refImages.value.splice(idx, 1)
  saveRefCache(productDir.value, refImages.value).catch(() => {})
}

async function deleteResult(idx: number, filePath: string) {
  if (!confirm('确定删除该参考图吗？')) return
  try {
    await deleteFile(filePath)
    refResults.value.splice(idx, 1)
    await loadExisting()
  } catch (e: any) { console.error(e) }
}
</script>

<template>
  <div class="ref-section">
    <div class="section-title">📷 参考图</div>
    <div class="ref-grid">
      <div v-for="(img, idx) in refImages" :key="idx" class="ref-item">
        <div class="ref-img-wrap"><img :src="img" class="ref-img" @click="previewImage(img)" /></div>
        <button class="btn-remove" @click="removeImage(idx)">删除</button>
      </div>
      <div class="ref-add" @click="openUpload"><div class="add-box"><span>+</span><span>添加图</span></div></div>
    </div>
    <div v-if="refResults.length > 0" class="results-section">
      <div class="results-title">🖼️ 已生成结果</div>
      <div class="results-grid">
        <div v-for="(r, ri) in refResults" :key="ri" class="result-item">
          <img :src="getResultUrl(r.path)" class="result-img" @click="previewImage(getResultUrl(r.path))" />
          <div class="result-name">{{ r.name }}</div>
          <button class="btn-delete-result" @click="deleteResult(ri, r.path)">🗑️ 删除</button>
        </div>
      </div>
    </div>
    <UploadModal ref="uploadRef" @confirm="onUploadConfirm" />
  </div>
</template>

<style scoped>
.ref-section { padding: 10px 0; }
.section-title { font-size: 13px; font-weight: 600; margin-bottom: 12px; }
.ref-grid { display: flex; gap: 10px; flex-wrap: wrap; }
.ref-item { text-align: center; }
.ref-img-wrap { width: 120px; height: 120px; overflow: hidden; border-radius: 8px; border: 1px solid #e0e0e0; }
.ref-img { width: 100%; height: 100%; object-fit: cover; cursor: pointer; }
.btn-remove { margin-top: 4px; padding: 2px 8px; border: 1px solid #ff4d4f; border-radius: 4px; font-size: 10px; background: #fff; color: #ff4d4f; cursor: pointer; }
.ref-add { cursor: pointer; }
.add-box { width: 120px; height: 120px; border: 2px dashed #d9d9d9; border-radius: 8px; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #999; background: #fafafa; }
.results-section { margin-top: 16px; border-top: 1px solid #eee; padding-top: 12px; }
.results-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; }
.results-grid { display: flex; gap: 10px; flex-wrap: wrap; }
.result-item { text-align: center; }
.result-img { width: 100px; height: 100px; object-fit: cover; border-radius: 6px; border: 1px solid #e0e0e0; cursor: pointer; }
.result-name { font-size: 10px; color: #999; margin-top: 2px; }
.btn-delete-result { padding: 2px 6px; border: 1px solid #ff4d4f; border-radius: 4px; font-size: 10px; cursor: pointer; background: #fff; color: #ff4d4f; }
</style>
