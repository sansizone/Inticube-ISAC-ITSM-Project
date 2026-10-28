import { Icon } from './icons.jsx'
import React, { useState } from 'react'
import { api } from '../api.js'
import { ThemeMenu } from './Topnav.jsx'

export default function Login({ onLogin }) {
  const [username, setUsername] = useState('')
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
      const r = await api.post('/api/auth/login', { username, password })
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
    <div className="login-wrap">
      <div className="login-theme"><ThemeMenu compact /></div>
      <form className="login-card" onSubmit={submit}>
        <span className="brand-mark brand-mark-lg" />
        <h1 className="login-title"><b>인티큐브</b> ISAC-PROJECT</h1>
        <p className="muted" style={{ margin: '0 0 24px' }}>로그인하여 팀의 업무와 소식을 한곳에서</p>
        <label className="field">
          <span>아이디</span>
          <input className="input" autoFocus autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
        </label>
        <label className="field">
          <span>비밀번호</span>
          <span className="pw-wrap">
            <input className="input" type={showPw ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            <button type="button" className="pw-eye" onClick={() => setShowPw((v) => !v)}
              aria-label={showPw ? '비밀번호 숨기기' : '비밀번호 보기'} title={showPw ? '비밀번호 숨기기' : '비밀번호 보기'}>
              <Icon name={showPw ? 'eyeOff' : 'eye'} size={17} />
            </button>
          </span>
        </label>
        {error && <div className="form-error" role="alert">{error}</div>}
        <button className="btn btn-primary btn-block" disabled={busy}>{busy ? '로그인 중…' : '로그인'}</button>
        <p className="hint" style={{ marginTop: 16, textAlign: 'center' }}>계정이 없으면 관리자에게 요청하세요.</p>
      </form>
    </div>
  )
}
