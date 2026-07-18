<script setup lang="ts">
import { computed } from 'vue'
import type { OrderSupplement } from '../types/orderSupplement'

const props = defineProps<{
  records: OrderSupplement[]
}>()
const emit = defineEmits<{ (e: 'close'): void }>()

/** 淘宝下单链接 */
function buildTaobaoUrl(pid: string, sku: string) {
  const param = `${pid}_1_${sku || ''}`
  return `https://main.m.taobao.com/order/index.html?buyNow=false&buyParam=${param},${param},&abtest_module=dx2native.settlement-bar.0`
}

/** 批量下单URL（多商品逗号拼接） */
const orderUrl = computed(() => {
  const params = props.records
    .map(r => `${r.productId}_1_${r.skuId || ''}`)
    .filter(Boolean)
    .join(',')
  if (!params) return ''
  return `https://main.m.taobao.com/order/index.html?buyNow=false&buyParam=${params},${params},&abtest_module=dx2native.settlement-bar.0`
})

/** 二维码图片URL */
const qrUrl = computed(() => {
  if (!orderUrl.value) return ''
  return `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(orderUrl.value)}`
})

const totalPrice = computed(() => {
  return props.records.reduce((sum, r) => sum + (Number(r.price) || 0), 0)
})
</script>

<template>
  <div class="modal-overlay" @click.self="emit('close')">
    <div class="modal-content qr-modal">
      <div class="modal-header">
        <h3>下单二维码</h3>
        <button class="close-btn" @click="emit('close')">&times;</button>
      </div>
      <div class="qr-body">
        <!-- 二维码 -->
        <div class="qr-area">
          <img v-if="qrUrl" :src="qrUrl" class="qr-img" alt="二维码" />
          <div v-else class="qr-empty">请选择包含商品ID的记录</div>
        </div>

        <!-- 商品列表 -->
        <div class="qr-records">
          <table class="qr-table">
            <thead>
              <tr>
                <th>商品名称</th>
                <th>商品标题</th>
                <th>商品ID</th>
                <th>SKU ID</th>
                <th>价格</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(r, i) in records" :key="i">
                <td>{{ r.productName || '-' }}</td>
                <td>{{ r.productName || '-' }}</td>
                <td>{{ r.productId || '-' }}</td>
                <td>{{ r.skuId || '-' }}</td>
                <td>{{ r.price }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 合计 -->
        <div class="qr-total">
          <span>合计：</span>
          <span class="total-price">¥{{ totalPrice.toFixed(2) }}</span>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn-cancel" @click="emit('close')">关闭</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.qr-modal { max-width: 700px; }
.qr-body { padding: 20px 24px; max-height: 60vh; overflow-y: auto; }
.qr-area { text-align: center; margin-bottom: 20px; }
.qr-img { width: 220px; height: 220px; border: 1px solid #e0e0e0; border-radius: 8px; }
.qr-empty { color: #999; font-size: 13px; padding: 40px 0; }
.qr-records { margin-bottom: 16px; }
.qr-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.qr-table th { background: #fafafa; padding: 8px 10px; text-align: left; font-weight: 600; border-bottom: 2px solid #e8e8e8; }
.qr-table td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
.qr-total { text-align: right; font-size: 16px; font-weight: 600; padding-top: 12px; border-top: 2px solid #e8e8e8; }
.total-price { color: #ff4d4f; font-size: 20px; }
</style>
