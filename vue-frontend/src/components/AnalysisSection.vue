<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { useAiState } from '../composables/useAiState'
import { autoGeneratePrompts, getTaskStatus } from '../api/aiImage'

const { productDir, platform, analysis, setAnalysis, setPromptsForPlatform } = useAiState()
const loading = ref(false)
const polling = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null
const API = '/api/ai-image'

async function checkAnalysisDone(): Promise<boolean> {
  if (!productDir.value) return false
  try {
    const r = await fetch(`${API}/analysis-done?productDir=${encodeURIComponent(productDir.value)}&platform=${platform.value}`)
    const d = await r.json()
    return d.exists === true
  } catch { return false }
}

async function loadAnalysisData() {
  if (!productDir.value) return
  try {
    const data = await autoGeneratePrompts(productDir.value, platform.value, false)
    if (data.success && data.prompts) {
      const a: Record<string, string> = {}
      for (const k of ['品类', '材质', '卖点', '目标人群', '使用场景', '视觉特征']) {
        if (data.prompts[k]) a[k] = data.prompts[k]
      }
      setAnalysis(a)
      const p: Record<string, string> = {}
      for (let i = 1; i <= 5; i++) {
        const key = '图' + i; if (data.prompts[key]) p[key] = data.prompts[key]
      }
      setPromptsForPlatform(platform.value, p)
    }
  } catch (e) { console.error('加载分析数据失败', e) }
}

function stopPoll() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  polling.value = false
}

async function startPoll() {
  stopPoll()
  if (!productDir.value) return

  // ① 检查缓存文件
  if (await checkAnalysisDone()) {
    await loadAnalysisData()
    return
  }

  // ② 检查是否有正在运行的任务（防刷新后重复调 AI）
  let taskRunning = false
  try {
    const st = await getTaskStatus(productDir.value, 'analysis')
    if (st.status === 'running') taskRunning = true
  } catch {}

  loading.value = true

  if (!taskRunning) {
    // ③ 没有运行中的任务 → 异步启动 AI 分析
    autoGeneratePrompts(productDir.value, platform.value, false).catch(() => {})
  }

  // ④ 轮询等待缓存文件
  polling.value = true
  pollTimer = setInterval(async () => {
    if (await checkAnalysisDone()) {
      stopPoll()
      loading.value = false
      await loadAnalysisData()
    }
  }, 1000)
}

async function doRefresh() {
  if (!productDir.value) return
  loading.value = true
  try {
    await fetch(`${API}/auto-generate-prompts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ productDir: productDir.value, platform: platform.value, forceNew: true })
    })
  } catch {}
  await startPoll()
}

watch(productDir, () => {
  if (productDir.value) startPoll()
  else { stopPoll(); loading.value = false }
}, { immediate: true })

onUnmounted(() => stopPoll())
</script>
<template>
  <div class="analysis-section">
    <div v-if="loading" class="analysis-loading">
      <span class="spinner"></span> AI 正在分析商品...
    </div>
    <div v-if="!loading && Object.keys(analysis).length > 0">
      <div class="analysis-header">
        <span class="analysis-title">📊 AI 商品分析</span>
        <button class="btn-refresh" @click="doRefresh">🔄 重新生成</button>
      </div>
      <div class="analysis-grid">
        <div v-for="(v, k) in analysis" :key="k" class="analysis-item">
          <div class="item-label">{{ k }}</div>
          <div class="item-value">{{ v }}</div>
        </div>
      </div>
    </div>
  </div>
</template>
<style scoped>
.analysis-section { margin-bottom: 12px; }
.analysis-loading { text-align: center; padding: 20px; color: #999; font-size: 13px; }
.analysis-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.analysis-title { font-size: 13px; color: #1d39c4; font-weight: 600; }
.btn-refresh { background: #fff; border: 1px solid #1d39c4; color: #1d39c4; border-radius: 6px; padding: 3px 12px; font-size: 12px; cursor: pointer; font-weight: 600; }
.analysis-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }
.analysis-item { background: #fff; border-radius: 6px; padding: 8px 12px; }
.item-label { font-size: 11px; color: #667eea; font-weight: 600; margin-bottom: 2px; }
.item-value { font-size: 12px; color: #333; line-height: 1.4; }
.spinner { display: inline-block; width: 14px; height: 14px; border: 2px solid #e0e0e0; border-top-color: #1d39c4; border-radius: 50%; animation: spin 0.8s linear infinite; vertical-align: middle; margin-right: 6px; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
