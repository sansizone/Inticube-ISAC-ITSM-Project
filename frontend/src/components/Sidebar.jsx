import { GroupSettingsModal, GroupSelect } from './WsGroups.jsx'
import React, { useEffect, useRef, useState } from 'react'
import { useApp, navigate } from '../App.jsx'
import { api } from '../api.js'
import { Modal, useToast, Popover, usePopover } from './common.jsx'
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
  const { me, boards, categories, unread, panelSection, setPanelSection, openSidebar, refreshBoards, wsGroups } = useApp()
  const toast = useToast()
  const [creating, setCreating] = useState(false) // true 또는 만들 그룹 ID
  const [groupEdit, setGroupEdit] = useState(null) // 설정할 그룹 ('new' = 새 그룹)
  // 지금 보고 있는 워크스페이스 그룹: 이번 세션에 고른 것 → 내가 정한 기본 그룹 → 첫 그룹
  const [curGroupId, setCurGroupId] = useState(() => { try { return Number(sessionStorage.getItem('tb.wsg.current')) || null } catch { return null } })
  const pickGroup = (id) => { setCurGroupId(id); try { sessionStorage.setItem('tb.wsg.current', String(id)) } catch { /* 무시 */ } }
  const curGroup = wsGroups.find((g) => g.id === curGroupId) || wsGroups.find((g) => g.isDefault) || wsGroups[0] || null
  // 다른 그룹의 워크스페이스로 이동하면(검색·링크) 그 그룹으로 전환
  const routeGroup = route.boardId ? boards.find((b) => b.id === route.boardId)?.groupId : null
  useEffect(() => { if (routeGroup && wsGroups.some((g) => g.id === routeGroup) && routeGroup !== curGroup?.id) pickGroup(routeGroup) }, [routeGroup, wsGroups.length]) // eslint-disable-line
  const [showAllBoards, setShowAllBoards] = useState(false)
  // 즐겨찾기도 지금 고른 그룹 안의 것만 보여 준다
  const inCurGroup = (b) => !wsGroups.length || (curGroup ? b.groupId === curGroup.id : !b.groupId)
  const favBoards = boards.filter((b) => b.favorite && inCurGroup(b))
  const restBoards = boards.filter((b) => !b.favorite)
  const limit = favBoards.length ? 8 : 12
  const shownBoards = showAllBoards ? restBoards : restBoards.slice(0, limit)
  const section = panelSection
  /** 워크스페이스 위/아래 이동 — 같은 목록(즐겨찾기/전체) 안의 이웃과 자리를 바꿔 전체 순서를 서버에 저장 (사용자별) */
  const moveBoard = async (b, dir, inList) => {
    const list = inList || (b.favorite ? favBoards : restBoards)
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
            <button className="icon-btn icon-btn-sm" title="새 워크스페이스" aria-label="새 워크스페이스" onClick={() => setCreating(curGroup ? curGroup.id : true)}><Icon name="plus" size={14} /></button>
          </span>
        </div>
        {wsGroups.length > 0 && <GroupSwitcher groups={wsGroups} current={curGroup} boards={boards} onPick={pickGroup}
          onEdit={(g) => setGroupEdit(g)} onNew={me.role === 'ADMIN' ? () => setGroupEdit('new') : null} />}
        <div className="sidenav-menu">
          {favBoards.length > 0 && <>
            <div className="sidenav-subheading"><Icon name="star" size={11} /> 즐겨찾기</div>
            {favBoards.map((b, i) => <BoardRow key={b.id} b={b} route={route} onMove={moveBoard} first={i === 0} last={i === favBoards.length - 1} />)}
            {restBoards.length > 0 && wsGroups.length === 0 && <div className="sidenav-subheading">전체</div>}
          </>}
          {wsGroups.length > 0 ? (() => {
            const list = restBoards.filter((b) => (curGroup ? b.groupId === curGroup.id : !b.groupId))
            return <>
              {favBoards.length > 0 && <div className="sidenav-subheading">{curGroup?.name || '워크스페이스'}</div>}
              {list.map((b, i) => <BoardRow key={b.id} b={b} route={route} onMove={(x, d) => moveBoard(x, d, list)} first={i === 0} last={i === list.length - 1} />)}
              {list.length === 0 && curGroup && <button className="side-item muted" onClick={() => setCreating(curGroup.id)}><Icon name="plus" /> 워크스페이스 만들기</button>}
            </>
          })() : <>
          {shownBoards.map((b, i) => <BoardRow key={b.id} b={b} route={route} onMove={moveBoard} first={i === 0} last={i === restBoards.length - 1} />)}
          {restBoards.length > limit && <button className="side-item muted" onClick={() => setShowAllBoards((v) => !v)}>{showAllBoards ? '접기' : `${restBoards.length - limit}개 더 보기`}</button>}
          </>}
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
              </a>
            )
          })}
        </div>
      </>}
      {section === 'admin' && me.role === 'ADMIN' && <>
        <div className="panel-head"><span>관리</span></div>
        <div className="sidenav-menu">
          <a href="#/admin" className={`side-item ${route.page === 'admin' && !['boards', 'audit', 'trash'].includes(route.tab) ? 'active' : ''}`}><Icon name="users" /> 사용자 관리</a>
          <a href="#/admin/boards" className={`side-item ${route.page === 'admin' && route.tab === 'boards' ? 'active' : ''}`}><Icon name="settings" /> 게시판 관리</a>
          <a href="#/admin/trash" className={`side-item ${route.page === 'admin' && route.tab === 'trash' ? 'active' : ''}`}><Icon name="trash" /> 휴지통</a>
          <a href="#/admin/audit" className={`side-item ${route.page === 'admin' && route.tab === 'audit' ? 'active' : ''}`}><Icon name="shield" /> 감사 로그</a>
        </div>
      </>}
      {creating && <CreateBoardModal groupId={typeof creating === 'number' ? creating : null} onClose={() => setCreating(false)} />}
      {groupEdit && <GroupSettingsModal group={groupEdit === 'new' ? null : groupEdit} onClose={() => setGroupEdit(null)} />}
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

export function CreateBoardModal({ onClose, groupId: initialGroup }) {
  const { refreshBoards, wsGroups } = useApp()
  const [groupId, setGroupId] = useState(initialGroup || wsGroups[0]?.id || null)
  const [isPublic, setPublic] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  const toast = useToast()
  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    try {
      const b = await api.post('/api/boards', { name, description, isPublic, groupId })
      refreshBoards(); onClose(); navigate(`/board/${b.id}`)
    } catch (err) { toast(err.message, 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title="새 워크스페이스" onClose={onClose}>
      <form onSubmit={submit}>
        <label className="field"><span>이름</span>
          <input className="input" autoFocus maxLength={100} value={name} onChange={(e) => setName(e.target.value)} placeholder="예: 고객사관리, 요청사항" required /></label>
        <label className="field"><span>설명 <em>선택</em></span>
          <textarea className="input" rows={3} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="이 워크스페이스에서 관리할 내용을 한 줄로 설명하세요" /></label>
        {wsGroups.length > 0 && <label className="field"><span>워크스페이스 그룹</span><GroupSelect value={groupId} onChange={setGroupId} /></label>}
        <div className="field"><span>공개 범위</span><VisibilityPicker value={isPublic} onChange={setPublic} /></div>
        <p className="hint">기본 상태(대기 · 처리중 · 막힘 · 완료)·그룹 1개·보기 탭이 함께 만들어집니다. 컬럼·탭·멤버는 만든 뒤 설정에서 바꿀 수 있습니다. 기존 업무 엑셀이 있으면 <a className="link" href="#/import/excel" onClick={onClose}>엑셀로 워크스페이스 만들기</a>.</p>
        <div className="form-actions">
          <button type="button" className="btn" onClick={onClose}>취소</button>
          <button className="btn btn-primary" disabled={busy}>만들기</button>
        </div>
      </form>
    </Modal>
  )
}

/**
 * 워크스페이스 그룹 전환 드롭다운 (사이드바 상단). 그룹·워크스페이스 검색, 기본 그룹 고정(핀)
 */
function GroupSwitcher({ groups, current, boards, onPick, onEdit, onNew }) {
  const { refreshGroups } = useApp()
  const toast = useToast()
  const pop = usePopover()
  const [q, setQ] = useState('')
  const inputRef = useRef(null)
  useEffect(() => { if (pop.anchor) { setQ(''); setTimeout(() => inputRef.current?.focus(), 0) } }, [pop.anchor])
  const needle = q.trim().toLowerCase()
  const shownGroups = groups.filter((g) => !needle || g.name.toLowerCase().includes(needle))
  const shownBoards = needle ? boards.filter((b) => b.name.toLowerCase().includes(needle)).slice(0, 12) : []
  const groupName = (id) => groups.find((g) => g.id === id)?.name || ''
  const setDefault = async (e, g) => {
    e.stopPropagation()
    try {
      if (g.isDefault) { await api.del(`/api/ws-groups/${g.id}/default`); toast('그룹 고정을 해제했습니다.') }
      else { await api.put(`/api/ws-groups/${g.id}/default`); toast(`'${g.name}'을(를) 고정했습니다. 다음 접속 때 이 그룹이 먼저 보입니다.`, 'success') }
      refreshGroups()
    } catch (err) { toast(err.message, 'error') }
  }
  return (
    <div className="wsg-switch-wrap">
      <button type="button" className="wsg-switch" onClick={pop.open} aria-haspopup="listbox" aria-expanded={!!pop.anchor} title="워크스페이스 그룹 전환">
        <span className="wsg-mark" style={{ background: boardColor(current?.id || 0) }}>{(current?.name || '?').trim().charAt(0)}</span>
        <span className="grow ellipsis">{current?.name || '그룹 선택'}</span>
        {current?.isDefault && <span className="wsg-def" title="고정된 그룹"><Icon name="pin" size={12} /></span>}
        <Icon name="chevronDown" size={13} />
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={290} className="wsg-pop">
          <div className="filter-search" style={{ margin: 8 }}>
            <Icon name="search" size={13} />
            <input ref={inputRef} placeholder="그룹·워크스페이스 검색" value={q} onChange={(e) => setQ(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { if (shownGroups[0]) { onPick(shownGroups[0].id); pop.close() } else if (shownBoards[0]) { onPick(shownBoards[0].groupId); pop.close(); navigate(`/board/${shownBoards[0].id}`) } } }} />
          </div>
          <div className="menu" style={{ maxHeight: '22rem', overflowY: 'auto', paddingTop: 0 }}>
            {shownGroups.length > 0 && <div className="menu-label">워크스페이스 그룹</div>}
            {shownGroups.map((g) => (
              <button key={g.id} type="button" className={`menu-row wsg-row ${current?.id === g.id ? 'on' : ''}`} onClick={() => { onPick(g.id); pop.close() }}>
                <span className="wsg-mark sm" style={{ background: boardColor(g.id) }}>{g.name.trim().charAt(0)}</span>
                <span className="grow ellipsis">{g.name}</span>
                <span className="muted small">{g.boardCount}</span>
                <span role="button" tabIndex={0} className={`wsg-star ${g.isDefault ? 'on' : ''}`} title={g.isDefault ? '고정 해제' : '이 그룹 고정'} aria-label={g.isDefault ? '고정 해제' : '이 그룹 고정'}
                  onClick={(e) => setDefault(e, g)} onKeyDown={(e) => { if (e.key === 'Enter') setDefault(e, g) }}><Icon name="pin" size={13} /></span>
              </button>
            ))}
            {shownGroups.length === 0 && !shownBoards.length && <div className="menu-empty">검색 결과가 없습니다.</div>}
            {shownBoards.length > 0 && <>
              <div className="menu-label">워크스페이스</div>
              {shownBoards.map((b) => (
                <button key={b.id} type="button" className="menu-row" onClick={() => { if (b.groupId) onPick(b.groupId); pop.close(); navigate(`/board/${b.id}`) }}>
                  <BoardMark board={b} size={16} /><span className="grow ellipsis">{b.name}</span><span className="muted small ellipsis" style={{ maxWidth: '7rem' }}>{groupName(b.groupId)}</span>
                </button>
              ))}
            </>}
          </div>
          <div className="menu-sep" />
          <div className="menu">
            {current?.myRole === 'ADMIN' && <button type="button" className="menu-row" onClick={() => { pop.close(); onEdit(current) }}><Icon name="settings" size={14} /> '{current.name}' 설정·멤버</button>}
            {onNew && <button type="button" className="menu-row" onClick={() => { pop.close(); onNew() }}><Icon name="plus" size={14} /> 새 워크스페이스 그룹</button>}
            <div className="hint" style={{ margin: '4px 10px 6px' }}>핀을 눌러 고정한 그룹이 접속할 때 먼저 보입니다.</div>
          </div>
        </Popover>
      )}
    </div>
  )
}
