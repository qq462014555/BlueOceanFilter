<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listRecords, createRecord, updateRecord, deleteRecord, uploadImage, getImageUrl, fetchShops, searchResourceParties, ungroupRecord as ungroupRecordApi, bindGroup } from '../api/orderSupplement'
import QrOrderDialog from './QrOrderDialog.vue'
import { shopDisplay, STATUS_LIST } from '../types/orderSupplement'
import type { OrderSupplement, ShopInfo } from '../types/orderSupplement'
import UploadModal from './UploadModal.vue'

const shops = ref<ShopInfo[]>([])
const records = ref<OrderSupplement[]>([])
const total = ref(0)
const page = ref(1)
const size = 50
const loading = ref(false)

// 搜索条件
const searchShopId = ref('')
const searchKeyword = ref('')
const searchStatus = ref('')
const dateFrom = ref('')
const dateTo = ref('')

// 排序
const sortField = ref('date')
const sortOrder = ref('desc')

// 新增/编辑弹窗
const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = ref<OrderSupplement>({
  shopId: '',
  productName: '',
  productId: '',
  skuId: '',
  price: 0,
  reviewImage: '',
  reviewText: '',
  status: '待补单',
})

// 表单校验状态（blur 触发）
const errProductName = ref(false)
const errProductId = ref(false)
const errSkuId = ref(false)
const errPrice = ref(false)
function resetErrors() {
  errProductName.value = false; errProductId.value = false; errSkuId.value = false; errPrice.value = false
}
function checkBlur(field: string, val: any) {
  const empty = val == null || String(val).trim() === '' || (field === 'price' && Number(val) <= 0)
  if (field === 'productName') errProductName.value = empty
  else if (field === 'productId') errProductId.value = empty
  else if (field === 'skuId') errSkuId.value = empty
  else if (field === 'price') errPrice.value = empty
}

// 选择 & 归组
const selectedIds = ref<Set<number>>(new Set())
const showGroupForm = ref(false)
const groupDate = ref('')
const groupResourceParty = ref('')
const showQrDialog = ref(false)
const qrRecords = ref<OrderSupplement[]>([])
const rpSuggestions = ref<{ id: number; resource_party: string }[]>([])
const rpShowDropdown = ref(false)
const selectedGroupId = ref<number | null>(null)
let rpSearchTimer = 0
let rpProgrammatic = false
function onRpInput() {
  clearTimeout(rpSearchTimer)
  if (rpProgrammatic) { rpProgrammatic = false; return }
  selectedGroupId.value = null
  const val = groupResourceParty.value?.trim()
  if (!val) { rpSuggestions.value = []; rpShowDropdown.value = false; return }
  rpSearchTimer = window.setTimeout(async () => {
    try {
      rpSuggestions.value = await searchResourceParties(val)
      rpShowDropdown.value = rpSuggestions.value.length > 0
    } catch { rpSuggestions.value = [] }
  }, 300)
}
function selectRp(item: { id: number; resource_party: string }) {
  rpProgrammatic = true
  groupResourceParty.value = item.id + ' - ' + item.resource_party
  selectedGroupId.value = item.id
  rpShowDropdown.value = false
}
function onRpBlur() {
  setTimeout(() => rpShowDropdown.value = false, 200)
}
const formMsg = ref('')
let formMsgTimer = 0

// 确认弹窗
const confirmMsg = ref('')
let confirmCb: (() => void) | null = null
function showConfirm(msg: string, cb: () => void) {
  confirmMsg.value = msg; confirmCb = cb
}
function setFormMsg(msg: string, isErr = true) {
  formMsg.value = (isErr ? '❌ ' : '✅ ') + msg
  clearTimeout(formMsgTimer); formMsgTimer = window.setTimeout(() => formMsg.value = '', 3000)
}

// 上传图片相关
const uploadRef = ref<InstanceType<typeof UploadModal>>()
const imagePaths = ref<string[]>([])

onMounted(async () => {
  try { shops.value = await fetchShops() } catch {}
  fetchList()
})

async function fetchList() {
  loading.value = true
  try {
    const data = await listRecords({
      page: page.value, size,
      shopId: searchShopId.value || undefined,
      dateFrom: dateFrom.value || undefined,
      dateTo: dateTo.value || undefined,
      keyword: searchKeyword.value || undefined,
      status: searchStatus.value || undefined,
      sortField: sortField.value,
      sortOrder: sortOrder.value,
    })
    records.value = data.records
    total.value = data.total
  } catch (e: any) { /* ignore */ }
  loading.value = false
}

// 快捷日期
function setQuickDate(days: number) {
  const now = new Date()
  const to = now.toISOString().slice(0, 10)
  const from = new Date(now.getTime() - days * 86400000).toISOString().slice(0, 10)
  dateFrom.value = from
  dateTo.value = to
}

// 表头排序切换
function toggleSort(field: string) {
  if (sortField.value === field) {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortField.value = field
    sortOrder.value = 'asc'
  }
  fetchList()
}
function sortIcon(field: string) {
  if (sortField.value !== field) return '↕'
  return sortOrder.value === 'asc' ? '↑' : '↓'
}

// 分页
function goPage(p: number) { page.value = p; fetchList() }
// 新增
function openAdd() {
  editingId.value = null
  resetErrors()
  // 尝试恢复缓存的表单数据
  const cached = localStorage.getItem('order_supplement_form_cache')
  if (cached) {
    try {
      const parsed = JSON.parse(cached)
      form.value = { ...form.value, ...parsed }
    } catch {}
    localStorage.removeItem('order_supplement_form_cache')
  } else {
    const defaultShop = shops.value[0] ? shopDisplay(shops.value[0]) : ''
    form.value = { shopId: defaultShop, productName: '', productId: '', skuId: '', price: null as any, reviewImage: '', reviewText: '', status: '待补单' }
  }
  imagePaths.value = []
  showForm.value = true
}

// 关闭表单时缓存当前输入
function closeForm() {
  if (!editingId.value) {
    localStorage.setItem('order_supplement_form_cache', JSON.stringify(form.value))
  }
  showForm.value = false
}

// 编辑
function openEdit(row: OrderSupplement) {
  editingId.value = row.id!
  resetErrors()
  form.value = { ...row }
  imagePaths.value = row.reviewImage ? row.reviewImage.split(',').filter(s => s.trim()) : []
  showForm.value = true
}

// 保存
async function saveForm() {
  // 必填校验
  resetErrors()
  const missing: string[] = []
  if (!form.value.productName?.trim()) { missing.push('商品名称'); errProductName.value = true }
  if (!form.value.productId?.trim()) { missing.push('商品ID'); errProductId.value = true }
  if (!form.value.skuId?.trim()) { missing.push('商品SKU ID'); errSkuId.value = true }
  if (form.value.price == null || form.value.price === '' as any) { missing.push('价格'); errPrice.value = true }
  if (missing.length) { setFormMsg('请填写：' + missing.join('、')); return }

  try {
    const payload = { ...form.value }
    payload.reviewImage = imagePaths.value.join(',')
    if (editingId.value) {
      await updateRecord(payload)
    } else {
      await createRecord(payload)
    }
    localStorage.removeItem('order_supplement_form_cache')
    showForm.value = false
    await fetchList()
  } catch (e: any) { setFormMsg('保存失败: ' + (e as any).message) }
}

// 删除
function removeRecord(id: number) {
  showConfirm('确定删除该记录吗？', async () => {
    try {
      await deleteRecord(id)
      await fetchList()
    } catch (e: any) { setFormMsg('删除失败: ' + (e as any).message) }
  })
}
function ungroupRecord(id: number) {
  showConfirm('确定取消该记录的组绑定吗？', async () => {
    try {
      await ungroupRecordApi(id)
      await fetchList()
    } catch (e: any) { setFormMsg('取消绑定失败: ' + (e as any).message) }
  })
}
function confirmOk() {
  if (confirmCb) confirmCb()
  confirmMsg.value = ''; confirmCb = null
}

// 上传评论图
function openUpload() { uploadRef.value?.open() }
function onUploadConfirm(data: string) {
  const shopId = form.value.shopId || (shops.value[0] ? shopDisplay(shops.value[0]) : '')
  const name = form.value.productName || 'unknown'
  uploadImage({ image: data, shopId, productName: name }).then(res => {
    if (res.success && res.path) {
      imagePaths.value.push(res.path)
    }
  }).catch(() => {})
}

// 删除某张评论图
function removeImage(idx: number) {
  imagePaths.value.splice(idx, 1)
}

// 图片URL + 尺寸解析
function parseImagePath(path: string) {
  const parts = path.split('|')
  return { url: getImageUrl(parts[0]), dimension: parts[1] || '' }
}

// 预览图片
function previewImage(url: string) {
  window.open(url, '_blank')
}

// 打开淘宝商品链接
function openTaobaoUrl() {
  const id = form.value.productId?.trim()
  if (id) {
    const sku = form.value.skuId?.trim()
    const url = sku ? `https://item.taobao.com/item.htm?id=${id}&skuId=${sku}` : `https://item.taobao.com/item.htm?id=${id}`
    window.open(url, '_blank')
  }
}

// 选择/取消选择
function toggleSelect(id: number) {
  const s = new Set(selectedIds.value)
  if (s.has(id)) s.delete(id); else s.add(id)
  selectedIds.value = s
}
function toggleSelectAll() {
  const selectable = records.value.filter(r => !r.groupId)
  if (selectedIds.value.size === selectable.length) {
    selectedIds.value = new Set()
  } else {
    selectedIds.value = new Set(selectable.map(r => r.id!))
  }
}

// 归组
import { createGroup as apiCreateGroup } from '../api/orderSupplement'
function openGroupForm() {
  if (selectedIds.value.size === 0) { alert('请先勾选要归组的记录'); return }
  groupDate.value = new Date().toISOString().slice(0, 10)
  groupResourceParty.value = ''
  selectedGroupId.value = null
  showGroupForm.value = true
}
async function confirmGroup() {
  if (!groupDate.value) { setFormMsg('请选择日期'); return }
  if (!groupResourceParty.value.trim()) { setFormMsg('请填写资源方'); return }
  try {
    const ids = Array.from(selectedIds.value)
    if (selectedGroupId.value) {
      // 绑定到已有组
      await bindGroup({ recordIds: ids, groupId: selectedGroupId.value })
    } else {
      // 创建新组并绑定
      await apiCreateGroup({ recordIds: ids, date: groupDate.value || undefined, resourceParty: groupResourceParty.value })
    }
    showGroupForm.value = false
    selectedIds.value = new Set()
    selectedGroupId.value = null
    await fetchList()
  } catch (e: any) { setFormMsg('归组失败: ' + (e as any).message) }
}
</script>

<template>
  <div class="order-supplement">
    <!-- 搜索栏 -->
    <div class="search-bar">
      <select v-model="searchShopId">
        <option value="">全部店铺</option>
        <option v-for="s in shops" :key="s.shopId + s.platform" :value="shopDisplay(s)">{{ shopDisplay(s) }}</option>
      </select>
      <div class="date-range">
        <button :class="{ active: dateFrom && (new Date().getTime() - new Date(dateFrom).getTime()) === 6 * 86400000 }" @click="setQuickDate(7)">近一周</button>
        <button :class="{ active: dateFrom && (new Date().getTime() - new Date(dateFrom).getTime()) === 29 * 86400000 }" @click="setQuickDate(30)">近一月</button>
        <input type="date" v-model="dateFrom" class="date-input" />
        <span>~</span>
        <input type="date" v-model="dateTo" class="date-input" />
      </div>
      <input v-model="searchKeyword" placeholder="商品名称/商品ID/SKU ID" class="search-input" @keyup.enter="fetchList" />
      <select v-model="searchStatus">
        <option value="">全部状态</option>
        <option v-for="st in STATUS_LIST" :key="st" :value="st">{{ st }}</option>
      </select>
      <button class="btn-search" @click="fetchList">搜索</button>
    </div>

    <!-- 操作栏 -->
    <div class="action-bar">
      <button class="btn-add" @click="openAdd">+ 新增</button>
      <span style="flex:1"></span>
      <button class="btn-group" @click="openGroupForm" :disabled="selectedIds.size === 0">归组 ({{ selectedIds.size }})</button>
    </div>

    <!-- 表格 -->
    <div class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th><input type="checkbox" :checked="records.length > 0 && selectedIds.size === records.length" @change="toggleSelectAll" /></th>
            <th @click="toggleSort('shopId')">店铺ID {{ sortIcon('shopId') }}</th>
            <th @click="toggleSort('date')">日期 {{ sortIcon('date') }}</th>
            <th @click="toggleSort('productName')">商品名称 {{ sortIcon('productName') }}</th>
            <th @click="toggleSort('productId')">商品ID {{ sortIcon('productId') }}</th>
            <th @click="toggleSort('skuId')">SKU ID {{ sortIcon('skuId') }}</th>
            <th @click="toggleSort('price')">价格 {{ sortIcon('price') }}</th>
            <th>评论</th>
            <th>资源方</th>
            <th @click="toggleSort('status')">状态 {{ sortIcon('status') }}</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="11" class="td-loading">加载中...</td></tr>
          <tr v-else-if="records.length === 0"><td colspan="11" class="td-empty">暂无数据</td></tr>
          <tr v-for="row in records" :key="row.id">
            <td>
              <span v-if="row.groupId" class="chk-disabled">✕</span>
              <input v-else type="checkbox" :checked="selectedIds.has(row.id!)" @change="toggleSelect(row.id!)" />
            </td>
            <td>{{ row.shopId || '-' }}</td>
            <td>{{ row.date || '-' }}</td>
            <td>{{ row.productName || '-' }}</td>
            <td>{{ row.productId || '-' }}</td>
            <td>{{ row.skuId || '-' }}</td>
            <td>{{ row.price }}</td>
            <td class="td-review">
              <div class="review-text">{{ row.reviewText || '-' }}</div>
              <div class="review-images">
                <div v-for="(img, idx) in (row.reviewImage || '').split(',').filter(Boolean)" :key="idx" class="img-cell">
                  <img :src="parseImagePath(img).url" class="thumb" @mouseenter="(e) => { const t = e.target as HTMLImageElement; t.style.transform = 'scale(3)'; t.style.zIndex = '10' }"
                    @mouseleave="(e) => { const t = e.target as HTMLImageElement; t.style.transform = 'scale(1)'; t.style.zIndex = '1' }"
                    @click="previewImage(parseImagePath(img).url)" />
                  <div class="img-size">{{ parseImagePath(img).dimension }}</div>
                </div>
              </div>
            </td>
            <td>{{ row.groupId && row.resourceParty ? (row.groupId + ' - ' + row.resourceParty) : (row.resourceParty || '-') }}</td>
            <td><span :class="'status-tag status-' + row.status">{{ row.status }}</span></td>
            <td class="td-actions">
              <button class="btn-edit" @click="openEdit(row)">编辑</button>
              <button class="btn-del" @click="removeRecord(row.id!)">删除</button>
              <button v-if="row.groupId" class="btn-ungroup" @click="ungroupRecord(row.id!)">取消绑定组</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分页 -->
    <div class="pagination" v-if="total > size">
      <span>共 {{ total }} 条</span>
      <button :disabled="page <= 1" @click="goPage(page - 1)">上一页</button>
      <span>第 {{ page }}/{{ Math.ceil(total / size) }} 页</span>
      <button :disabled="page >= Math.ceil(total / size)" @click="goPage(page + 1)">下一页</button>
    </div>

    <!-- 新增/编辑弹窗 -->
    <div v-if="showForm" class="modal-overlay">
      <div class="modal-content form-modal">
        <h3>{{ editingId ? '编辑' : '新增' }}补单记录</h3>
        <div class="form-grid">
          <label>店铺ID <select v-model="form.shopId"><option v-for="s in shops" :key="s.shopId + s.platform" :value="shopDisplay(s)">{{ shopDisplay(s) }}</option></select></label>
          <label><div class="label-text"><span class="required">*</span>商品名称</div><input v-model="form.productName" @blur="checkBlur('productName', form.productName)" :class="{ 'input-err': errProductName }" /><span v-if="errProductName" class="err-msg">必填</span></label>
          <label><div class="label-text"><span class="required">*</span>商品ID</div><input v-model="form.productId" @blur="checkBlur('productId', form.productId)" :class="{ 'input-err': errProductId }" /><span v-if="errProductId" class="err-msg">必填</span></label>
          <label><div class="label-text"><span class="required">*</span>商品SKU ID</div><div class="input-with-btn"><input v-model="form.skuId" @blur="checkBlur('skuId', form.skuId)" :class="{ 'input-err': errSkuId }" /><button v-if="form.productId?.trim()" class="btn-open-url" title="用浏览器打开商品链接" @click="openTaobaoUrl">🔗</button></div><span v-if="errSkuId" class="err-msg">必填</span></label>
          <label><div class="label-text"><span class="required">*</span>价格</div><input type="number" v-model="form.price" @blur="checkBlur('price', form.price)" :class="{ 'input-err': errPrice }" /><span v-if="errPrice" class="err-msg">必填</span></label>
          <label class="full-width">评论文本 <textarea v-model="form.reviewText" rows="3"></textarea></label>
          <label class="full-width">
            评论图片
            <div class="upload-area">
              <button class="btn-upload" @click="openUpload">+ 添加图片</button>
              <div class="uploaded-images">
                <div v-for="(img, idx) in imagePaths" :key="idx" class="uploaded-img-item">
                  <img :src="getImageUrl(img)" class="uploaded-thumb" />
                  <span class="img-dim">{{ parseImagePath(img).dimension }}</span>
                  <button class="img-remove" @click="removeImage(idx)">×</button>
                </div>
              </div>
            </div>
          </label>
          <label>状态 <select v-model="form.status"><option v-for="st in STATUS_LIST" :key="st" :value="st">{{ st }}</option></select></label>
        </div>
        <div class="form-actions">
          <span class="form-msg" :class="{ 'form-msg-err': formMsg.startsWith('❌') }">{{ formMsg }}</span>
          <button class="btn-cancel" @click="closeForm">取消</button>
          <button class="btn-save" @click="saveForm">保存</button>
        </div>
      </div>
    </div>

    <!-- 归组对话框 -->
    <div v-if="showGroupForm" class="modal-overlay">
      <div class="modal-content" style="max-width: 420px;">
        <h3>归组</h3>
        <p style="margin-bottom:12px;color:#999;font-size:13px;">已选择 {{ selectedIds.size }} 条记录</p>
        <div class="form-grid">
          <label>日期 <input type="date" v-model="groupDate" /></label>
          <label class="full-width">资源方
            <div class="rp-autocomplete">
              <input v-model="groupResourceParty" placeholder="输入资源方名称，下拉可选已有组" @input="onRpInput" @focus="onRpInput" @blur="onRpBlur" />
              <div v-if="rpShowDropdown" class="rp-dropdown">
                <div v-for="item in rpSuggestions" :key="item.id" class="rp-item" @mousedown.prevent="selectRp(item)">{{ item.id }} - {{ item.resource_party }}</div>
              </div>
            </div>
            <span v-if="selectedGroupId" class="rp-hint">已选组 #{{ selectedGroupId }}，将直接绑定到此组</span>
            <span v-else class="rp-hint">将创建新组并绑定</span>
          </label>
        </div>
        <div class="form-actions">
          <span class="form-msg" :class="{ 'form-msg-err': formMsg.startsWith('❌') }">{{ formMsg }}</span>
          <button class="btn-cancel" @click="showGroupForm = false">取消</button>
          <button class="btn-save" @click="confirmGroup">确认归组</button>
        </div>
      </div>
    </div>

    <!-- 确认弹窗 -->
    <div v-if="confirmMsg" class="modal-overlay" @click.self="confirmMsg = ''">
      <div class="modal-content" style="max-width: 380px; text-align: center;">
        <p style="font-size:15px;margin:20px 0;">{{ confirmMsg }}</p>
        <div class="form-actions" style="justify-content: center;">
          <button class="btn-cancel" @click="confirmMsg = ''">取消</button>
          <button class="btn-save" style="background:#ff4d4f;" @click="confirmOk">确定</button>
        </div>
      </div>
    </div>

    <UploadModal ref="uploadRef" @confirm="onUploadConfirm" />
  </div>
</template>

<style scoped>
.order-supplement { font-size: 13px; }
.search-bar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 12px; }
.search-bar select, .search-bar input { padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; outline: none; }
.search-bar select:focus, .search-bar input:focus { border-color: #667eea; }
.date-range { display: flex; align-items: center; gap: 4px; }
.date-range button { padding: 4px 10px; border: 1px solid #d9d9d9; border-radius: 4px; background: #fff; cursor: pointer; font-size: 12px; }
.date-range button.active { background: #667eea; color: #fff; border-color: #667eea; }
.date-input { width: 130px; }
.search-input { width: 200px; }
.btn-search { padding: 6px 16px; border: none; border-radius: 6px; background: #667eea; color: #fff; cursor: pointer; font-weight: 600; }
.btn-add { padding: 6px 16px; border: none; border-radius: 6px; background: #52c41a; color: #fff; cursor: pointer; font-weight: 600; }
.btn-group { padding: 6px 16px; border: none; border-radius: 6px; background: #fa8c16; color: #fff; cursor: pointer; font-weight: 600; }
.action-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.btn-group:disabled { background: #d9d9d9; cursor: not-allowed; }

.table-wrap { overflow-x: auto; }
.data-table { width: 100%; border-collapse: collapse; white-space: nowrap; }
.data-table th { background: #fafafa; padding: 10px 12px; text-align: left; font-weight: 600; cursor: pointer; user-select: none; border-bottom: 2px solid #e8e8e8; }
.data-table th:hover { background: #f0f0f0; }
.data-table td { padding: 10px 12px; border-bottom: 1px solid #f0f0f0; vertical-align: middle; text-align: center; }
.data-table td.td-review { text-align: center; }
.td-review .review-text { text-align: left; }
.td-loading, .td-empty { text-align: center; color: #999; padding: 30px !important; }

.td-review { max-width: 450px; white-space: normal; word-break: break-word; overflow-wrap: break-word; }
.review-text { word-break: break-word; overflow-wrap: break-word; line-height: 1.5; max-height: 80px; overflow-y: auto; }
.review-text { margin-bottom: 6px; font-size: 12px; color: #333; }
.review-images { display: flex; gap: 6px; flex-wrap: wrap; }
.img-cell { text-align: center; }
.thumb { width: 50px; height: 50px; object-fit: cover; border-radius: 4px; border: 1px solid #e0e0e0; cursor: pointer; transition: transform 0.15s; position: relative; }
.thumb:hover { position: relative; z-index: 10; border-color: #667eea; box-shadow: 0 4px 12px rgba(0,0,0,0.2); }
.img-size { font-size: 10px; color: #999; margin-top: 2px; }

.status-tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.status-待补单 { background: #fff7e6; color: #fa8c16; border: 1px solid #ffd591; }
.status-已补单 { background: #f6ffed; color: #52c41a; border: 1px solid #b7eb8f; }
.status-取消补单 { background: #fff1f0; color: #ff4d4f; border: 1px solid #ffa39e; }

.td-actions { display: flex; gap: 4px; }
.btn-edit { padding: 2px 10px; border: 1px solid #667eea; border-radius: 4px; background: #fff; color: #667eea; cursor: pointer; font-size: 12px; }
.btn-del { padding: 2px 10px; border: 1px solid #ff4d4f; border-radius: 4px; background: #fff; color: #ff4d4f; cursor: pointer; font-size: 12px; }
.btn-ungroup { padding: 2px 10px; border: 1px solid #fa8c16; border-radius: 4px; background: #fff; color: #fa8c16; cursor: pointer; font-size: 12px; }

.pagination { display: flex; align-items: center; gap: 12px; justify-content: center; margin-top: 12px; padding: 10px 0; font-size: 13px; color: #666; }
.pagination button { padding: 4px 12px; border: 1px solid #d9d9d9; border-radius: 4px; background: #fff; cursor: pointer; }
.pagination button:disabled { opacity: 0.5; cursor: not-allowed; }

.modal-overlay { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-content { background: #fff; border-radius: 12px; padding: 24px; max-width: 700px; width: 90%; max-height: 85vh; overflow-y: auto; }
.modal-content h3 { font-size: 16px; margin-bottom: 16px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-grid label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #666; }
.form-grid label.full-width { grid-column: 1 / -1; }
.label-text { display: flex; align-items: center; gap: 2px; }
.form-grid input, .form-grid select, .form-grid textarea { padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; outline: none; }
.form-grid input:focus, .form-grid select:focus, .form-grid textarea:focus { border-color: #667eea; }
.form-grid .input-err { border-color: #ff4d4f !important; background: #fff2f0; }
.input-with-btn { display: flex; gap: 4px; align-items: center; }
.input-with-btn input { flex: 1; }
.btn-open-url { padding: 4px 8px; border: 1px solid #1890ff; border-radius: 4px; background: #e6f7ff; color: #1890ff; cursor: pointer; font-size: 14px; line-height: 1; }
.btn-open-url:hover { background: #1890ff; color: #fff; }
.rp-autocomplete { position: relative; }
.rp-dropdown { position: absolute; top: 100%; left: 0; right: 0; background: #fff; border: 1px solid #d9d9d9; border-radius: 6px; max-height: 200px; overflow-y: auto; z-index: 10; box-shadow: 0 4px 12px rgba(0,0,0,0.1); }
.rp-item { padding: 8px 12px; font-size: 13px; cursor: pointer; border-bottom: 1px solid #f5f5f5; }
.rp-item:last-child { border-bottom: none; }
.rp-item:hover { background: #f0f0ff; color: #667eea; }
.rp-hint { font-size: 11px; color: #999; margin-top: 2px; }
.chk-disabled { display: inline-flex; align-items: center; justify-content: center; width: 16px; height: 16px; background: #f0f0f0; border-radius: 3px; color: #ccc; font-size: 12px; font-weight: 700; cursor: not-allowed; }
.required { color: #ff4d4f; margin-right: 2px; font-weight: 700; }
.err-msg { font-size: 11px; color: #ff4d4f; margin-top: 2px; }
.form-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px; }
.btn-cancel { padding: 8px 20px; border: 1px solid #d9d9d9; border-radius: 6px; background: #fff; cursor: pointer; }
.btn-save { padding: 8px 20px; border: none; border-radius: 6px; background: #667eea; color: #fff; cursor: pointer; font-weight: 600; }
.form-msg { flex: 1; font-size: 12px; text-align: left; }
.form-msg-err { color: #ff4d4f; }
.btn-upload { padding: 4px 12px; border: 1px dashed #667eea; border-radius: 4px; background: #f8f9ff; color: #667eea; cursor: pointer; font-size: 12px; }
.upload-area { display: flex; flex-direction: column; gap: 6px; }
.uploaded-images { display: flex; gap: 6px; flex-wrap: wrap; }
.uploaded-img-item { position: relative; text-align: center; }
.uploaded-thumb { width: 60px; height: 60px; object-fit: cover; border-radius: 4px; border: 1px solid #e0e0e0; }
.img-dim { display: block; font-size: 10px; color: #999; margin-top: 1px; }
.img-remove { position: absolute; top: -4px; right: -4px; width: 16px; height: 16px; border-radius: 50%; border: none; background: #ff4d4f; color: #fff; font-size: 10px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
</style>
