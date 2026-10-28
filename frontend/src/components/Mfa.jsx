import React, { useEffect, useRef, useState } from 'react'
import QRCode from 'qrcode'
import { api } from '../api.js'
import { Modal, useToast } from './common.jsx'
import { Icon } from './icons.jsx'

/**
 * 2단계 인증 설정 (Google Authenticator · Microsoft Authenticator · Authy 등 TOTP 앱).
 * forced=true 면 정책상 필수라서 설정 전엔 다른 화면을 쓸 수 없는 상태.
 */
export function MfaSetup({ forced, me, onDone, onLogout, onCancel }) {
  const [setup, setSetup] = useState(null)
  const [code, setCode] = useState('')
  const [codes, setCodes] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [showSecret, setShowSecret] = useState(false)
  const canvas = useRef(null)

  useEffect(() => { api.post('/api/auth/mfa/setup').then(setSetup).catch((e) => setError(e.message)) }, [])
  useEffect(() => { if (setup && canvas.current) QRCode.toCanvas(canvas.current, setup.otpauthUrl, { width: 200, margin: 1 }).catch(() => {}) }, [setup])

  const enable = async (e) => {
    e.preventDefault(); setBusy(true); setError('')
    try { const r = await api.post('/api/auth/mfa/enable', { code }); setCodes(r.recoveryCodes) }
    catch (err) { setError(err.message) } finally { setBusy(false) }
  }
  const finish = async () => { const u = await api.get('/api/auth/me'); onDone(u) }
  const logout = async () => { try { await api.post('/api/auth/logout') } catch { /* 무시 */ } await api.get('/api/auth/csrf').catch(() => {}); onLogout && onLogout() }

  const body = codes ? (
    <>
      <h2 className="mfa-h">복구 코드를 저장하세요</h2>
      <p className="muted small">휴대폰을 잃어버리거나 앱을 지웠을 때 이 코드로 로그인할 수 있습니다. 코드마다 한 번만 쓸 수 있고, <b>이 화면을 닫으면 다시 볼 수 없습니다.</b></p>
      <div className="recovery-grid">{codes.map((c) => <code key={c}>{c}</code>)}</div>
      <div className="row gap-sm" style={{ marginTop: 12 }}>
        <button type="button" className="btn btn-sm" onClick={() => navigator.clipboard?.writeText(codes.join('\n'))}>복사</button>
        <button type="button" className="btn btn-sm" onClick={() => { const a = document.createElement('a'); a.href = 'data:text/plain;charset=utf-8,' + encodeURIComponent(`ISAC-PROJECT 2단계 인증 복구 코드 (${me.username})\n\n${codes.join('\n')}`); a.download = 'isac-project-recovery-codes.txt'; a.click() }}>파일로 저장</button>
        <span className="grow" />
        <button type="button" className="btn btn-primary" onClick={finish}>저장했습니다, 완료</button>
      </div>
    </>
  ) : (
    <form onSubmit={enable}>
      <h2 className="mfa-h">{forced ? '2단계 인증을 설정하세요' : '2단계 인증 설정'}</h2>
      {forced && <p className="muted small">보안 정책에 따라 2단계 인증을 설정해야 ISAC-PROJECT를 쓸 수 있습니다.</p>}
      <ol className="steps">
        <li>휴대폰에 <b>Google Authenticator</b>(또는 Microsoft Authenticator, Authy)를 설치합니다.</li>
        <li>앱에서 <b>＋ → QR 코드 스캔</b>으로 아래 QR 코드를 찍습니다.</li>
        <li>앱에 표시된 6자리 코드를 입력합니다.</li>
      </ol>
      <div className="mfa-qr">
        {setup ? <canvas ref={canvas} aria-label="QR 코드" /> : <div className="muted">QR 코드 준비 중…</div>}
        <div className="mfa-manual">
          <button type="button" className="link small" onClick={() => setShowSecret((v) => !v)}>{showSecret ? '키 숨기기' : 'QR 코드를 찍을 수 없나요? 키 직접 입력'}</button>
          {showSecret && setup && <div className="mfa-secret"><code>{setup.secret.replace(/(.{4})/g, '$1 ').trim()}</code><div className="muted small">계정: {setup.issuer} ({setup.account}) · 시간 기반 · 6자리</div></div>}
        </div>
      </div>
      <label className="field"><span>앱에 표시된 코드</span>
        <input className="input mfa-input" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} placeholder="123456" required autoFocus /></label>
      {error && <div className="form-error" role="alert">{error}</div>}
      <div className="form-actions">
        {forced ? <button type="button" className="btn" onClick={logout}>로그아웃</button> : <button type="button" className="btn" onClick={onCancel}>취소</button>}
        <button className="btn btn-primary" disabled={busy || code.length !== 6 || !setup}>{busy ? '확인 중…' : '켜기'}</button>
      </div>
    </form>
  )
  if (forced) return <div className="login-card mfa-card"><span className="brand-mark brand-mark-lg" />{body}</div>
  return <Modal title="2단계 인증" onClose={codes ? finish : onCancel} width={560}>{body}</Modal>
}

/** 계정 메뉴의 2단계 인증 상태/끄기 */
export function MfaManage({ me, onClose, onChanged }) {
  const toast = useToast()
  const [status, setStatus] = useState(null)
  const [setupOpen, setSetupOpen] = useState(false)
  const [f, setF] = useState({ password: '', code: '' })
  const [busy, setBusy] = useState(false)
  const load = () => api.get('/api/auth/mfa/status').then(setStatus).catch((e) => toast(e.message, 'error'))
  useEffect(() => { load() }, []) // eslint-disable-line
  const disable = async (e) => {
    e.preventDefault(); setBusy(true)
    try { const u = await api.post('/api/auth/mfa/disable', f); toast('2단계 인증을 껐습니다.'); onChanged(u); onClose() } catch (err) { toast(err.message, 'error') } finally { setBusy(false) }
  }
  if (setupOpen) return <MfaSetup me={me} onCancel={() => setSetupOpen(false)} onDone={(u) => { onChanged(u); toast('2단계 인증을 켰습니다.', 'success'); onClose() }} />
  return (
    <Modal title="2단계 인증" onClose={onClose} footer={<button className="btn" onClick={onClose}>닫기</button>}>
      {!status ? <div className="muted">확인 중…</div> : status.enabled ? (
        <>
          <p><span className="tag tag-success">켜짐</span> 로그인할 때 비밀번호 다음에 인증 앱 코드를 입력합니다. 남은 복구 코드 <b>{status.recoveryCodesLeft}</b>개.</p>
          {status.recoveryCodesLeft <= 2 && <div className="form-warn">복구 코드가 거의 없습니다. 끄고 다시 켜면 새 복구 코드 10개를 받습니다.</div>}
          <form onSubmit={disable} className="mfa-disable">
            <div className="dsec-head"><h4>끄기</h4></div>
            <label className="field"><span>비밀번호</span><input type="password" className="input" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} required autoComplete="current-password" /></label>
            <label className="field"><span>인증 앱 코드 (또는 복구 코드)</span><input className="input" value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} required inputMode="numeric" autoComplete="one-time-code" /></label>
            <button className="btn btn-danger-ghost" disabled={busy}>2단계 인증 끄기</button>
          </form>
        </>
      ) : (
        <>
          <p><span className="tag">꺼짐</span> 켜면 비밀번호가 유출돼도 휴대폰의 인증 앱 없이는 로그인할 수 없습니다.</p>
          <button className="btn btn-primary" onClick={() => setSetupOpen(true)}><Icon name="shield" size={14} /> 2단계 인증 켜기</button>
        </>
      )}
    </Modal>
  )
}
