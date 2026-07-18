<script setup lang="ts">
import { ref, watch, inject } from "vue"
import { useAiState } from '../composables/useAiState'
import { replaceImages as apiReplaceImages, listReplaceResults, deleteFile, saveReplaceCache, getReplaceCache } from '../api/aiImage'
import UploadModal from './UploadModal.vue'
import WhiteBgSelector from "./WhiteBgSelector.vue"

const { productDir, selectedModel, modelList, replaceImages, replacePrompts, replaceResults } = useAiState()
const loading = ref(false)
const alertMsg = ref("")
function showAlert(m: string) { alertMsg.value = m }
function closeAlert() { alertMsg.value = "" }
const status = ref('')
const uploadRef = ref<InstanceType<typeof UploadModal>>()
const showWhiteBgSelector = ref(false)
const selectedWhiteBgUrls = ref<string[][]>([])
const previewImage = inject<(url: string) => void>("previewImage") || ((u: string) => { window.open(u, "_blank"); })
function getResultUrl(path: string) { return "/api/ai-image/image-file?path=" + encodeURIComponent(path) + "&t=" + Date.now() }
const uploadIdx = ref(-1)

watch(productDir, async (dir) => { if (!dir) return; await loadExisting() }, { immediate: true })

async function loadExisting() {
  if (!productDir.value) return
  try {
    const cache = await getReplaceCache(productDir.value)
    if (cache.success && cache.images?.length) { replaceImages.value = cache.images; replacePrompts.value = cache.images.map(() => "") }
  } catch (e) {}
  try {
    const data = await listReplaceResults(productDir.value)
    if (data.success && data.images) { replaceResults.value = data.images.map((img, i) => ({ key: '替换图' + (i + 1), path: img.path })) }
  } catch (e) {}
}

function openUpload(idx: number) { uploadIdx.value = idx; uploadRef.value?.open() }
function onUploadConfirm(data: string) {
  if (uploadIdx.value === -1) { replaceImages.value.push(data); replacePrompts.value.push("") }
  else { replaceImages.value[uploadIdx.value] = data }
  saveReplaceCache(productDir.value, replaceImages.value).catch(() => {})
}
function removeImage(idx: number) {
  replaceImages.value.splice(idx, 1); replacePrompts.value.splice(idx, 1)
  saveReplaceCache(productDir.value, replaceImages.value).catch(() => {})
}
function updatePrompt(idx: number, val: string) { replacePrompts.value[idx] = val }

function startGenerate() {
  if (replaceImages.value.length === 0) { showAlert("请先添加替换图，再点击生成"); return }
  const empty = replacePrompts.value.some((p: string) => !p.trim())
  if (empty) { showAlert("请填写所有替换图的追加提示词"); return }
  if (!productDir.value) return
  showWhiteBgSelector.value = true
}

async function onWhiteBgSelected(selected: string[][]) {
  showWhiteBgSelector.value = false; selectedWhiteBgUrls.value = selected; await doGenerateWithRefs()
}

async function doGenerateWithRefs() {
  loading.value = true; status.value = "正在生成..."; let succeeded = 0; let total = 0
  for (let i = 0; i < replaceImages.value.length; i++) {
    status.value = "正在生成 替换图" + (i + 1) + "/" + replaceImages.value.length + "..."
    try {
      const data = await apiReplaceImages({ productDir: productDir.value, images: [replaceImages.value[i]], prompts: [replacePrompts.value[i] || ""], model: selectedModel.value, selectedWhiteBg: selectedWhiteBgUrls.value[i] || [] })
      if (data.results) {
        if (!replaceResults.value[i]) replaceResults.value[i] = data.results[0]
        else replaceResults.value[i] = data.results[0]
        replaceResults.value = [...replaceResults.value]; succeeded++
      }
    } catch (e: any) { status.value = "❌ 替换图" + (i + 1) + "失败: " + e.message }
    total++
  }
  status.value = "✅ " + succeeded + "/" + total + " 成功"; await loadExisting(); loading.value = false
}

async function regenerate(idx: number) {
  if (!replaceImages.value[idx]) return; loading.value = true
  try {
    const data = await apiReplaceImages({ productDir: productDir.value, images: [replaceImages.value[idx]], prompts: [replacePrompts.value[idx] || ''], model: selectedModel.value, selectedWhiteBg: selectedWhiteBgUrls.value[idx] || [] })
    if (data.results?.[0]) {
      if (!replaceResults.value[idx]) replaceResults.value[idx] = data.results[0]
      else replaceResults.value[idx] = data.results[0]
      replaceResults.value = [...replaceResults.value]
    }
  } catch (e: any) { status.value = "❌ " + e.message }
  loading.value = false
}

async function deleteResult(idx: number, filePath: string) {
  if (!confirm('确定删除该替换图吗？')) return
  try { await deleteFile(filePath); replaceResults.value.splice(idx, 1); await loadExisting() } catch (e: any) { status.value = "❌ " + e.message }
}
</script>
<template>
  <div class="replace-section">
    <div v-if="alertMsg" class="alert-overlay" @click="closeAlert">
      <div class="alert-box" @click.stop>
        <div class="alert-icon">!</div>
        <div class="alert-text">{{ alertMsg }}</div>
        <button class="alert-btn" @click="closeAlert">确定</button>
      </div>
    </div>
    <div class="section-title">🔄 替换图</div>
    <div class="replace-grid">
      <div v-for="(img, idx) in replaceImages" :key="idx" class="replace-item">
        <div class="replace-img-wrap"><img :src="img" class="replace-img" @click="previewImage(img)" /></div>
        <input :value="replacePrompts[idx]" @input="(e: any) => updatePrompt(idx, e.target.value)" placeholder="必填：输入替换要求" class="prompt-input" />
        <button class="btn-remove" @click="removeImage(idx)">删除</button>
      </div>
      <div class="replace-add" @click="openUpload(-1)"><div class="add-box"><span>+</span><span>添加图</span></div></div>
    </div>
    <div class="model-select">
      <label>图生成ai模型：</label>
      <select v-model="selectedModel">
        <option v-for="m in modelList" :key="m.id" :value="m.id">{{ m.name }}</option>
        <option value="black-forest-labs/FLUX.1-schnell">FLUX.1 Schnell</option>
      </select>
    </div>
    <div class="gen-bar">
      <span class="gen-status">{{ status }}</span>
      <button class="btn-gen" :disabled="loading" @click="startGenerate">{{ loading ? '⏳ 生成中...' : '🚀 生成替换图' }}</button>
    </div>
    <div v-if="replaceResults.length > 0" class="results-section">
      <div class="results-title">🖼️ 替换结果</div>
      <div class="results-grid">
        <div v-for="(r, ri) in replaceResults" :key="ri" class="result-item">
          <img :src="getResultUrl(r.path)" class="result-img" @click="previewImage(getResultUrl(r.path))" />
          <div class="result-name">{{ r.key }}</div>
          <div class="result-actions">
            <button class="btn-regenerate" @click="regenerate(ri)">🔄 重新生成</button>
            <button class="btn-delete-result" @click="deleteResult(ri, r.path)">🗑️ 删除</button>
          </div>
        </div>
      </div>
    </div>
    <UploadModal ref="uploadRef" @confirm="onUploadConfirm" />
    <WhiteBgSelector v-if="showWhiteBgSelector" :productDir="productDir" :replaceImages="replaceImages" @confirm="onWhiteBgSelected" @close="showWhiteBgSelector = false" />
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
.alert-overlay { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 99999; }
.alert-box { background: #fff; border-radius: 16px; padding: 40px 48px 32px; text-align: center; min-width: 320px; box-shadow: 0 12px 40px rgba(0,0,0,0.3); animation: alertIn 0.25s ease; }
@keyframes alertIn { from { transform: scale(0.9); opacity: 0; } to { transform: scale(1); opacity: 1; } }
.alert-icon { width: 56px; height: 56px; background: #ff4d4f; color: #fff; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 28px; font-weight: bold; margin: 0 auto 16px; }
.alert-text { font-size: 15px; color: #333; line-height: 1.6; margin-bottom: 24px; }
.alert-btn { padding: 10px 48px; border: none; border-radius: 8px; background: #667eea; color: #fff; font-size: 14px; font-weight: 600; cursor: pointer; }
</style>
