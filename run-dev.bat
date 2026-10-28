@echo off
REM 프론트엔드 개발 서버 (화면 수정이 저장 즉시 반영, 빌드 불필요)
REM   run-dev.bat         : 개발 서버(10.1.17.116:30000) 백엔드에 붙어서 실행
REM   run-dev.bat local   : 내 PC 에서 띄운 백엔드(localhost:8080) 에 붙어서 실행
chcp 65001 >nul
cd /d "%~dp0frontend"
where npm >nul 2>nul || (echo Node.js 가 없습니다. https://nodejs.org 에서 LTS 설치 후 다시 실행하세요. & pause & exit /b 1)
if not exist node_modules (
  echo [최초 1회] 패키지 설치 중...
  call npm ci || (pause & exit /b 1)
)
if /i "%1"=="local" (
  call npm run dev
) else (
  call npm run dev:remote
)
