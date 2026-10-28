import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 빌드 결과물은 Spring Boot static 폴더로 출력 → 단일 jar 배포
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
})
