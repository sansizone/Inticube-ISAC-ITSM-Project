import React, { useState } from 'react'
import { useApp, navigate } from '../App.jsx'
import { api } from '../api.js'
import { Modal, useToast } from './common.jsx'
import { UserDropdown } from './Topnav.jsx'
import { Icon } from './icons.jsx'
import { boardColor, sc } from '../util.js'
import { VisibilityPicker } from './Workspace.jsx'

export function categoryIcon(c) {
  if (c.adminOnlyWrite || /공지/.test(c.name)) return 'megaphone'
  if (/자료|문서|양식/.test(c.name)) return 'folder'
  return 'chat'
}

export function BoardMark({ board, size: rawSize = 18 }) {
  const size = sc(rawSize)
  return (
    <span className="board-mark" style={{ width: size, height: size, background: boardColor(board.id), fontSize: size * 0.55 }}>
      {board.name.trim().charAt(0).toUpperCase()}
    </span>
  )
}

/** 워크스페이스 즐겨찾기 토글 (사용자별, 서버 저장) */
export function useFavorite() {
  const { refreshBoards } = useApp()
  const toast = useToast()
  return async (board) => {
    try { await api.put(`/api/boards/${board.id}/favorite`, { favorite: !board.favorite }); refreshBoards() }
    catch (e) { toast(e.message, 'error') }
  }
}

/** 사이드바·접힌 메뉴에서 쓰는 즐겨찾기 별 버튼 */
export function FavStar({ board, size = 13 }) {
  const toggle = useFavorite()
  return (
    <button type="button" className={`fav-star ${board.favorite ? 'on' : ''}`} title={board.favorite ? '즐겨찾기 해제' : '즐겨찾기'} aria-label={board.favorite ? '즐겨찾기 해제' : '즐겨찾기'}
      onClick={(e) => { e.preventDefault(); e.stopPropagation(); toggle(board) }}>
      <Icon name="star" size={size} />
    </button>
  )
}

/** 라우트 → 패널 섹션 (home/inbox 는 이전 섹션 유지) */
export function sectionOf(route) {
  if (['posts', 'post', 'post-edit'].includes(route.page)) return 'posts'
  if (route.page === 'admin') return 'admin'
  if (['board', 'import-monday'].includes(route.page)) return 'workspace'
  return null
}

/**
 * 좌측 프레임 = 아이콘 레일(1단) + 패널(2단).
 * 레일: 대시보드·알림함·워크스페이스·게시판·관리 + 하단 내 계정. 패널: 레일에서 고른 섹션의 하위 목록.
 */
export default function Sidebar({ route }) {
  const { me, boards, categories, unread, panelSection, setPanelSection, openSidebar, refreshBoards } = useApp()
  const toast = useToast()
  const [creating, setCreating] = useState(false)
  const [showAllBoards, setShowAllBoards] = useState(false)
  const favBoards = boards.filter((b) => b.favorite)
  const restBoards = boards.filter((b) => !b.favorite)
  const limit = favBoards.length ? 8 : 12
  const shownBoards = showAllBoards ? restBoards : restBoards.slice(0, limit)
  const section = panelSection
  /** 워크스페이스 위/아래 이동 — 같은 목록(즐겨찾기/전체) 안의 이웃과 자리를 바꿔 전체 순서를 서버에 저장 (사용자별) */
  const moveBoard = async (b, dir) => {
    const list = b.favorite ? favBoards : restBoards
    const i = list.findIndex((x) => x.id === b.id)
    const j = i + dir
    if (i < 0 || j < 0 || j >= list.length) return
    const ids = boards.map((x) => x.id)
    const a = ids.indexOf(b.id), c = ids.indexOf(list[j].id)
    ;[ids[a], ids[c]] = [ids[c], ids[a]]
    try { await api.put('/api/boards/order', { ids }); refreshBoards() }
    catch (e) { toast(e.message, 'error') }
  }
  const pick = (sec) => {
    setPanelSection(sec); openSidebar()
    // 대시보드·알림함에서 '워크'를 누르면 마지막으로 본 워크스페이스로 이동 (없으면 즐겨찾기 → 첫 워크스페이스)
    if (sec === 'workspace' && ['home', 'inbox'].includes(route.page) && boards.length) {
      let last = null; try { last = Number(localStorage.getItem('tb.lastBoard')) } catch { /* 무시 */ }
      const target = boards.find((b) => b.id === last) || favBoards[0] || boards[0]
      navigate(`/board/${target.id}`)
    }
  }

  const rail = (key, icon, label, active, onClick, href, badge) => {
    const inner = <>
      <span className="rail-icon"><Icon name={icon} size={19} />{badge > 0 && <span className="rail-badge">{badge > 99 ? '99+' : badge}</span>}</span>
      <span className="rail-label">{label}</span>
    </>
    return href
      ? <a key={key} href={href} className={`rail-item ${active ? 'active' : ''}`} title={label} onClick={onClick}>{inner}</a>
      : <button key={key} type="button" className={`rail-item ${active ? 'active' : ''}`} title={label} onClick={onClick}>{inner}</button>
  }

  return (
    <>
    <nav className="siderail" aria-label="주 메뉴">
      <a className="rail-brand" href="#/" title="인티큐브 ISAC-PROJECT"><span className="brand-mark" /></a>
      {rail('home', 'chart', '대시보드', route.page === 'home', null, '#/')}
      {rail('inbox', 'bell', '알림함', route.page === 'inbox', null, '#/inbox', unread)}
      {rail('ws', 'kanban', '워크', section === 'workspace' && !['home', 'inbox'].includes(route.page), () => pick('workspace'))}
      {rail('posts', 'doc', '게시판', section === 'posts', () => pick('posts'), '#/posts')}
      {me.role === 'ADMIN' && rail('admin', 'settings', '관리', section === 'admin', () => pick('admin'), '#/admin')}
      <span className="grow" />
      <RailUser />
    </nav>
    <aside className="sidenav" aria-label="하위 메뉴">
      {section === 'workspace' && <>
        <div className="panel-head">
          <span>워크스페이스</span>
          <span className="row-actions">
            <a className="icon-btn icon-btn-sm" href="#/import/excel" title="엑셀로 워크스페이스 만들기" aria-label="엑셀로 워크스페이스 만들기"><Icon name="upload" size={14} /></a>
            <button className="icon-btn icon-btn-sm" title="새 워크스페이스" aria-label="새 워크스페이스" onClick={() => setCreating(true)}><Icon name="plus" size={14} /></button>
          </span>
        </div>
        <div className="sidenav-menu">
          {favBoards.length > 0 && <>
            <div className="sidenav-subheading"><Icon name="star" size={11} /> 즐겨찾기</div>
            {favBoards.map((b, i) => <BoardRow key={b.id} b={b} route={route} onMove={moveBoard} first={i === 0} last={i === favBoards.length - 1} />)}
            {restBoards.length > 0 && <div className="sidenav-subheading">전체</div>}
          </>}
          {shownBoards.map((b, i) => <BoardRow key={b.id} b={b} route={route} onMove={moveBoard} first={i === 0} last={i === restBoards.length - 1} />)}
          {restBoards.length > limit && <button className="side-item muted" onClick={() => setShowAllBoards((v) => !v)}>{showAllBoards ? '접기' : `${restBoards.length - limit}개 더 보기`}</button>}
          {boards.length === 0 && <button className="side-item muted" onClick={() => setCreating(true)}><Icon name="plus" /> 워크스페이스 만들기</button>}
        </div>
      </>}
      {section === 'posts' && <>
        <div className="panel-head"><span>게시판</span>
          <span className="row-actions"><a className="icon-btn icon-btn-sm" href="#/posts/new" title="글쓰기" aria-label="글쓰기"><Icon name="edit" size={14} /></a></span>
        </div>
        <div className="sidenav-menu">
          <a href="#/posts" className={`side-item ${route.page === 'posts' && !route.params.cat ? 'active' : ''}`}><Icon name="doc" /> 전체 글</a>
          {categories.map((c) => {
            const active = (route.page === 'posts' && Number(route.params.cat) === c.id)
            return (
              <a key={c.id} href={`#/posts?cat=${c.id}`} className={`side-item ${active ? 'active' : ''}`}>
                <Icon name={categoryIcon(c)} /> <span className="grow ellipsis">{c.name}</span>
                {c.postCount > 0 && <span className="side-meta">{c.postCount}</span>}
              </a>
            )
          })}
        </div>
      </>}
      {section === 'admin' && me.role === 'ADMIN' && <>
        <div className="panel-head"><span>관리</span></div>
        <div className="sidenav-menu">
          <a href="#/admin" className={`side-item ${route.page === 'admin' && route.tab !== 'boards' ? 'active' : ''}`}><Icon name="users" /> 사용자 관리</a>
          <a href="#/admin/boards" className={`side-item ${route.page === 'admin' && route.tab === 'boards' ? 'active' : ''}`}><Icon name="settings" /> 게시판 관리</a>
        </div>
      </>}
      {creating && <CreateBoardModal onClose={() => setCreating(false)} />}
    </aside>
    </>
  )
}

/** 레일 하단: 내 계정 메뉴 (비밀번호·2단계 인증·로그아웃) */
function RailUser() {
  const { me } = useApp()
  return (
    <div className="rail-user" title={`${me.name} (${me.username})`}>
      <UserDropdown />
    </div>
  )
}

/** 패널 워크스페이스 한 줄: 마크·이름·잠금·진행률, 마우스를 올리면 위/아래 이동·즐겨찾기 별 */
function BoardRow({ b, route, onMove, first, last }) {
  const pct = b.showProgress !== false && b.itemCount ? Math.floor((b.doneCount / b.itemCount) * 100) : null
  const mv = (dir) => (e) => { e.preventDefault(); e.stopPropagation(); onMove(b, dir) }
  return (
    <a href={`#/board/${b.id}`} className={`side-item side-board ${route.boardId === b.id ? 'active' : ''}`} title={b.description || b.name}>
      <BoardMark board={b} size={16} />
      <span className="grow ellipsis">{b.name}</span>
      {!b.isPublic && <Icon name="lock" size={12} className="side-lock" />}
      {pct != null && <span className="side-meta">{pct}%</span>}
      <span className="side-move">
        <button type="button" className="side-move-btn" title="위로 이동" aria-label="위로 이동" disabled={first} onClick={mv(-1)}><Icon name="arrowUp" size={11} /></button>
        <button type="button" className="side-move-btn" title="아래로 이동" aria-label="아래로 이동" disabled={last} onClick={mv(1)}><Icon name="arrowDown" size={11} /></button>
      </span>
      <FavStar board={b} />
    </a>
  )
}

export function CreateBoardModal({ onClose }) {
  const { refreshBoards } = useApp()
  const [isPublic, setPublic] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  const toast = useToast()
  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    try {
      const b = await api.post('/api/boards', { name, description, isPublic })
      refreshBoards(); onClose(); navigate(`/board/${b.id}`)
    } catch (err) { toast(err.message, 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title="새 워크스페이스" onClose={onClose}>
      <form onSubmit={submit}>
        <label className="field"><span>이름</span>
          <input className="input" autoFocus maxLength={100} value={name} onChange={(e) => setName(e.target.value)} placeholder="예: 고객사관리, 요청사항, 2026 WFM 고도화" required /></label>
        <label className="field"><span>설명 <em>선택</em></span>
          <textarea className="input" rows={3} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="이 워크스페이스에서 관리할 내용을 한 줄로 설명하세요" /></label>
        <div className="field"><span>공개 범위</span><VisibilityPicker value={isPublic} onChange={setPublic} /></div>
        <p className="hint">기본 상태(대기 · 진행중 · 막힘 · 완료)·그룹 1개·보기 탭이 함께 만들어집니다. 컬럼·탭·멤버는 만든 뒤 설정에서 바꿀 수 있습니다. 기존 업무 엑셀이 있으면 <a className="link" href="#/import/excel" onClick={onClose}>엑셀로 워크스페이스 만들기</a>.</p>
        <div className="form-actions">
          <button type="button" className="btn" onClick={onClose}>취소</button>
          <button className="btn btn-primary" disabled={busy}>만들기</button>
        </div>
      </form>
    </Modal>
  )
}
