import React, { useCallback, useEffect, useMemo, useState, createContext, useContext } from 'react'
import { createPortal } from 'react-dom'
import { api, setUnauthorizedHandler } from './api.js'
import { rootScale } from './util.js'
import { ToastProvider, Spinner } from './components/common.jsx'
import Login from './components/Login.jsx'
import ChangePassword from './components/ChangePassword.jsx'
import { MfaSetup } from './components/Mfa.jsx'
import Sidebar, { sectionOf } from './components/Sidebar.jsx'
import Topnav from './components/Topnav.jsx'
import { Icon } from './components/icons.jsx'
import CommandPalette from './components/CommandPalette.jsx'
import Home from './components/Home.jsx'
import BoardView from './components/BoardView.jsx'
import AdminPage from './components/AdminPage.jsx'
import Inbox from './components/Inbox.jsx'
import PostList from './components/PostList.jsx'
import PostView from './components/PostView.jsx'
import PostEditor from './components/PostEditor.jsx'
import MondayImport from './components/MondayImport.jsx'

export const AppCtx = createContext(null)
export const useApp = () => useContext(AppCtx)

function parseHash() {
  const raw = window.location.hash.replace(/^#/, '') || '/'
  const [path, query = ''] = raw.split('?')
  const parts = path.split('/').filter(Boolean)
  const params = Object.fromEntries(new URLSearchParams(query))
  if (parts[0] === 'board' && parts[1]) return { page: 'board', boardId: Number(parts[1]), view: parts[2] || null, params }
  if (parts[0] === 'posts') {
    if (parts[1] === 'new') return { page: 'post-edit', params }
    if (parts[1] && parts[2] === 'edit') return { page: 'post-edit', postId: Number(parts[1]), params }
    if (parts[1]) return { page: 'post', postId: Number(parts[1]), params }
    return { page: 'posts', params }
  }
  if (parts[0] === 'import' && (parts[1] === 'excel' || parts[1] === 'monday')) return { page: 'import-monday', params }
  if (parts[0] === 'inbox') return { page: 'inbox', params }
  if (parts[0] === 'admin') return { page: 'admin', tab: parts[1] || 'users', params }
  return { page: 'home', params }
}

export function navigate(path) { window.location.hash = path }

export default function App() {
  const [me, setMe] = useState(undefined)
  useEffect(() => {
    setUnauthorizedHandler(() => setMe(null))
    api.get('/api/auth/csrf').then(() => api.get('/api/auth/me')).then(setMe).catch(() => setMe(null))
  }, [])
  return (
    <ToastProvider>
      {me === undefined ? <div className="center-screen"><Spinner /></div>
        : !me ? <Login onLogin={setMe} />
          : me.mustChangePassword ? <div className="login-wrap"><ChangePassword forced me={me} onDone={setMe} onLogout={() => setMe(null)} /></div>
          : me.mfaSetupRequired ? <div className="login-wrap"><MfaSetup forced me={me} onDone={(u) => setMe(u)} onLogout={() => setMe(null)} /></div>
            : <Shell me={me} setMe={setMe} />}
    </ToastProvider>
  )
}

function Shell({ me, setMe }) {
  const [route, setRoute] = useState(parseHash)
  const [users, setUsers] = useState([])
  const [boards, setBoards] = useState([])
  const [categories, setCategories] = useState([])
  const [unread, setUnread] = useState(0)
  // 데스크톱: true = 아이콘 레일로 접힘(기억함) / 모바일: true = 메뉴 열림
  const [panelSection, setPanelSection] = useState(() => sectionOf(parseHash()) || 'workspace')
  const [sidebarOpen, setSidebarOpen] = useState(() => {
    try { return window.innerWidth >= 992 && localStorage.getItem('tb.sidenav.collapsed') === '1' } catch { return false }
  })
  const [palette, setPalette] = useState(false)
  const [, setScaleTick] = useState(0)

  // 창 크기가 바뀌어 화면 배율(rem)이 달라지면 JS 로 크기를 정하는 요소(아이콘·아바타·타임라인)도 다시 그림
  useEffect(() => {
    let last = rootScale(), t
    const onResize = () => { clearTimeout(t); t = setTimeout(() => { const r = rootScale(); if (r !== last) { last = r; setScaleTick((x) => x + 1) } }, 150) }
    window.addEventListener('resize', onResize)
    return () => { window.removeEventListener('resize', onResize); clearTimeout(t) }
  }, [])

  useEffect(() => {
    const onHash = () => {
      const r = parseHash(); setRoute(r)
      const sec = sectionOf(r); if (sec) setPanelSection(sec)
      if (window.innerWidth < 992) setSidebarOpen(false)
    }
    window.addEventListener('hashchange', onHash)
    const onKey = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setPalette((v) => !v) }
    }
    window.addEventListener('keydown', onKey)
    return () => { window.removeEventListener('hashchange', onHash); window.removeEventListener('keydown', onKey) }
  }, [])

  const refreshBoards = useCallback(() => api.get('/api/boards').then(setBoards).catch(() => {}), [])
  const refreshUsers = useCallback(() => api.get('/api/users').then(setUsers).catch(() => {}), [])
  const refreshCategories = useCallback(() => api.get('/api/post-categories').then(setCategories).catch(() => {}), [])
  const refreshUnread = useCallback(() => api.get('/api/notifications').then((d) => setUnread(d.unreadCount)).catch(() => {}), [])
  useEffect(() => {
    refreshBoards(); refreshUsers(); refreshCategories(); refreshUnread()
    const t = setInterval(refreshUnread, 30000)
    return () => clearInterval(t)
  }, [refreshBoards, refreshUsers, refreshCategories, refreshUnread])

  const logout = useCallback(async () => {
    try { await api.post('/api/auth/logout') } catch { /* 무시 */ }
    await api.get('/api/auth/csrf').catch(() => {})
    setMe(null)
    window.location.hash = '/'
  }, [setMe])

  const ctx = useMemo(() => ({
    me, setMe, users, boards, categories, unread, logout,
    refreshBoards, refreshUsers, refreshCategories, refreshUnread,
    mentionNames: new Set(users.flatMap((u) => [u.username, u.name, `${u.name}(${(u.username || '').split('@')[0]})`])),
    openItem: (boardId, itemId) => navigate(`/board/${boardId}?item=${itemId}`),
    openSidebar: () => setSidebarOpen((v) => {
      // 데스크톱: 접혀 있으면(=toggled) 펼침. 모바일: 열기
      if (window.innerWidth >= 992) { try { localStorage.setItem('tb.sidenav.collapsed', '0') } catch { /* 무시 */ } return false }
      return true
    }),
    panelSection, setPanelSection,
    toggleSidebar: () => setSidebarOpen((v) => {
      if (window.innerWidth >= 992) { try { localStorage.setItem('tb.sidenav.collapsed', v ? '0' : '1') } catch { /* 무시 */ } }
      return !v
    }),
    openPalette: () => setPalette(true),
  }), [me, setMe, users, boards, categories, unread, logout, refreshBoards, refreshUsers, refreshCategories, refreshUnread, panelSection])

  let page
  switch (route.page) {
    case 'board': page = <BoardView key={route.boardId} boardId={route.boardId} view={route.view} itemParam={route.params.item} />; break
    case 'posts': page = <PostList params={route.params} />; break
    case 'post': page = <PostView key={route.postId} postId={route.postId} />; break
    case 'post-edit': page = <PostEditor key={route.postId || 'new'} postId={route.postId} params={route.params} />; break
    case 'inbox': page = <Inbox />; break
    case 'import-monday': page = <MondayImport />; break
    case 'admin': page = me.role === 'ADMIN' ? <AdminPage tab={route.tab} /> : <div className="page"><p>권한이 없습니다.</p></div>; break
    default: page = <Home />
  }

  return (
    <AppCtx.Provider value={ctx}>
      <div className={`app ${sidebarOpen ? 'sidenav-toggled' : ''}`}>
        <Topnav />
        <div className="layout-body">
          <Sidebar route={route} />
          <div className="sidebar-scrim" onClick={() => setSidebarOpen(false)} />
          <main className="main-panel">
            <div className="main-content">{page}</div>
          </main>
        </div>
      </div>
      {palette && <CommandPalette onClose={() => setPalette(false)} />}
    </AppCtx.Provider>
  )
}

/** 데스크톱(≥992px) 여부 — 페이지 헤더를 상단바에 넣을지 결정 */
function useDesktop() {
  const q = '(min-width: 992px)'
  const [on, setOn] = useState(() => window.matchMedia(q).matches)
  useEffect(() => {
    const m = window.matchMedia(q); const f = (e) => setOn(e.matches)
    m.addEventListener('change', f); return () => m.removeEventListener('change', f)
  }, [])
  return on
}

/**
 * 페이지 헤더. 데스크톱에서는 상단바(#topbar-slot)에 브레드크럼·제목·액션을 한 줄로 넣고,
 * 모바일에서는 본문 위에 작은 헤더로 표시한다.
 */
export function PageHeader({ title, icon = 'home', subtitle, actions, primary, children, crumbs, compact }) {
  const desktop = useDesktop()
  const [slot, setSlot] = useState(null)
  const [pslot, setPslot] = useState(null)   // 상단 바 검색 버튼 왼쪽 (주요 버튼 한 개, 모바일에서도 보임)
  useEffect(() => { setSlot(document.getElementById('topbar-slot')); setPslot(document.getElementById('topbar-primary')) }, [])
  const primaryEl = primary && pslot ? createPortal(primary, pslot) : null
  if (primary && !pslot) actions = <>{actions}{primary}</>
  const crumbEls = crumbs && crumbs.length > 0 && (
    <span className="tb-crumbs">{crumbs.map((c, i) => <React.Fragment key={i}>{c.href ? <a href={c.href}>{c.label}</a> : <span>{c.label}</span>}<span className="tb-sep">/</span></React.Fragment>)}</span>
  )
  if (desktop && slot) {
    return createPortal(
      <div className="tb-head">
        <span className="tb-icon"><Icon name={icon} size={14} /></span>
        {crumbEls}
        <h1 className="tb-title">{typeof title === 'string' ? <span className="ellipsis">{title}</span> : title}</h1>
        {subtitle && <div className="tb-sub">{subtitle}</div>}
        {children}
        <span className="grow" />
        {actions && <div className="tb-actions">{actions}</div>}
        {primaryEl}
      </div>, slot)
  }
  return (
    <header className={`page-header-sb ${compact ? 'compact' : ''}`}>
      <div className="phs-inner">
        <div className="phs-main">
          {crumbEls && <div className="phs-crumbs">{crumbEls}</div>}
          <h1 className="phs-title">
            <span className="phs-icon"><Icon name={icon} size={18} /></span>
            {typeof title === 'string' ? <span className="ellipsis">{title}</span> : title}
          </h1>
          {subtitle && <div className="phs-subtitle">{subtitle}</div>}
          {children}
        </div>
        {actions && <div className="phs-actions">{actions}</div>}
      </div>
      {primaryEl}
    </header>
  )
}
