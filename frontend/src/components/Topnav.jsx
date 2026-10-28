import React, { useCallback, useState } from 'react'
import { api } from '../api.js'
import { useApp, navigate } from '../App.jsx'
import { Avatar, Modal, Popover, usePopover, useToast, Kbd } from './common.jsx'
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
    </>
  )
}
