// 저장된 테마를 렌더링 전에 적용 (CSP 때문에 index.html 인라인 스크립트는 쓸 수 없음)
try { const t = localStorage.getItem('tb.theme'); if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t) } catch { /* 무시 */ }
import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.jsx'
// Pretendard (OFL) — 한글·영문 통일 서체. 동적 서브셋이라 화면에 쓰인 글자 묶음만 내려받음 (폐쇄망에서도 jar 에 포함)
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css'
import './styles.css'

createRoot(document.getElementById('root')).render(<App />)
