<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAiState } from '../composables/useAiState'
import { replaceImages, listReplaceResults, deleteFile } from '../api/aiImage'
import UploadModal from './UploadModal.vue'

const { productDir, selectedModel, modelList } = useAiState()

const userImages = ref<string[]>([])
const userPrompts = ref<string[]>([])
const results = ref<{ key: string; path: string }[]>([])
const loading = ref(false)
const status = ref('')

const uploadRef = ref<InstanceType<typeof UploadModal>>()
const uploadIdx = ref(-1)

watch(productDir, async (dir) => {
  if (dir) await loadExisting()
}, { immediate: true })

async function loadExisting() {
  if (!productDir.value) return
  try {
    const data = await listReplaceResults(productDir.value)
    if (data.success && data.images) {
      results.value = data.images.map((img, i) => ({
        key: '替换图' + (i + 1),
        path: img.path,
      }))
    }
  } catch (e) {}
}

function openUpload(idx: number) {
  uploadIdx.value = idx
  uploadRef.value?.open()
}

function onUploadConfirm(data: string) {
  if (uploadIdx.value === -1) {
    userImages.value.push(data)
    userPrompts.value.push('')
  } else {
    userImages.value[uploadIdx.value] = data
  }
}

function removeImage(idx: number) {
  userImages.value.splice(idx, 1)
  userPrompts.value.splice(idx, 1)
}

function updatePrompt(idx: number, val: string) {
  userPrompts.value[idx] = val
}

async function generate() {
  if (userImages.value.length === 0) { status.value = '⚠️ 请先添加图片'; return }
  const emptyPrompt = userPrompts.value.some(p => !p.trim())
  if (emptyPrompt) { status.value = '⚠️ 请填写所有追加提示词'; return }
  if (!productDir.value) return

  loading.value = true
  status.value = '正在生成...'
  try {
    const data = await replaceImages({
      productDir: productDir.value,
      images: userImages.value,
      prompts: userPrompts.value,
      model: selectedModel.value,
    })
    status.value = `✅ ${data.succeeded || 0} 成功`
    if (data.results) results.value = data.results
    await loadExisting()
  } catch (e: any) {
    status.value = '❌ ' + e.message
  }
  loading.value = false
}

async function regenerate(idx: number) {
  if (!userImages.value[idx]) return
  loading.value = true
  try {
    const data = await replaceImages({
      productDir: productDir.value,
      images: [userImages.value[idx]],
      prompts: [userPrompts.value[idx] || ''],
      model: selectedModel.value,
    })
    if (data.results?.[0]) {
      if (!results.value[idx]) results.value[idx] = data.results[0]
      else results.value[idx] = data.results[0]
      results.value = [...results.value]
    }
  } catch (e: any) {
    status.value = '❌ ' + e.message
  }
  loading.value = false
}

async function deleteResult(idx: number, filePath: string) {
  if (!confirm('确定删除该替换图吗？')) return
  try {
    await deleteFile(filePath)
    results.value.splice(idx, 1)
    await loadExisting()
  } catch (e: any) {
    status.value = '❌ ' + e.message
  }
}
</script>

<template>
  <div class="replace-section">
    <div class="section-title">🔄 替换图</div>

    <!-- 已上传的图 -->
    <div class="replace-grid">
      <div v-for="(img, idx) in userImages" :key="idx" class="replace-item">
        <div class="replace-img-wrap">
          <img :src="img" class="replace-img" @click="openUpload(idx)" />
        </div>
        <input v-model="userPrompts[idx]"
          @input="(e: any) => updatePrompt(idx, e.target.value)"
          placeholder="必填：输入替换要求" class="prompt-input" />
        <button class="btn-remove" @click="removeImage(idx)">删除</button>
      </div>
      <div class="replace-add" @click="openUpload(-1)">
        <div class="add-box"><span>+</span><span>添加图</span></div>
      </div>
    </div>

    <!-- 模型选择 -->
    <div class="model-select">
      <label>图生成ai模型：</label>
      <select v-model="selectedModel">
        <option v-for="m in modelList" :key="m.id" :value="m.id">{{ m.name }}</option>
        <option value="black-forest-labs/FLUX.1-schnell">FLUX.1 Schnell</option>
      </select>
    </div>

    <!-- 生成按钮 -->
    <div class="gen-bar">
      <span class="gen-status">{{ status }}</span>
      <button class="btn-gen" :disabled="loading" @click="generate">
        {{ loading ? '⏳ 生成中...' : '🚀 生成替换图' }}
      </button>
    </div>

    <!-- 结果展示 -->
    <div v-if="results.length > 0" class="results-section">
      <div class="results-title">🖼️ 替换结果</div>
      <div class="results-grid">
        <div v-for="(r, ri) in results" :key="ri" class="result-item">
          <img :src="'/api/ai-image/image-file?path=' + encodeURIComponent(r.path) + '&t=' + Date.now()" class="result-img" />
          <div class="result-name">{{ r.key }}</div>
          <div class="result-actions">
            <button class="btn-regenerate" @click="regenerate(ri)">🔄 重新生成</button>
            <button class="btn-delete-result" @click="deleteResult(ri, r.path)">🗑️ 删除</button>
          </div>
        </div>
      </div>
    </div>

    <UploadModal ref="uploadRef" @confirm="onUploadConfirm" />
  </div>
</template>

<style scoped>
.replace-section { padding: 10px 0; }
.section-title { font-size: 13px; font-weight: 600; margin-bottom: 12px; }
.replace-grid { display: flex; gap: 10px; flex-wrap: wrap; }
.replace-item { text-align: center; }
.replace-img-wrap { width: 120px; height: 120px; overflow: hidden; border-radius: 8px; border: 1px solid #e0e0e0; }
.replace-img { width: 100%; height: 100%; object-fit: cover; cursor: pointer; }
.prompt-input { width: 100%; margin-top: 4px; padding: 4px 6px; border: 1px solid #ff4d4f; border-radius: 4px; font-size: 11px; outline: none; }
.btn-remove { margin-top: 2px; padding: 2px 8px; border: 1px solid #ff4d4f; border-radius: 4px; font-size: 10px; background: #fff; color: #ff4d4f; cursor: pointer; }
.replace-add { cursor: pointer; }
.add-box { width: 120px; height: 120px; border: 2px dashed #d9d9d9; border-radius: 8px; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #999; background: #fafafa; }
.model-select { display: flex; align-items: center; gap: 12px; padding: 16px; background: #fafafa; border-radius: 10px; margin: 12px 0; }
.model-select label { font-size: 13px; font-weight: 600; white-space: nowrap; }
.model-select select { flex: 1; padding: 8px 12px; border: 2px solid #e0e0e0; border-radius: 8px; font-size: 13px; }
.gen-bar { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
.gen-status { font-size: 12px; color: #999; }
.btn-gen { background: linear-gradient(135deg, #667eea, #764ba2); border: none; color: #fff; padding: 6px 20px; border-radius: 8px; font-size: 12px; font-weight: 600; cursor: pointer; }
.btn-gen:disabled { background: #ccc; }
.results-section { margin-top: 16px; border-top: 1px solid #eee; padding-top: 12px; }
.results-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; }
.results-grid { display: flex; gap: 10px; flex-wrap: wrap; }
.result-item { text-align: center; }
.result-img { width: 100px; height: 100px; object-fit: cover; border-radius: 6px; border: 1px solid #e0e0e0; cursor: pointer; }
.result-name { font-size: 10px; color: #999; margin-top: 2px; }
.result-actions { display: flex; gap: 4px; justify-content: center; margin-top: 4px; }
.btn-regenerate, .btn-delete-result { padding: 2px 6px; border-radius: 4px; font-size: 10px; cursor: pointer; background: #fff; }
.btn-regenerate { border: 1px solid #667eea; color: #667eea; }
.btn-delete-result { border: 1px solid #ff4d4f; color: #ff4d4f; }
</style>
