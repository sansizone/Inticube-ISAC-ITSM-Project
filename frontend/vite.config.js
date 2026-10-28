import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import fs from 'node:fs'

// 빌드 결과물은 Spring Boot static 폴더로 출력 → 단일 jar 배포
// 개발 서버(npm run dev)는 /api 요청을 API_TARGET 으로 넘긴다
//   npm run dev         → 내 PC 에서 띄운 백엔드 (루트 config/application.yml 의 server.port, 없으면 8080)
//   npm run dev:remote  → 개발 서버 백엔드 (.env.remote 의 API_TARGET, 기본 http://10.1.17.116:30000)
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // 로컬 백엔드 포트: 저장소 루트 config/application.yml 의 server.port (IDE 에서 루트 기준으로 띄우면 이 설정을 씀), 없으면 8080
  let localPort = 8080
  try {
    const yml = fs.readFileSync(new URL('../config/application.yml', import.meta.url), 'utf8')
    const m = yml.match(/^server:\s*\n(?:[ \t]+.*\n)*?[ \t]+port:\s*(\d+)/m)
    if (m) localPort = Number(m[1])
  } catch { /* 설정 파일 없음 → 8080 */ }
  const target = env.API_TARGET || `http://127.0.0.1:${localPort}`
  console.log(`\n  [ISAC dev] /api → ${target}\n`)
  return {
    plugins: [react()],
    build: {
      outDir: '../backend/src/main/resources/static',
      emptyOutDir: true,
    },
    server: {
      // localhost 가 아니라 127.0.0.1 로 띄운다: 쿠키는 포트를 구분하지 않아서,
      // 같은 PC 의 localhost:8080(로컬 jar)에 로그인돼 있으면 세션 쿠키(TBSESSION)가 서로 덮어써 401 이 난다
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      open: 'http://127.0.0.1:5173/',
      proxy: {
        '/api': {
          target,
          changeOrigin: true,
          // 백엔드 세션·CSRF 쿠키를 localhost:5173 에서도 쓰도록 쿠키 도메인 제거
          cookieDomainRewrite: '',
        },
      },
    },
  }
})
