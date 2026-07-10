<script setup lang="ts">
import { ref } from "vue"

const emit = defineEmits<{ (e: 'confirm', data: string): void }>()
const show = ref(false)
const tab = ref<'local' | 'url' | 'paste'>('local')
const fileInput = ref<HTMLInputElement>()
const urlInput = ref('')
const pasteTip = ref('Ctrl+V 粘贴图片或URL')
const pasteData = ref('')

function open() {
  show.value = true
  tab.value = 'local'
  urlInput.value = ''
  pasteTip.value = 'Ctrl+V 粘贴图片或URL'
  pasteData.value = ''
}

function close() {
  show.value = false
}

async function confirm() {
  if (tab.value === 'local' && fileInput.value?.files?.[0]) {
    const reader = new FileReader()
    reader.onload = (e) => { emit('confirm', e.target?.result as string); close() }
    reader.readAsDataURL(fileInput.value.files[0])
  } else if (tab.value === 'url' && urlInput.value.trim()) {
    emit('confirm', urlInput.value.trim())
    close()
  } else if (tab.value === 'paste' && pasteData.value) {
    emit('confirm', pasteData.value)
    close()
  }
}

function onPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      // 粘贴的是图片
      const file = item.getAsFile()
      if (!file) continue
      const reader = new FileReader()
      reader.onload = (ev) => {
        pasteData.value = ev.target?.result as string
        pasteTip.value = '✅ 已粘贴图片'
      }
      reader.readAsDataURL(file)
      return
    }
  }
  // 尝试作为 URL
  const text = e.clipboardData?.getData('text')
  if (text && (text.startsWith('http://') || text.startsWith('https://') || text.startsWith('data:image'))) {
    pasteData.value = text
    pasteTip.value = '✅ 已粘贴URL'
  } else if (text) {
    pasteTip.value = '⚠️ 粘贴内容不是图片或URL'
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

defineExpose({ open, close })
</script>
<template>
  <div v-if="show" class="modal-overlay" @click.self="close" @keydown="onKeydown" @paste="onPaste" tabindex="0">
    <div class="modal-content">
      <div class="modal-header">
        <h3>上传图片</h3>
        <button class="close-btn" @click="close">&times;</button>
      </div>
      <div class="modal-tabs">
        <button :class="['tab', { active: tab === 'local' }]" @click="tab = 'local'">本地上传</button>
        <button :class="['tab', { active: tab === 'url' }]" @click="tab = 'url'">图片URL</button>
        <button :class="['tab', { active: tab === 'paste' }]" @click="tab = 'paste'">粘贴图片</button>
      </div>
      <div class="modal-body">
        <div v-if="tab === 'local'">
          <input ref="fileInput" type="file" accept="image/*" class="file-input" />
        </div>
        <div v-else-if="tab === 'url'">
          <input v-model="urlInput" placeholder="粘贴图片链接..." class="url-input" />
        </div>
        <div v-else class="paste-area" @paste="onPaste">
          <div class="paste-icon">📋</div>
          <div class="paste-tip">{{ pasteTip }}</div>
          <div v-if="pasteData && pasteData.startsWith('data:image')" class="paste-preview">
            <img :src="pasteData" class="paste-img" />
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn-cancel" @click="close">取消</button>
        <button class="btn-confirm" @click="confirm" :disabled="tab === 'paste' && !pasteData">确认上传</button>
      </div>
    </div>
  </div>
</template>
<style scoped>
.modal-overlay {
  position: fixed; top: 0; left: 0; width: 100%; height: 100%;
  background: rgba(0,0,0,0.7); display: flex; align-items: center; justify-content: center; z-index: 1300; outline: none;
}
.modal-content { background: #fff; border-radius: 12px; width: 90%; max-width: 500px; overflow: hidden; }
.modal-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 24px; border-bottom: 1px solid #f0f0f0; }
.modal-header h3 { font-size: 16px; font-weight: 600; }
.close-btn { background: none; border: none; font-size: 24px; cursor: pointer; color: #999; }
.modal-tabs { display: flex; border-bottom: 1px solid #f0f0f0; }
.modal-tabs .tab { flex: 1; padding: 12px; text-align: center; font-size: 13px; font-weight: 600; cursor: pointer; color: #999; border-bottom: 2px solid transparent; background: none; border-top: none; border-left: none; border-right: none; }
.modal-tabs .tab.active { color: #ff6a00; border-bottom-color: #ff6a00; }
.modal-body { padding: 24px; min-height: 80px; }
.file-input { width: 100%; padding: 8px; }
.url-input { width: 100%; padding: 8px 12px; border: 2px solid #e0e0e0; border-radius: 8px; font-size: 13px; outline: none; }
.paste-area { text-align: center; padding: 20px; border: 2px dashed #d9d9d9; border-radius: 8px; cursor: pointer; }
.paste-icon { font-size: 32px; margin-bottom: 8px; }
.paste-tip { font-size: 13px; color: #999; }
.paste-preview { margin-top: 12px; }
.paste-img { max-width: 200px; max-height: 200px; border-radius: 6px; border: 1px solid #e0e0e0; }
.modal-footer { padding: 12px 24px; border-top: 1px solid #f0f0f0; display: flex; justify-content: flex-end; gap: 8px; }
.btn-cancel { padding: 8px 20px; border: 1px solid #d9d9d9; border-radius: 8px; background: #f5f5f5; color: #666; cursor: pointer; }
.btn-confirm { padding: 8px 20px; border: none; border-radius: 8px; background: #667eea; color: #fff; cursor: pointer; }
.btn-confirm:disabled { background: #ccc; cursor: not-allowed; }
</style>
