import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { cpSync, existsSync } from 'fs'

export default defineConfig({
  plugins: [
    vue(),
    {
      name: 'copy-to-src',
      closeBundle() {
        const dist = '../blueocean-app/target/classes/static/vue'
        const src = '../blueocean-app/src/main/resources/static/vue'
        if (existsSync(dist)) {
          cpSync(dist, src, { recursive: true, force: true })
        }
      }
    }
  ],
  base: '/vue/',
  build: {
    outDir: '../blueocean-app/target/classes/static/vue',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
