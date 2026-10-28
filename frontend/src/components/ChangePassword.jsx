import React, { useState } from 'react'
import { api } from '../api.js'

export default function ChangePassword({ forced, me, onDone, onLogout, onCancel }) {
  const [cur, setCur] = useState('')
  const [next, setNext] = useState('')
  const [next2, setNext2] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e) => {
    e.preventDefault()
    if (next !== next2) { setError('새 비밀번호가 서로 다릅니다.'); return }
    setBusy(true); setError('')
    try {
      const updated = await api.post('/api/auth/password', { currentPassword: cur, newPassword: next })
      onDone(updated)
    } catch (err) { setError(err.message) } finally { setBusy(false) }
  }

  const logout = async () => {
    try { await api.post('/api/auth/logout') } catch { /* 무시 */ }
    await api.get('/api/auth/csrf').catch(() => {})
    onLogout && onLogout()
  }

  return (
    <form className={forced ? 'login-card' : ''} onSubmit={submit}>
      {forced && <>
        <span className="brand-mark brand-mark-lg" />
        <h1 className="login-title">새 비밀번호 설정</h1>
        <p className="muted small" style={{ marginTop: 0, marginBottom: 20 }}>{me.name}님, 처음 로그인했거나 비밀번호가 초기화되었습니다. 새 비밀번호를 설정해 주세요.</p>
      </>}
      <label className="field"><span>현재 비밀번호</span>
        <input className="input" type="password" autoComplete="current-password" value={cur} onChange={(e) => setCur(e.target.value)} required autoFocus /></label>
      <label className="field"><span>새 비밀번호</span>
        <input className="input" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} required /></label>
      <label className="field"><span>새 비밀번호 확인</span>
        <input className="input" type="password" autoComplete="new-password" value={next2} onChange={(e) => setNext2(e.target.value)} required /></label>
      <p className="hint">8~64자, 영문·숫자·특수문자 포함, 아이디 포함 불가</p>
      {error && <div className="form-error" role="alert">{error}</div>}
      <div className="form-actions">
        {forced ? <button type="button" className="btn" onClick={logout}>로그아웃</button>
          : <button type="button" className="btn" onClick={onCancel}>취소</button>}
        <button className="btn btn-primary" disabled={busy}>{busy ? '변경 중…' : '변경'}</button>
      </div>
    </form>
  )
}
