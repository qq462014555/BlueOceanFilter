<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import type { OrderSupplement } from '../types/orderSupplement'
import { getOrderUrl, markSending } from '../api/orderSupplement'

const props = defineProps<{
  groupId: number
  records: OrderSupplement[]
}>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'refresh'): void }>()

const qrUrl = ref('')

onMounted(async () => {
  try {
    const res = await getOrderUrl(props.groupId)
    if (res.success && res.url) {
      qrUrl.value = `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(res.url)}`
    }
  } catch {}
})

function exportExcel() {
  window.open(`/api/order-supplement/export-excel?groupId=${props.groupId}`, '_blank')
}

async function markAsSending() {
  try {
    await markSending(props.groupId)
    emit('refresh')
    emit('close')
  } catch (e: any) { alert('操作失败: ' + e.message) }
}

function openTaobao(pid: string, sku?: string) {
  const url = sku ? `https://item.taobao.com/item.htm?id=${pid}&skuId=${sku}` : `https://item.taobao.com/item.htm?id=${pid}`
  window.open(url, '_blank')
}

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
                <th>商品标题</th>
                <th>商品ID</th>
                <th>SKU ID</th>
                <th>链接</th>
                <th>价格</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(r, i) in records" :key="i">
                <td>{{ r.productName || '-' }}</td>
                <td>{{ r.productId || '-' }}</td>
                <td>{{ r.skuId || '-' }}</td>
                <td><button class="link-btn" @click="openTaobao(r.productId, r.skuId)" title="打开商品链接">🔗</button></td>
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
        <button class="btn-export" @click="exportExcel">导出Excel</button>
        <button class="btn-sending" @click="markAsSending">已发给刷手</button>
        <button class="btn-cancel" @click="emit('close')">关闭</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-content { background: #fff; border-radius: 12px; overflow: hidden; width: 90%; max-height: 85vh; display: flex; flex-direction: column; }
.modal-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 24px; border-bottom: 1px solid #f0f0f0; }
.modal-header h3 { font-size: 16px; font-weight: 600; }
.close-btn { background: none; border: none; font-size: 24px; cursor: pointer; color: #999; }
.modal-footer { padding: 12px 24px; border-top: 1px solid #f0f0f0; display: flex; justify-content: center; gap: 12px; }
.btn-cancel { padding: 8px 20px; border: 1px solid #d9d9d9; border-radius: 6px; background: #fff; cursor: pointer; }
.btn-export { padding: 8px 20px; border: none; border-radius: 6px; background: #52c41a; color: #fff; cursor: pointer; font-weight: 600; }
.btn-sending { padding: 8px 20px; border: none; border-radius: 6px; background: #1890ff; color: #fff; cursor: pointer; font-weight: 600; }
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
.link-btn { padding: 2px 6px; border: 1px solid #1890ff; border-radius: 4px; background: #e6f7ff; color: #1890ff; cursor: pointer; font-size: 13px; line-height: 1; }
.link-btn:hover { background: #1890ff; color: #fff; }
</style>
