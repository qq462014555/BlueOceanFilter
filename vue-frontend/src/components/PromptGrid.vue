<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAiState } from '../composables/useAiState'
import { generateAllImages, listGeneratedImages } from '../api/aiImage'
import { PLATFORM_NAMES } from '../types'

const { productDir, platform, prompts, selectedModel, genStatus, genLoading, getPrompt } = useAiState()

const checkedKeys = ref<string[]>(['图1', '图2', '图3', '图4'])
const generatedImages = ref<Record<string, string[]>>({})
const keys = ['图1', '图2', '图3', '图4']

function toggleAll(e: Event) {
  const cb = e.target as HTMLInputElement
  if (cb.checked) checkedKeys.value = [...keys]
  else checkedKeys.value = []
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

  genLoading.value = true
  genStatus.value = '正在生成...'
  try {
    const data = await generateAllImages({
      model: selectedModel.value,
      prompt: checked.map(k => k + '：' + all[k]).join('\n'),
      allPrompts: all,
      n: checked.length,
      productDir: dir,
      platform: plat,
    })
    genStatus.value = `✅ ${data.succeeded || 0}/${data.total || 0} 成功`
    if (data.results) {
      for (const r of data.results) {
        if (!generatedImages.value[r.key]) generatedImages.value[r.key] = []
        generatedImages.value[r.key].push(r.path)
      }
    }
  } catch (e: any) {
    genStatus.value = '❌ ' + e.message
  }
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
</script>
<template>
  <div class="ps">
    <div class="ps-header">📝 AI 智能生成提示词（可微调）</div>
    <div class="sel-all"><label><input type="checkbox" checked @change="toggleAll" /> 一键全选</label></div>
    <div class="pg">
      <div v-for="key in keys" :key="key" class="pc">
        <div class="pc-hd">
          <span class="pc-label">{{ PLATFORM_NAMES[platform] || platform }} - {{ key }}</span>
          <label style="font-size:11px;"><input type="checkbox" :value="key" v-model="checkedKeys" /> 生成</label>
        </div>
        <textarea
          :value="getPrompt(platform, key)"
          @input="(e: any) => { const p = prompts[platform] || {}; p[key] = e.target.value }"
          placeholder="输入生成风格提示词..." class="ta"
        ></textarea>
        <div class="gi" v-if="generatedImages[key]?.length">
          <img v-for="(path, pi) in generatedImages[key]" :key="pi"
            :src="`/api/ai-image/image-file?path=${encodeURIComponent(path)}&t=${Date.now()}`"
            class="gi-img" />
        </div>
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
.pc { background: #fff; border-radius: 8px; padding: 12px; border: 1px solid #e0e0e0; }
.pc-hd { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; font-size: 12px; }
.pc-label { font-weight: 600; color: #667eea; }
.ta { width: 100%; height: 100px; padding: 8px; border: 1px solid #e0e0e0; border-radius: 6px; font-size: 13px; resize: vertical; outline: none; font-family: inherit; box-sizing: border-box; }
.ta:focus { border-color: #667eea; }
.gi { display: flex; gap: 6px; margin-top: 6px; flex-wrap: wrap; }
.gi-img { width: 60px; height: 60px; object-fit: cover; border-radius: 4px; border: 1px solid #e0e0e0; cursor: pointer; }
.gb { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
.gs { font-size: 13px; color: #999; }
.btn-gen { background: linear-gradient(135deg, #667eea, #764ba2); border: none; color: #fff; padding: 10px 32px; border-radius: 8px; font-size: 14px; font-weight: 600; cursor: pointer; }
.btn-gen:disabled { background: #ccc; cursor: not-allowed; }
</style>
