import { Icon } from './icons.jsx'
import React, { useState } from 'react'
import { api } from '../api.js'
import { ThemeMenu } from './Topnav.jsx'

export default function Login({ onLogin }) {
  // 아이디 저장: 이 브라우저에만 아이디(비밀번호 아님)를 기억
  const savedId = (() => { try { return localStorage.getItem('tb.savedId') || '' } catch { return '' } })()
  const [username, setUsername] = useState(savedId)
  const [remember, setRemember] = useState(!!savedId)
  const [password, setPassword] = useState('')
  const [showPw, setShowPw] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [mfa, setMfa] = useState(false)
  const [code, setCode] = useState('')

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true); setError('')
    try {
      await api.get('/api/auth/csrf')
      const r = await api.post('/api/auth/login', { username: username.trim(), password })
      try { if (remember) localStorage.setItem('tb.savedId', username.trim()); else localStorage.removeItem('tb.savedId') } catch { /* 저장소 사용 불가 */ }
      if (r.mfaRequired) { setMfa(true); setPassword('') } else onLogin(r.me)
    } catch (err) {
      setError(err.message)
    } finally { setBusy(false) }
  }
  const submitMfa = async (e) => {
    e.preventDefault()
    setBusy(true); setError('')
    try { const r = await api.post('/api/auth/mfa', { code }); onLogin(r.me) }
    catch (err) { setError(err.message); if (/다시 로그인/.test(err.message)) { setMfa(false); setCode('') } }
    finally { setBusy(false) }
  }

  if (mfa) {
    return (
      <div className="login-wrap">
        <form className="login-card" onSubmit={submitMfa}>
          <span className="brand-mark brand-mark-lg" />
          <h1 className="login-title">2단계 인증</h1>
          <p className="muted" style={{ margin: '0 0 24px' }}><b>{username}</b> · 인증 앱(Google Authenticator 등)에 표시된 6자리 코드를 입력하세요.</p>
          <label className="field">
            <span>인증 코드</span>
            <input className="input mfa-input" autoFocus inputMode="numeric" autoComplete="one-time-code" maxLength={20} value={code} onChange={(e) => setCode(e.target.value)} placeholder="123456" required />
          </label>
          {error && <div className="form-error" role="alert">{error}</div>}
          <button className="btn btn-primary btn-block" disabled={busy}>{busy ? '확인 중…' : '확인'}</button>
          <p className="hint" style={{ marginTop: 16, textAlign: 'center' }}>앱을 쓸 수 없으면 설정할 때 받은 <b>복구 코드</b>를 입력하세요. 그것도 없으면 관리자에게 2단계 인증 초기화를 요청하세요.</p>
          <button type="button" className="link" style={{ display: 'block', margin: '8px auto 0' }} onClick={() => { setMfa(false); setCode(''); setError('') }}>다른 계정으로 로그인</button>
        </form>
      </div>
    )
  }

  return (
    <div className="login-wrap login-l2">
      <div className="login-theme"><ThemeMenu compact /></div>
      <form className="login-card" onSubmit={submit}>
        {/* L2: 가운데 브랜드 + 카드형 폼 (색은 메인 화면의 파랑·네이비에 맞춤) */}
        <div className="login-brand">
          <span className="brand-mark brand-mark-lg" />
          <div className="login-brand-sub">인티큐브</div>
          <h1 className="login-brand-name">ISAC-PROJECT</h1>
          <p className="login-brand-desc">회사 이메일 계정으로 로그인하세요</p>
        </div>
        {/* 입력칸: 왼쪽 아이콘 + 옅은 배경, 포커스 시 흰 배경·파란 테두리 */}
        <label className="login-field">
          <span className="sr-only">아이디</span>
          <Icon name="user" size={17} className="login-field-icon" />
          <input id="login-id" className="login-input" autoFocus={!savedId} autoComplete="username" placeholder="이메일 주소"
            value={username} onChange={(e) => setUsername(e.target.value)} required />
        </label>
        <label className="login-field">
          <span className="sr-only">비밀번호</span>
          <Icon name="lock" size={17} className="login-field-icon" />
          <input id="login-pw" className="login-input" autoFocus={!!savedId} type={showPw ? 'text' : 'password'} autoComplete="current-password" placeholder="비밀번호"
            value={password} onChange={(e) => setPassword(e.target.value)} required />
          <button type="button" className="login-eye" onClick={() => setShowPw((v) => !v)}
            aria-label={showPw ? '비밀번호 숨기기' : '비밀번호 보기'} title={showPw ? '비밀번호 숨기기' : '비밀번호 보기'}>
            <Icon name={showPw ? 'eyeOff' : 'eye'} size={17} />
          </button>
        </label>
        {error && <div className="form-error" role="alert">{error}</div>}
        <button className="btn btn-primary btn-block login-submit" disabled={busy}>{busy ? '로그인 중…' : '로그인'}</button>
        <div className="login-row">
          <label className="check login-remember"><input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} /> 아이디 저장</label>
          <span>비밀번호 분실 시 관리자 문의</span>
        </div>
      </form>
      <div className="login-foot">사내 전용 시스템</div>
    </div>
  )

}
