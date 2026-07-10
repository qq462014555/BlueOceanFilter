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

export function useAiState() {
  function setProductDir(dir: string) {
    productDir.value = dir
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
  }
}
