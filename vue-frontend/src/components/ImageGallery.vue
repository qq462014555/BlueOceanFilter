<script setup lang="ts">
import { ref } from 'vue'

const show = ref(false)
const src = ref('')
const images = ref<string[]>([])
const index = ref(0)

function open(url: string, allImages?: string[], idx?: number) {
  src.value = url
  images.value = allImages || []
  index.value = idx ?? 0
  show.value = true
}

function close() { show.value = false }

function prev() {
  if (images.value.length > 0 && index.value > 0) {
    index.value--
    src.value = images.value[index.value]
  }
}

function next() {
  if (images.value.length > 0 && index.value < images.value.length - 1) {
    index.value++
    src.value = images.value[index.value]
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
  if (e.key === 'ArrowLeft') prev()
  if (e.key === 'ArrowRight') next()
}

defineExpose({ open, close })
</script>
<template>
  <div v-if="show" class="gallery-overlay" @click.self="close" @keydown="onKeydown" tabindex="0">
    <span v-if="images.length > 0 && index > 0" class="nav nav-left" @click="prev">&#10094;</span>
    <img :src="src" class="gallery-img" @click="close" />
    <span v-if="images.length > 0 && index < images.length - 1" class="nav nav-right" @click="next">&#10095;</span>
    <span v-if="images.length > 1" class="counter">{{ index + 1 }} / {{ images.length }}</span>
  </div>
</template>
<style scoped>
.gallery-overlay {
  position: fixed; top: 0; left: 0; width: 100%; height: 100%;
  background: rgba(0,0,0,0.8); display: flex; align-items: center; justify-content: center;
  z-index: 9999; cursor: pointer;
}
.gallery-img { max-width: 90%; max-height: 90%; object-fit: contain; border-radius: 8px; }
.nav {
  position: absolute; top: 50%; transform: translateY(-50%);
  font-size: 40px; color: #fff; cursor: pointer; padding: 20px;
  user-select: none; opacity: 0.6; z-index: 2001;
}
.nav:hover { opacity: 1; }
.nav-left { left: 10px; }
.nav-right { right: 10px; }
.counter {
  position: absolute; bottom: 20px; left: 50%; transform: translateX(-50%);
  color: #fff; font-size: 14px; opacity: 0.7; z-index: 2001;
}
</style>
