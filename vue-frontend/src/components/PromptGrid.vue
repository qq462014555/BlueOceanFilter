<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAiState } from '../composables/useAiState'
import { generateAllImages, listGeneratedImages } from '../api/aiImage'
import { PLATFORM_NAMES } from '../types'

const { productDir, platform, prompts, selectedModel, genStatus, genLoading, getPrompt, analysis } = useAiState()

const checkedKeys = ref<string[]>(['图1', '图2', '图3', '图4', '图5'])
const generatedImages = ref<Record<string, string[]>>({})
const keys = ['图1', '图2', '图3', '图4', '图5']

function toggleAll(e: Event) {
  const cb = e.target as HTMLInputElement
  if (cb.checked) checkedKeys.value = [...keys]
  else checkedKeys.value = []
}

async function optimize(key: string) {
  const analysisMap = analysis.value
  if (Object.keys(analysisMap).length === 0) { genStatus.value = '⚠️ 缺少商品分析'; return }
  const ta = document.querySelector<HTMLTextAreaElement>('textarea[data-key="' + key + '"]')
  if (!ta || !ta.value.trim()) { genStatus.value = '⚠️ 提示词为空'; return }
  genStatus.value = '⏳ 优化中...'
  try {
    const resp = await fetch('/api/ai-image/optimize-prompt', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt: ta.value.trim(), analysis: analysisMap })
    })
    if (resp.ok) {
      const data = await resp.json()
      if (data.success && data.optimizedPrompt) {
        ta.value = data.optimizedPrompt
        genStatus.value = '✅ 已优化'
        // Update shared state
        if (!prompts[platform.value]) prompts[platform.value] = {}
        prompts[platform.value][key] = data.optimizedPrompt
      } else { genStatus.value = '❌ 优化失败' }
    }
  } catch { genStatus.value = '❌ 异常' }
}

async function generate() {
  const dir = productDir.value
  const plat = platform.value
  if (!dir || !plat) return
  const all: Record<string, string> = {}
  const checked: string[] = []
  for (const k of keys) {
    if (checkedKeys.value.includes(k)) {
      const v = getPrompt(plat, k)
      if (v.trim()) { all[k] = v; checked.push(k) }
    }
  }
  if (checked.length === 0) { genStatus.value = '⚠️ 请填写提示词'; return }
  genLoading.value = true; genStatus.value = '正在生成...'
  try {
    const data = await generateAllImages({ model: selectedModel.value, prompt: checked.map(k => k + '：' + all[k]).join('\n'), allPrompts: all, n: checked.length, productDir: dir, platform: plat })
    genStatus.value = `✅ ${data.succeeded || 0}/${data.total || 0} 成功`
    if (data.results) {
      for (const r of data.results) {
        if (!generatedImages.value[r.key]) generatedImages.value[r.key] = []
        generatedImages.value[r.key].push(r.path)
      }
    }
  } catch (e: any) { genStatus.value = '❌ ' + e.message }
  genLoading.value = false
}

async function loadGenerated() {
  if (!productDir.value) return
  try {
    const data = await listGeneratedImages(productDir.value)
    const grouped: Record<string, string[]> = {}
    for (const img of data.images || []) {
      const m = img.name.match(/主图_(\d+)_主图\d+\.jpg/)
      if (m) {
        const key = '图' + parseInt(m[1])
        if (!grouped[key]) grouped[key] = []
        grouped[key].push(img.path)
      }
    }
    generatedImages.value = grouped
  } catch (e) {}
}

watch(productDir, () => { loadGenerated() }, { immediate: true })

function previewImage(path: string) {
  const u = '/api/ai-image/image-file?path=' + encodeURIComponent(path) + '&t=' + Date.now()
  window.open(u, '_blank')
}
</script>
<template>
  <div class="ps">
    <div class="ps-header">📝 AI 智能生成提示词（可微调）</div>
    <div class="sel-all"><label><input type="checkbox" checked @change="toggleAll" /> 一键全选</label></div>
    <div class="pg">
      <div v-for="key in keys" :key="key" class="pc">
        <div class="pc-hd">
          <span class="pc-label">{{ PLATFORM_NAMES[platform] || platform }} - {{ key }}</span>
          <label><input type="checkbox" :value="key" v-model="checkedKeys" /> 生成</label>
        </div>
        <div class="pc-body">
          <textarea :value="getPrompt(platform, key)"
            @input="(e: any) => { if (!prompts[platform]) prompts[platform] = {}; prompts[platform][key] = e.target.value }"
            placeholder="输入生成风格提示词..." class="ta" data-key="replace_me"></textarea>
          <div class="gen-slot" v-if="generatedImages[key]?.length">
            <img v-for="(path, pi) in generatedImages[key]" :key="pi"
              :src="'/api/ai-image/image-file?path=' + encodeURIComponent(path) + '&t=' + Date.now()"
              class="gi-img" @click="previewImage(path)" />
          </div>
        </div>
        <button class="btn-optimize" :disabled="Object.keys(analysis).length === 0" @click="optimize(key)">🧠 智能优化提示词</button>
      </div>
    </div>
    <div class="gb">
      <span class="gs">{{ genStatus }}</span>
      <button class="btn-gen" :disabled="genLoading" @click="generate">
        {{ genLoading ? '⏳ 生成中...' : '🚀 生成全部勾选的主图' }}
      </button>
    </div>
  </div>
</template>
<style scoped>
.ps { background: #f8f9ff; border-radius: 10px; padding: 16px; margin-bottom: 12px; }
.ps-header { font-size: 13px; font-weight: 600; color: #667eea; margin-bottom: 8px; }
.sel-all { text-align: right; font-size: 12px; color: #667eea; margin-bottom: 8px; }
.pg { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.pc { background: #fff; border-radius: 8px; padding: 12px; border: 1px solid #e0e0e0; display:flex; flex-direction:column; gap:4px; }
.pc-hd { display: flex; justify-content: space-between; align-items: center; font-size: 12px; }
.pc-label { font-weight: 600; color: #667eea; }
.ta { width: 100%; min-height: 100px; padding: 8px; border: 1px solid #e0e0e0; border-radius: 6px; font-size: 13px; resize: vertical; outline: none; font-family: inherit; box-sizing: border-box; }
.ta:focus { border-color: #667eea; }
.gen-slot { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 4px; }
.gi-img { width: 60px; height: 60px; object-fit: cover; border-radius: 4px; border: 1px solid #e0e0e0; cursor: pointer; }
.btn-optimize { padding: 4px 10px; border: 1px solid #52c41a; border-radius: 4px; font-size: 11px; background: #f6ffed; color: #52c41a; cursor: pointer; }
.btn-optimize:disabled { background: #f5f5f5; color: #999; border-color: #d9d9d9; }
.gb { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
.gs { font-size: 13px; color: #999; }
.btn-gen { background: linear-gradient(135deg, #667eea, #764ba2); border: none; color: #fff; padding: 10px 32px; border-radius: 8px; font-size: 14px; font-weight: 600; cursor: pointer; }
.btn-gen:disabled { background: #ccc; cursor: not-allowed; }
</style>
