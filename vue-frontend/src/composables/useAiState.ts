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

// 替换图缓存（内存）
const replaceImages = ref<string[]>([])
const replacePrompts = ref<string[]>([])
const replaceResults = ref<{ key: string; path: string }[]>([])

// 参考图缓存
const refImages = ref<string[]>([])

// 多商品缓存
interface ProductCache {
  analysis: AiAnalysis
  prompts: PromptsMap
  replaceImages: string[]
  replacePrompts: string[]
  replaceResults: { key: string; path: string }[]
  refImages: string[]
}
const _cache: Record<string, ProductCache> = {}

export function useAiState() {
  function setProductDir(dir: string) {
    if (productDir.value && productDir.value !== dir) saveCache()
    productDir.value = dir
    restoreCache(dir)
  }

  function saveCache() {
    const d = productDir.value
    if (!d) return
    _cache[d] = {
      analysis: { ...analysis.value },
      prompts: { ...(prompts[platform.value] || {}) },
      replaceImages: [...replaceImages.value],
      replacePrompts: [...replacePrompts.value],
      replaceResults: JSON.parse(JSON.stringify(replaceResults.value)),
      refImages: [...refImages.value],
    }
  }

  function restoreCache(dir: string) {
    const cached = _cache[dir]
    if (cached) {
      analysis.value = { ...cached.analysis }
      if (cached.prompts) prompts[platform.value] = { ...cached.prompts }
      replaceImages.value = [...(cached.replaceImages || [])]
      replacePrompts.value = [...(cached.replacePrompts || [])]
      replaceResults.value = JSON.parse(JSON.stringify(cached.replaceResults || []))
      refImages.value = [...(cached.refImages || [])]
    } else {
      analysis.value = {}
      replaceImages.value = []
      replacePrompts.value = []
      replaceResults.value = []
      refImages.value = []
    }
  }

  function setPlatform(p: string) { platform.value = p }
  function setAnalysis(a: AiAnalysis) { analysis.value = a }
  function getPrompt(p: string, key: string): string { return prompts[p]?.[key] || '' }
  function setPrompt(p: string, key: string, val: string) { if (!prompts[p]) prompts[p] = {}; prompts[p][key] = val }
  function setPromptsForPlatform(p: string, data: PromptsMap) { prompts[p] = { ...data } }
  function setModels(models: Model[]) { modelList.value = models }
  function clearCache(dir: string) { delete _cache[dir] }

  function setReplaceData(imgs: string[], prmpts: string[], res: { key: string; path: string }[]) {
    replaceImages.value = imgs
    replacePrompts.value = prmpts
    replaceResults.value = res
  }

  return {
    productDir, platform, analysis, prompts, modelList, selectedModel, genStatus, genLoading,
    replaceImages, replacePrompts, replaceResults, refImages,
    setProductDir, setPlatform, setAnalysis, setPrompt, getPrompt, setPromptsForPlatform, setModels,
    saveCache, clearCache, setReplaceData,
  }
}
