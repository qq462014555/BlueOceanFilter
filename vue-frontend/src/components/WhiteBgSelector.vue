<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listWhiteBgImages } from '../api/aiImage'
import type { WhiteBgImage } from '../types'

const props = defineProps<{ productDir: string }>()
const emit = defineEmits<{ (e: 'confirm', selected: WhiteBgImage[]): void; (e: 'close'): void }>()

const images = ref<WhiteBgImage[]>([])
const selected = ref<Set<string>>(new Set())

onMounted(async () => {
  try {
    const data = await listWhiteBgImages(props.productDir)
    images.value = data.images || []
    // 默认全选
    for (const img of images.value) selected.value.add(img.path)
  } catch (e) {}
})

function toggle(img: WhiteBgImage) {
  if (selected.value.has(img.path)) selected.value.delete(img.path)
  else selected.value.add(img.path)
}

function toggleAll() {
  if (selected.value.size === images.value.length) selected.value.clear()
  else for (const img of images.value) selected.value.add(img.path)
}

function confirm() {
  const sel = images.value.filter(img => selected.value.has(img.path))
  emit('confirm', sel)
}
</script>
<template>
  <div class="selector-overlay" @click.self="$emit('close')">
    <div class="selector-content">
      <div class="selector-header">
        <h3>选择参考图 - 白底图</h3>
        <button class="close-btn" @click="$emit('close')">&times;</button>
      </div>
      <div class="selector-body">
        <div v-if="images.length === 0" class="empty">暂无白底图，请先生成白底图</div>
        <div class="select-all" v-if="images.length > 0">
          <label><input type="checkbox" :checked="selected.size === images.length" @change="toggleAll" /> 全选/取消</label>
        </div>
        <div class="image-list">
          <div v-for="img in images" :key="img.path"
            :class="['image-item', { selected: selected.has(img.path) }]"
            @click="toggle(img)">
            <img :src="'/api/ai-image/image-file?path=' + encodeURIComponent(img.path) + '&t=' + Date.now()" />
            <div class="check-mark" v-if="selected.has(img.path)">✓</div>
          </div>
        </div>
      </div>
      <div class="selector-footer">
        <button class="btn-cancel" @click="$emit('close')">取消</button>
        <button class="btn-confirm" @click="confirm" :disabled="selected.size === 0">
          确认选择 ({{ selected.size }})
        </button>
      </div>
    </div>
  </div>
</template>
<style scoped>
.selector-overlay {
  position: fixed; top: 0; left: 0; width: 100%; height: 100%;
  background: rgba(0,0,0,0.7); display: flex; align-items: center; justify-content: center; z-index: 1400;
}
.selector-content { background: #fff; border-radius: 12px; width: 90%; max-width: 600px; max-height: 80vh; display: flex; flex-direction: column; }
.selector-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 24px; border-bottom: 1px solid #f0f0f0; }
.selector-header h3 { font-size: 16px; font-weight: 600; }
.close-btn { background: none; border: none; font-size: 24px; cursor: pointer; color: #999; }
.selector-body { padding: 16px 24px; overflow-y: auto; flex: 1; }
.empty { text-align: center; padding: 30px; color: #999; font-size: 13px; }
.select-all { margin-bottom: 10px; font-size: 13px; color: #667eea; cursor: pointer; }
.image-list { display: flex; gap: 10px; flex-wrap: wrap; }
.image-item {
  width: 100px; height: 100px; border: 2px solid #e0e0e0; border-radius: 8px;
  overflow: hidden; cursor: pointer; position: relative;
}
.image-item.selected { border-color: #667eea; box-shadow: 0 0 0 2px rgba(102,126,234,0.3); }
.image-item img { width: 100%; height: 100%; object-fit: cover; }
.check-mark {
  position: absolute; top: 4px; right: 4px; width: 20px; height: 20px;
  background: #667eea; color: #fff; border-radius: 50%;
  display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: bold;
}
.selector-footer { padding: 12px 24px; border-top: 1px solid #f0f0f0; display: flex; justify-content: flex-end; gap: 8px; }
.btn-cancel { padding: 8px 20px; border: 1px solid #d9d9d9; border-radius: 8px; background: #f5f5f5; color: #666; cursor: pointer; }
.btn-confirm { padding: 8px 20px; border: none; border-radius: 8px; background: #667eea; color: #fff; cursor: pointer; }
.btn-confirm:disabled { background: #ccc; }
</style>
