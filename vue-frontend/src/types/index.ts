export interface Model {
  id: string
  name: string
}

export interface WhiteBgImage {
  path: string
  name: string
}

export interface ReplaceResult {
  key: string
  path: string
  success: boolean
}

export interface AiAnalysis {
  [key: string]: string
}

export interface PromptsMap {
  [key: string]: string
}

export type TaskStatus = 'none' | 'running' | 'completed' | 'failed'

export const PLATFORM_NAMES: Record<string, string> = {
  taobao: '淘宝',
  douyin: '抖音',
  shopee: '虾皮',
}
