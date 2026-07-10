import { ref, reactive } from 'vue'
import type { Model, AiAnalysis, PromptsMap } from '../types'

// 全局单例状态
const productDir = ref('')
const platform = ref('taobao')
const analysis = ref<AiAnalysis>({})
const prompts = reactive<Record<string, PromptsMap>>({})
const modelList = ref<Model[]>([])
const selectedModel = ref('openai/gpt-image-2')
const genStatus = ref('')
const genLoading = ref(false)

// 多商品缓存: productDir -> { analysis, prompts }
interface ProductCache {
  analysis: AiAnalysis
  prompts: PromptsMap
}
const _cache: Record<string, ProductCache> = {}

export function useAiState() {
  function setProductDir(dir: string) {
    // 切换前保存当前
    if (productDir.value && productDir.value !== dir) {
      saveCache()
    }
    productDir.value = dir
    // 切换后恢复
    restoreCache(dir)
  }

  function saveCache() {
    const d = productDir.value
    if (!d) return
    _cache[d] = {
      analysis: { ...analysis.value },
      prompts: { ...(prompts[platform.value] || {}) },
    }
  }

  function restoreCache(dir: string) {
    const cached = _cache[dir]
    if (cached) {
      analysis.value = { ...cached.analysis }
      if (cached.prompts) {
        prompts[platform.value] = { ...cached.prompts }
      }
    } else {
      analysis.value = {}
    }
  }

  function setPlatform(p: string) {
    platform.value = p
  }

  function setAnalysis(a: AiAnalysis) {
    analysis.value = a
  }

  function setPrompt(p: string, key: string, val: string) {
    if (!prompts[p]) prompts[p] = {}
    prompts[p][key] = val
  }

  function getPrompt(p: string, key: string): string {
    return prompts[p]?.[key] || ''
  }

  function setPromptsForPlatform(p: string, data: PromptsMap) {
    prompts[p] = { ...data }
  }

  function setModels(models: Model[]) {
    modelList.value = models
  }

  function clearCache(dir: string) {
    delete _cache[dir]
  }

  return {
    productDir,
    platform,
    analysis,
    prompts,
    modelList,
    selectedModel,
    genStatus,
    genLoading,
    setProductDir,
    setPlatform,
    setAnalysis,
    setPrompt,
    getPrompt,
    setPromptsForPlatform,
    setModels,
    saveCache,
    clearCache,
  }
}
