<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listWhiteBgImages } from '../api/aiImage'
import type { WhiteBgImage } from '../types'

// 接收替换图列表 + 商品目录
const props = defineProps<{ productDir: string; replaceImages: string[] }>()
// 返回：每个替换图对应一个选中白底图的URL数组
const emit = defineEmits<{ (e: 'confirm', selected: string[][]): void; (e: 'close'): void }>()

const whiteBgList = ref<WhiteBgImage[]>([])
// 二维数组：selectedSet[idx] = 该替换图选中的白底图 path 集合
const selectedSets = ref<Set<string>[]>([])

onMounted(async () => {
  try {
    const data = await listWhiteBgImages(props.productDir)
    whiteBgList.value = data.images || []
    // 初始化：每个替换图默认全选
    selectedSets.value = props.replaceImages.map(() => {
      const s = new Set<string>()
      for (const img of (data.images || [])) s.add(img.path)
      return s
    })
  } catch (e) {}
})

function toggle(replaceIdx: number, img: WhiteBgImage) {
  const s = selectedSets.value[replaceIdx]
  if (!s) return
  if (s.has(img.path)) s.delete(img.path)
  else s.add(img.path)
  // 触发响应式
  selectedSets.value = [...selectedSets.value]
}

function toggleAll(replaceIdx: number) {
  const s = selectedSets.value[replaceIdx]
  if (!s) return
  if (s.size === whiteBgList.value.length) s.clear()
  else for (const img of whiteBgList.value) s.add(img.path)
  selectedSets.value = [...selectedSets.value]
}

function confirm() {
  const result: string[][] = selectedSets.value.map((s, _idx) => {
    return whiteBgList.value
      .filter(img => s.has(img.path))
      .map(img => {
        const path = encodeURIComponent(img.path)
        return `/api/ai-image/image-file?path=${path}&t=${Date.now()}`
      })
  })
  emit('confirm', result)
}
</script>
<template>
  <div class="sel-overlay" @click.self="$emit('close')">
    <div class="sel-content">
      <div class="sel-header">
        <h3>选择白底参考图</h3>
        <button class="sel-close" @click="$emit('close')">&times;</button>
      </div>
      <div class="sel-body">
        <div v-if="whiteBgList.length === 0" class="sel-empty">暂无白底图，请先生成白底图</div>
        <div v-for="(imgData, ri) in replaceImages" :key="ri" class="sel-row">
          <div class="sel-replace-preview">
            <img :src="imgData" class="sel-replace-img" />
            <div class="sel-replace-label">替换图{{ ri + 1 }}</div>
          </div>
          <div class="sel-whitebg-area">
            <div class="sel-whitebg-header">
              <label class="sel-check-all">
                <input type="checkbox"
                  :checked="selectedSets[ri]?.size === whiteBgList.length"
                  @change="toggleAll(ri)" />
                全选
              </label>
            </div>
            <div class="sel-whitebg-grid">
              <div v-for="w in whiteBgList" :key="w.path"
                :class="['sel-wb-item', { active: selectedSets[ri]?.has(w.path) }]"
                @click="toggle(ri, w)">
                <img :src="'/api/ai-image/image-file?path=' + encodeURIComponent(w.path) + '&t=' + Date.now()" />
                <div class="sel-wb-check" v-if="selectedSets[ri]?.has(w.path)">✓</div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="sel-footer">
        <button class="sel-btn-cancel" @click="$emit('close')">取消</button>
        <button class="sel-btn-confirm" @click="confirm">确认生成</button>
      </div>
    </div>
  </div>
</template>
<style scoped>
.sel-overlay {
  position: fixed; top: 0; left: 0; width: 100%; height: 100%;
  background: rgba(0,0,0,0.7); display: flex; align-items: center; justify-content: center; z-index: 1400;
}
.sel-content { background: #fff; border-radius: 12px; width: 95%; max-width: 800px; max-height: 85vh; display: flex; flex-direction: column; }
.sel-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 24px; border-bottom: 1px solid #f0f0f0; }
.sel-header h3 { font-size: 16px; font-weight: 600; }
.sel-close { background: none; border: none; font-size: 24px; cursor: pointer; color: #999; }
.sel-body { padding: 16px 24px; overflow-y: auto; flex: 1; }
.sel-empty { text-align: center; padding: 30px; color: #999; font-size: 13px; }
.sel-row { display: flex; gap: 12px; padding: 12px; border-bottom: 1px solid #f0f0f0; }
.sel-row:last-child { border-bottom: none; }
.sel-replace-preview { text-align: center; width: 100px; flex-shrink: 0; }
.sel-replace-img { width: 80px; height: 80px; object-fit: cover; border-radius: 6px; border: 1px solid #e0e0e0; }
.sel-replace-label { font-size: 11px; color: #667eea; font-weight: 600; margin-top: 4px; }
.sel-whitebg-area { flex: 1; }
.sel-whitebg-header { margin-bottom: 6px; font-size: 12px; color: #999; }
.sel-check-all { cursor: pointer; }
.sel-whitebg-grid { display: flex; gap: 8px; flex-wrap: wrap; }
.sel-wb-item {
  width: 70px; height: 70px; border: 2px solid #e0e0e0; border-radius: 6px;
  overflow: hidden; cursor: pointer; position: relative;
}
.sel-wb-item.active { border-color: #667eea; box-shadow: 0 0 0 2px rgba(102,126,234,0.3); }
.sel-wb-item img { width: 100%; height: 100%; object-fit: cover; }
.sel-wb-check {
  position: absolute; top: 2px; right: 2px; width: 18px; height: 18px;
  background: #667eea; color: #fff; border-radius: 50%;
  display: flex; align-items: center; justify-content: center; font-size: 10px;
}
.sel-footer { padding: 12px 24px; border-top: 1px solid #f0f0f0; display: flex; justify-content: flex-end; gap: 8px; }
.sel-btn-cancel { padding: 8px 20px; border: 1px solid #d9d9d9; border-radius: 8px; background: #f5f5f5; color: #666; cursor: pointer; }
.sel-btn-confirm { padding: 8px 20px; border: none; border-radius: 8px; background: #667eea; color: #fff; cursor: pointer; font-weight: 600; }
</style>
