import type { WhiteBgImage, TaskStatus, PromptsMap } from '../types'

const API = '/api/ai-image'

async function fetchJson<T>(url: string, opts?: RequestInit): Promise<T> {
  const res = await fetch(url, opts)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

// ===== 提示词 =====
export function loadPrompts(): Promise<{ platforms: Record<string, PromptsMap>; models: { id: string; name: string }[] }> {
  return fetchJson(`${API}/prompts`)
}

export function savePrompts(data: { platforms: Record<string, PromptsMap> }): Promise<{ success: boolean }> {
  return fetchJson(`${API}/prompts`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  })
}

// ===== AI 分析 =====
export function autoGeneratePrompts(productDir: string, platform: string, forceNew = false): Promise<any> {
  return fetchJson(`${API}/auto-generate-prompts`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productDir, platform, forceNew }),
  })
}

// ===== 主图生成 =====
export function generateAllImages(data: {
  model: string; prompt: string; allPrompts: PromptsMap; n: number
  productDir: string; platform: string
}): Promise<{ success: boolean; results?: { key: string; path: string }[]; succeeded?: number; total?: number }> {
  return fetchJson(`${API}/generate-all`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  })
}

// ===== 已生成图片 =====
export function listGeneratedImages(productDir: string): Promise<{ images: { path: string; name: string }[] }> {
  return fetchJson(`${API}/list-images?productDir=${encodeURIComponent(productDir)}`)
}

// ===== 白底图 =====
export function listWhiteBgImages(productDir: string): Promise<{ success: boolean; images: WhiteBgImage[] }> {
  return fetchJson(`${API}/list-whitebg-images?productDir=${encodeURIComponent(productDir)}`)
}

export function generateWhiteBg(productDir: string, force = false): Promise<{ success: boolean; images: WhiteBgImage[] }> {
  return fetchJson(`${API}/generate-white-bg`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productDir, force }),
  })
}

export function uploadWhiteBg(productDir: string, image: string): Promise<{ success: boolean; path?: string; error?: string }> {
  return fetchJson(`${API}/upload-white-bg`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productDir, image }),
  })
}

// ===== 替换图 =====
export function replaceImages(data: {
  productDir: string; images: string[]; prompts: string[]; model: string; selectedWhiteBg?: string[]
}): Promise<{ success: boolean; results?: { key: string; path: string }[]; succeeded?: number }> {
  return fetchJson(`${API}/replace`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  })
}

export function listReplaceResults(productDir: string): Promise<{ success: boolean; images: { path: string; name: string }[] }> {
  return fetchJson(`${API}/list-replace-images?productDir=${encodeURIComponent(productDir)}`)
}

// ===== 文件操作 =====
export function deleteFile(path: string): Promise<{ success: boolean; error?: string }> {
  return fetchJson(`${API}/delete-file`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ path }),
  })
}

// ===== 任务状态 =====
export function getTaskStatus(productDir: string, task: string): Promise<{ success: boolean; status: TaskStatus }> {
  return fetchJson(`${API}/task-status?productDir=${encodeURIComponent(productDir)}&task=${task}`)
}

export function setTaskStatus(productDir: string, task: string, status: TaskStatus): Promise<{ success: boolean }> {
  return fetchJson(`${API}/task-status`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productDir, task, status }),
  })
}
