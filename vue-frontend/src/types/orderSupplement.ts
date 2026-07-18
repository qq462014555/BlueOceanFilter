export interface OrderSupplement {
  id?: number
  shopId: string
  date?: string
  productName: string
  productId: string
  skuId?: string
  price: number
  reviewImage?: string
  reviewText?: string
  resourceParty?: string
  status: string
  groupId?: number
  createTime?: string
  updateTime?: string
}

export interface PageResult<T> {
  total: number
  page: number
  size: number
  records: T[]
}

export interface ShopInfo {
  platform: string
  shopId: string
  shopName: string
}

/** 拼接显示文本：平台/店铺名/shopId */
export function shopDisplay(s: ShopInfo) {
  return `${s.platform}/${s.shopName}/${s.shopId}`
}

export const STATUS_LIST = ['待补单', '已补单', '取消补单']
