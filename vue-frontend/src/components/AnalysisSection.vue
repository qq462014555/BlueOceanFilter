<script setup lang="ts">
import { ref } from 'vue'
import { useAiState } from '../composables/useAiState'
import { autoGeneratePrompts } from '../api/aiImage'

const { productDir, platform, analysis, setAnalysis, setPromptsForPlatform } = useAiState()
const loading = ref(false)
const timer = ref(0)

async function doRefresh() {
  if (!productDir.value) return
  loading.value = true
  timer.value = 0
  const interval = setInterval(() => timer.value++, 1000)
  try {
    const data = await autoGeneratePrompts(productDir.value, platform.value, true)
    if (data.success && data.prompts) {
      const a: Record<string, string> = {}
      for (const k of ['品类', '材质', '卖点', '目标人群', '使用场景', '视觉特征']) {
        if (data.prompts[k]) a[k] = data.prompts[k]
      }
      setAnalysis(a)
      const p: Record<string, string> = {}
      for (let i = 1; i <= 5; i++) {
        const key = '图' + i
        if (data.prompts[key]) p[key] = data.prompts[key]
      }
      setPromptsForPlatform(platform.value, p)
    }
  } catch (e: any) {
    console.error('AI 分析失败', e)
  } finally {
    clearInterval(interval)
    loading.value = false
  }
}

defineExpose({ doRefresh })
</script>
<template>
  <div v-if="Object.keys(analysis).length > 0" class="analysis-section">
    <div class="analysis-header">
      <span class="analysis-title">📊 AI 商品分析</span>
      <button class="btn-refresh-analysis" :disabled="loading" @click="doRefresh">
        {{ loading ? `⏳ ${timer}s` : '🔄 刷新' }}
      </button>
    </div>
    <div class="analysis-grid">
      <div v-for="(v, k) in analysis" :key="k" class="analysis-item">
        <div class="item-label">{{ k }}</div>
        <div class="item-value">{{ v }}</div>
      </div>
    </div>
  </div>
</template>
<style scoped>
.analysis-section { margin-bottom: 12px; }
.analysis-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.analysis-title { font-size: 13px; color: #1d39c4; font-weight: 600; }
.btn-refresh-analysis {
  background: #fff; border: 1px solid #1d39c4; color: #1d39c4;
  border-radius: 6px; padding: 3px 12px; font-size: 12px; cursor: pointer; font-weight: 600;
}
.btn-refresh-analysis:disabled { opacity: 0.5; cursor: not-allowed; }
.analysis-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }
.analysis-item { background: #fff; border-radius: 6px; padding: 8px 12px; }
.item-label { font-size: 11px; color: #667eea; font-weight: 600; margin-bottom: 2px; }
.item-value { font-size: 12px; color: #333; line-height: 1.4; }
</style>
