<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAiState } from '../composables/useAiState'
import { loadPrompts } from '../api/aiImage'
import AnalysisSection from './AnalysisSection.vue'
import PromptGrid from './PromptGrid.vue'
import WhiteBgSection from './WhiteBgSection.vue'
import ReplaceSection from './ReplaceSection.vue'
import ReferenceSection from './ReferenceSection.vue'

const props = defineProps<{ productDir: string }>()
const emit = defineEmits<{ (e: 'update:productDir', v: string): void }>()

const { platform, setPlatform, setModels, setPromptsForPlatform, modelList, selectedModel, setProductDir } = useAiState()
const tab = ref('generate')
const dirInput = ref('')

// 同步 productDir 到共享状态（AnalysisSection 等依赖它）
watch(() => props.productDir, async (dir) => {
  setProductDir(dir)
  if (!dir) return
  const data = await loadPrompts()
  setModels(data.models || [])
  for (const p of Object.keys(data.platforms || {})) {
    setPromptsForPlatform(p, data.platforms[p] || {})
  }
}, { immediate: true })

function loadDir() {
  if (dirInput.value.trim()) {
    emit('update:productDir', dirInput.value.trim())
  }
}
</script>
<template>
  <div class="panel">
    <div class="dir-bar">
      <input v-model="dirInput" :placeholder="productDir || '输入商品目录路径'" class="dir-input" />
      <button class="btn-load" @click="loadDir">加载</button>
    </div>
    <template v-if="productDir">
      <div class="platform-tabs">
        <button v-for="p in ['taobao', 'douyin', 'shopee']" :key="p"
          :class="['platform-tab', { active: platform === p }]"
          @click="setPlatform(p)">
          {{ { taobao: '淘宝', douyin: '抖音', shopee: '虾皮' }[p] }}
        </button>
      </div>
      <AnalysisSection />
      <WhiteBgSection :productDir="productDir" />
      <div class="tab-bar">
        <button v-for="t in [{ k: 'reference', label: '📷 参考图' }, { k: 'replace', label: '🔄 替换图' }, { k: 'generate', label: '🎨 自定义生成主图' }]"
          :key="t.k" :class="['tab', { active: tab === t.k }]" @click="tab = t.k">
          {{ t.label }}
        </button>
      </div>
      <div v-if="tab === 'reference'" class="tab-content">
        <ReferenceSection />
      </div>
      <div v-if="tab === 'replace'" class="tab-content">
        <ReplaceSection />
      </div>
      <div v-if="tab === 'generate'" class="tab-content">
        <div class="model-select">
          <label>图生成ai模型：</label>
          <select v-model="selectedModel">
            <option v-for="m in modelList" :key="m.id" :value="m.id">{{ m.name }}</option>
            <option value="black-forest-labs/FLUX.1-schnell">FLUX.1 Schnell</option>
          </select>
        </div>
        <PromptGrid />
      </div>
    </template>
  </div>
</template>
<style scoped>
.panel { padding: 10px 0; }
.dir-bar { display: flex; gap: 8px; margin-bottom: 16px; }
.dir-input { flex: 1; padding: 8px 12px; border: 2px solid #e0e0e0; border-radius: 8px; font-size: 14px; outline: none; }
.dir-input:focus { border-color: #667eea; }
.btn-load { padding: 8px 20px; background: #667eea; color: #fff; border: none; border-radius: 8px; cursor: pointer; font-weight: 600; }
.platform-tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.platform-tab { padding: 8px 20px; border: 2px solid #e0e0e0; border-radius: 8px; cursor: pointer; font-size: 13px; font-weight: 600; background: #fff; }
.platform-tab.active { background: #667eea; color: #fff; border-color: #667eea; }
.tab-bar { display: flex; gap: 8px; margin-bottom: 16px; }
.tab { flex: 1; padding: 10px; border: 2px solid #e0e0e0; border-radius: 8px; cursor: pointer; font-size: 13px; font-weight: 600; background: #fff; text-align: center; }
.tab.active { background: #667eea; color: #fff; border-color: #667eea; }
.tab-content { min-height: 200px; }
.model-select { display: flex; align-items: center; gap: 12px; padding: 16px; background: #fafafa; border-radius: 10px; margin-bottom: 12px; }
.model-select label { font-size: 13px; font-weight: 600; white-space: nowrap; }
.model-select select { flex: 1; padding: 8px 12px; border: 2px solid #e0e0e0; border-radius: 8px; font-size: 13px; }
</style>
