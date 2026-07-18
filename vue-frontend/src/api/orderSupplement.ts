import type { OrderSupplement, PageResult, ShopInfo } from '../types/orderSupplement'

const API = '/api/order-supplement'

async function fetchJson<T>(url: string, opts?: RequestInit): Promise<T> {
  const res = await fetch(url, opts)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

/** 获取店铺列表 */
export function fetchShops(): Promise<ShopInfo[]> {
  return fetchJson(`${API}/shops`)
}

/** 分页查询 */
export function listRecords(params: {
  page?: number
  size?: number
  shopId?: string
  dateFrom?: string
  dateTo?: string
  keyword?: string
  status?: string
  sortField?: string
  sortOrder?: string
}): Promise<PageResult<OrderSupplement>> {
  const qs = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== '') qs.set(k, String(v)) })
  return fetchJson(`${API}/list?${qs.toString()}`)
}

/** 新增 */
export function createRecord(data: OrderSupplement): Promise<{ success: boolean; id?: number; error?: string }> {
  return fetchJson(`${API}/create`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  })
}

/** 更新 */
export function updateRecord(data: OrderSupplement): Promise<{ success: boolean; error?: string }> {
  return fetchJson(`${API}/update`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  })
}

/** 删除 */
export function deleteRecord(id: number): Promise<{ success: boolean; error?: string }> {
  return fetchJson(`${API}/delete`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id }),
  })
}

/** 创建组（归组） */
export function createGroup(data: { recordIds: number[]; date?: string; resourceParty: string }): Promise<{ success: boolean; groupId?: number; error?: string }> {
  return fetchJson(`${API}/create-group`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  })
}

/** 标记组已发送 */
export function markSent(groupId: number): Promise<{ success: boolean; error?: string }> {
  return fetchJson(`${API}/mark-sent`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ groupId }),
  })
}

/** 模糊搜索资源方（返回组ID + 资源方名） */
export function searchResourceParties(keyword: string): Promise<{ id: number; resource_party: string }[]> {
  return fetchJson(`${API}/resource-parties?keyword=${encodeURIComponent(keyword)}`)
}

/** 取消记录与组的绑定 */
export function ungroupRecord(id: number): Promise<{ success: boolean; error?: string }> {
  return fetchJson(`${API}/ungroup`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id }),
  })
}

/** 绑定记录到已有组 */
export function bindGroup(data: { recordIds: number[]; groupId: number }): Promise<{ success: boolean; error?: string }> {
  return fetchJson(`${API}/bind-group`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  })
}

/** 上传评论图片 */
export function uploadImage(data: { image: string; shopId: string; productName: string }): Promise<{ success: boolean; path?: string; error?: string }> {
  return fetchJson(`${API}/upload-image`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  })
}

/** 获取评论图URL */
export function getImageUrl(path: string): string {
  return `${API}/image?path=${encodeURIComponent(path)}&t=${Date.now()}`
}
