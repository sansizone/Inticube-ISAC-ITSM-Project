import React, { useCallback, useState } from 'react'
import { api } from '../api.js'
import { useApp, navigate } from '../App.jsx'
import { Avatar, Modal, Popover, usePopover, useToast, Kbd, setAvatarOverride } from './common.jsx'
import { Icon } from './icons.jsx'
import ChangePassword from './ChangePassword.jsx'
import { MfaManage } from './Mfa.jsx'
import { relTime } from '../util.js'

const NOTI_ICON = { ASSIGNED: 'user', COMMENT: 'chat', MENTION: 'sparkle', DUE_SOON: 'calendar', STATUS: 'check', POST_COMMENT: 'chat', POST_MENTION: 'sparkle', NOTICE: 'megaphone' }
const NOTI_TONE = { ASSIGNED: 'primary', COMMENT: 'info', MENTION: 'secondary', DUE_SOON: 'warning', STATUS: 'success', POST_COMMENT: 'info', POST_MENTION: 'secondary', NOTICE: 'danger' }

/** 상단 고정 내비게이션 바 */
export default function Topnav() {
  const { toggleSidebar, openPalette } = useApp()
  return (
    <nav className="topnav">
      <button className="topnav-toggle" onClick={toggleSidebar} aria-label="사이드 메뉴 열기/닫기"><Icon name="menu" size={18} /></button>
      <a className="topnav-brand" href="#/"><span className="brand-mark" /> <span className="brand-text"><b>인티큐브</b><span>ISAC-PROJECT</span></span></a>
      <div id="topbar-slot" className="topbar-slot" />
      <div className="topnav-right">
        <span className="topnav-vsep" />
        <div id="topbar-primary" className="topbar-primary" />
        <button className="topnav-icon" onClick={openPalette} aria-label="검색 (Ctrl K)" title="검색 (Ctrl K)"><Icon name="search" size={18} /></button>
        <ThemeMenu />
        <AlertsDropdown />
      </div>
    </nav>
  )
}

function AlertsDropdown() {
  const { unread, openItem, refreshUnread } = useApp()
  const pop = usePopover()
  const [items, setItems] = useState(null)
  const load = useCallback(() => api.get('/api/notifications').then((d) => setItems(d.items.slice(0, 6))).catch(() => setItems([])), [])
  const open = async (n) => {
    pop.close()
    if (!n.read) { await api.post(`/api/notifications/${n.id}/read`).catch(() => {}); refreshUnread() }
    if (n.postId) navigate(`/posts/${n.postId}`)
    else if (n.boardId && n.itemId) openItem(n.boardId, n.itemId)
  }
  return (
    <>
      <button className="topnav-icon" onClick={(e) => { load(); pop.open(e) }} aria-label={`알림 ${unread}개`}>
        <Icon name="bell" size={18} />
        {unread > 0 && <span className="topnav-badge">{unread > 99 ? '99+' : unread}</span>}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={340} align="right" className="dropdown-sb">
          <div className="dropdown-header-sb"><Icon name="bell" size={14} /> 알림 센터</div>
          {items === null ? <div className="menu-empty" style={{ padding: 16 }}>불러오는 중…</div>
            : items.length === 0 ? <div className="menu-empty" style={{ padding: 16 }}>새 알림이 없습니다.</div>
              : items.map((n) => (
                <button key={n.id} className={`dropdown-alert ${n.read ? '' : 'unread'}`} onClick={() => open(n)}>
                  <span className={`alert-icon tone-${NOTI_TONE[n.type] || 'primary'}`}><Icon name={NOTI_ICON[n.type] || 'bell'} size={14} /></span>
                  <span className="grow" style={{ minWidth: 0 }}>
                    <span className="alert-time">{relTime(n.createdAt)}</span>
                    <span className="alert-msg">{n.message}</span>
                  </span>
                </button>
              ))}
          <a className="dropdown-footer-sb" href="#/inbox" onClick={pop.close}>알림함에서 모두 보기</a>
        </Popover>
      )}
    </>
  )
}

const THEMES = [['system', '시스템 설정 따름', 'monitor'], ['light', '라이트', 'sun'], ['dark', '다크', 'moon']]
export function readTheme() { try { const t = localStorage.getItem('tb.theme'); return t === 'light' || t === 'dark' ? t : 'system' } catch { return 'system' } }
export function applyTheme(t) {
  if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t)
  else document.documentElement.removeAttribute('data-theme')
  try { localStorage.setItem('tb.theme', t) } catch { /* 무시 */ }
}

/** 테마 선택: 시스템 / 라이트 / 다크 */
export function ThemeMenu({ compact }) {
  const pop = usePopover()
  const [theme, setTheme] = useState(readTheme)
  const cur = THEMES.find(([k]) => k === theme) || THEMES[0]
  const pick = (k) => { applyTheme(k); setTheme(k); pop.close() }
  return (
    <>
      <button className={compact ? 'btn btn-sm' : 'topnav-icon'} onClick={pop.open} aria-label="테마" title={`테마: ${cur[1]}`}>
        <Icon name={theme === 'system' ? (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'moon' : 'sun') : cur[2]} size={18} />{compact && <span style={{ marginLeft: 6 }}>{cur[1]}</span>}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={200} align="right" className="theme-menu">
          <div className="menu">
            <div className="menu-title">테마</div>
            {THEMES.map(([k, l, ic]) => (
              <button key={k} className={`menu-row ${theme === k ? 'active' : ''}`} onClick={() => pick(k)}>
                <Icon name={ic} size={14} /><span className="grow">{l}</span>{theme === k && <Icon name="check" size={14} />}
              </button>
            ))}
          </div>
        </Popover>
      )}
    </>
  )
}

export function UserDropdown() {
  const { me, setMe, logout } = useApp()
  const pop = usePopover()
  const [pw, setPw] = useState(false)
  const [mfa, setMfa] = useState(false)
  const [photo, setPhoto] = useState(false)
  const toast = useToast()
  return (
    <>
      <button className="topnav-user" onClick={pop.open} aria-label="내 계정"><Avatar user={me} size={32} /></button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={260} align="right" className="dropdown-sb">
          <div className="dropdown-user-sb">
            <Avatar user={me} size={40} />
            <div style={{ minWidth: 0 }}>
              <div className="du-name ellipsis">{me.name}</div>
              <div className="du-meta ellipsis">@{me.username}{me.department ? ` · ${me.department}` : ''}</div>
            </div>
          </div>
          <div className="menu-sep" style={{ margin: 0 }} />
          <div className="menu">
            <button className="menu-row" onClick={() => { pop.close(); setPhoto(true) }}><Icon name="camera" size={14} /> 프로필 사진 {me.avatar ? <span className="menu-meta">설정됨</span> : null}</button>
            <button className="menu-row" onClick={() => { pop.close(); setPw(true) }}><Icon name="key" size={14} /> 비밀번호 변경</button>
            <button className="menu-row" onClick={() => { pop.close(); setMfa(true) }}><Icon name="shield" size={14} /> 2단계 인증 {me.mfaEnabled ? <span className="tag tag-success" style={{ marginLeft: 'auto' }}>켜짐</span> : <span className="menu-meta">꺼짐</span>}</button>
            {me.role === 'ADMIN' && <a className="menu-row" href="#/admin" onClick={pop.close}><Icon name="settings" size={14} /> 관리자 설정</a>}
            <button className="menu-row" onClick={logout}><Icon name="logout" size={14} /> 로그아웃</button>
          </div>
        </Popover>
      )}
      {pw && (
        <Modal title="비밀번호 변경" onClose={() => setPw(false)}>
          <ChangePassword me={me} onCancel={() => setPw(false)} onDone={(u) => { setMe(u); setPw(false); toast('비밀번호가 변경되었습니다.', 'success') }} />
        </Modal>
      )}
      {mfa && <MfaManage me={me} onClose={() => setMfa(false)} onChanged={(u) => setMe(u)} />}
      {photo && <ProfilePhoto me={me} onClose={() => setPhoto(false)} onChanged={(v) => setMe({ ...me, avatar: v })} />}
    </>
  )
}

const PHOTO_MAX = 5 * 1024 * 1024
const PHOTO_SIZE = 256

/** 이미지 파일 → 가운데 정사각형 256px PNG (webp 등 서버가 못 읽는 형식도 브라우저에서 변환).
 *  CSP(img-src 'self' data:) 때문에 blob: 대신 data: 주소로 읽는다 */
function readDataUrl(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(r.result)
    r.onerror = () => reject(new Error('파일을 읽을 수 없습니다.'))
    r.readAsDataURL(file)
  })
}
async function toSquarePng(file) {
  const src = await readDataUrl(file)
  const img = await new Promise((resolve, reject) => {
    const im = new Image()
    im.onload = () => resolve(im)
    im.onerror = () => reject(new Error('이미지를 읽을 수 없습니다.'))
    im.src = src
  })
  const side = Math.min(img.naturalWidth, img.naturalHeight)
  const out = Math.min(PHOTO_SIZE, side)
  const c = document.createElement('canvas')
  c.width = out; c.height = out
  const g = c.getContext('2d')
  g.imageSmoothingQuality = 'high'
  g.drawImage(img, (img.naturalWidth - side) / 2, (img.naturalHeight - side) / 2, side, side, 0, 0, out, out)
  const blob = await new Promise((resolve, reject) => c.toBlob((b) => (b ? resolve(b) : reject(new Error('이미지 변환 실패'))), 'image/png'))
  return { blob, url: c.toDataURL('image/png') }
}

/** 프로필 사진 등록·변경·삭제 */
function ProfilePhoto({ me, onClose, onChanged }) {
  const { refreshUsers } = useApp()
  const toast = useToast()
  const [preview, setPreview] = useState(null) // { blob, url }
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)
  const fileRef = React.useRef(null)

  const pick = async (file) => {
    setErr(null)
    if (!file) return
    if (!/^image\/(jpeg|png|gif|webp)$/.test(file.type)) { setErr('JPG·PNG·GIF·WEBP 이미지만 올릴 수 있습니다.'); return }
    if (file.size > PHOTO_MAX) { setErr('5MB 이하 이미지만 올릴 수 있습니다.'); return }
    try {
      setPreview(await toSquarePng(file))
    } catch (e) { setErr(e.message) }
  }
  const done = (v, msg) => { setAvatarOverride(me.id, v); onChanged(v); refreshUsers?.(); toast(msg, 'success'); onClose() }
  const save = async () => {
    setBusy(true); setErr(null)
    try {
      const r = await api.upload('/api/me/avatar', new File([preview.blob], 'avatar.png', { type: 'image/png' }))
      done(r.avatar, '프로필 사진을 저장했습니다.')
    } catch (e) { setErr(e.message) } finally { setBusy(false) }
  }
  const remove = async () => {
    setBusy(true); setErr(null)
    try { await api.del('/api/me/avatar'); done(null, '프로필 사진을 삭제했습니다.') } catch (e) { setErr(e.message) } finally { setBusy(false) }
  }

  return (
    <Modal title="프로필 사진" onClose={onClose} width={400}>
      <div className="photo-edit">
        <div className="photo-preview">
          {preview ? <span className="avatar" style={{ width: 120, height: 120 }}><img className="avatar-img" src={preview.url} alt="새 프로필 사진 미리보기" /></span>
            : <Avatar user={me} size={120} />}
        </div>
        <p className="muted" style={{ margin: 0, fontSize: '0.8125rem', textAlign: 'center' }}>JPG·PNG·GIF·WEBP, 5MB 이하. 가운데를 정사각형으로 잘라 저장합니다.</p>
        {err && <div className="form-error">{err}</div>}
        <input ref={fileRef} type="file" accept="image/jpeg,image/png,image/gif,image/webp" hidden
          onChange={(e) => { pick(e.target.files?.[0]); e.target.value = '' }} />
        <div className="photo-actions">
          {me.avatar && !preview && <button className="btn btn-danger-ghost" disabled={busy} onClick={remove}><Icon name="trash" size={14} /> 삭제</button>}
          <span className="grow" />
          <button className="btn" disabled={busy} onClick={() => fileRef.current?.click()}><Icon name="upload" size={14} /> {preview || me.avatar ? '다른 사진 선택' : '사진 선택'}</button>
          {preview && <button className="btn btn-primary" disabled={busy} onClick={save}>{busy ? '저장 중…' : '저장'}</button>}
        </div>
      </div>
    </Modal>
  )
}
