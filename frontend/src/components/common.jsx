import React, { useEffect, useLayoutEffect, useRef, useState, createContext, useContext, useCallback } from 'react'
import { createPortal } from 'react-dom'
import { avatarColor, initials, sc } from '../util.js'
import { Icon, StatusIcon, PriorityIcon } from './icons.jsx'
import { PRIORITIES } from '../util.js'

// ---------- 토스트 ----------
const ToastCtx = createContext(() => {})
export const useToast = () => useContext(ToastCtx)
export function ToastProvider({ children }) {
  const [list, setList] = useState([])
  const push = useCallback((msg, type = 'info') => {
    const id = Math.random()
    setList((l) => [...l, { id, msg, type }])
    setTimeout(() => setList((l) => l.filter((t) => t.id !== id)), 3500)
  }, [])
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {list.map((t) => (
          <div key={t.id} className={`toast toast-${t.type}`}>
            <span className="toast-dot" />{t.msg}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  )
}

// ---------- 팝오버 ----------
export function Popover({ anchor, onClose, children, width: rawWidth = 220, align = 'left', className = '', side = 'bottom' }) {
  const width = sc(rawWidth)
  const ref = useRef(null)
  const [pos, setPos] = useState({ top: -9999, left: -9999 })
  useLayoutEffect(() => {
    if (!anchor) return
    const r = anchor.getBoundingClientRect()
    const h = ref.current ? ref.current.offsetHeight : 200
    let top = side === 'right' ? r.top : r.bottom + 4
    if (top + h > window.innerHeight - 8) top = side === 'right' ? Math.max(8, window.innerHeight - h - 8) : Math.max(8, r.top - h - 4)
    let left = side === 'right' ? r.right + 6 : align === 'right' ? r.right - width : r.left
    left = Math.min(Math.max(8, left), window.innerWidth - width - 8)
    setPos({ top, left })
  }, [anchor, width, align, side])
  useEffect(() => {
    const onDown = (e) => {
      if (ref.current && !ref.current.contains(e.target) && !(anchor && anchor.contains(e.target))) onClose()
    }
    const onKey = (e) => { if (e.key === 'Escape') { e.stopPropagation(); onClose() } }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey, true)
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey, true) }
  }, [anchor, onClose])
  return createPortal(
    <div ref={ref} className={`popover ${className}`} style={{ top: pos.top, left: pos.left, width }}>{children}</div>,
    document.body,
  )
}

export function usePopover() {
  const [anchor, setAnchor] = useState(null)
  const open = (e) => { e.stopPropagation(); e.preventDefault(); setAnchor(anchor ? null : e.currentTarget) }
  const close = useCallback(() => setAnchor(null), [])
  return { anchor, open, close }
}

// ---------- 모달 ----------
export function Modal({ title, onClose, children, footer, width: rawWidth = 480 }) {
  const width = sc(rawWidth)
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape' && !document.querySelector('.popover')) onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])
  return createPortal(
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="modal" style={{ width }} role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-head">
          <h3>{title}</h3>
          <button className="icon-btn" onClick={onClose} aria-label="닫기"><Icon name="x" /></button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>,
    document.body,
  )
}

export function useConfirm() {
  const [state, setState] = useState(null)
  const confirm = (message, { danger = true, okText = '확인' } = {}) =>
    new Promise((resolve) => setState({ message, danger, okText, resolve }))
  const dialog = state && (
    <Modal title="확인" onClose={() => { state.resolve(false); setState(null) }} width={400}
      footer={<>
        <button className="btn" onClick={() => { state.resolve(false); setState(null) }}>취소</button>
        <button className={`btn ${state.danger ? 'btn-danger' : 'btn-primary'}`} autoFocus
          onClick={() => { state.resolve(true); setState(null) }}>{state.okText}</button>
      </>}>
      <p style={{ margin: 0, whiteSpace: 'pre-line', color: 'var(--fg-2)' }}>{state.message}</p>
    </Modal>
  )
  return [confirm, dialog]
}

// ---------- 아바타 ----------
export function Avatar({ user, size: rawSize = 22 }) {
  const size = sc(rawSize)
  if (!user) return (
    <span className="avatar avatar-empty" style={{ width: size, height: size }} aria-label="담당자 없음">
      <Icon name="user" size={Math.round(rawSize * 0.6)} strokeWidth={2} />
    </span>
  )
  return (
    <span className="avatar" title={user.name} style={{ width: size, height: size, background: avatarColor(user.id), fontSize: Math.max(9, size * 0.42) }}>
      {initials(user.name)}
    </span>
  )
}

// ---------- 선택 메뉴 ----------
function SearchMenu({ placeholder, items, renderItem, onPick, activeKey, extra }) {
  const [q, setQ] = useState('')
  const [hi, setHi] = useState(0)
  const list = items.filter((x) => !q || x.search.toLowerCase().includes(q.toLowerCase()))
  return (
    <div className="menu">
      <input className="menu-search" autoFocus placeholder={placeholder} value={q}
        onChange={(e) => { setQ(e.target.value); setHi(0) }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') { e.preventDefault(); setHi((h) => Math.min(h + 1, list.length - 1)) }
          if (e.key === 'ArrowUp') { e.preventDefault(); setHi((h) => Math.max(h - 1, 0)) }
          if (e.key === 'Enter' && list[hi]) { e.preventDefault(); onPick(list[hi].value) }
        }} />
      <div className="menu-list">
        {list.map((x, i) => (
          <button key={x.key} className={`menu-row ${i === hi ? 'hi' : ''}`} onMouseEnter={() => setHi(i)} onClick={() => onPick(x.value)}>
            {renderItem(x)}
            {activeKey === x.key && <Icon name="check" size={14} className="menu-check" />}
          </button>
        ))}
        {list.length === 0 && <div className="menu-empty">결과 없음</div>}
      </div>
      {extra}
    </div>
  )
}

export function UserPicker({ users, value, onPick, allowNone = true }) {
  const items = [
    ...(allowNone ? [{ key: 'none', value: null, search: '담당자 없음 none', user: null }] : []),
    ...users.map((u) => ({ key: u.id, value: u.id, search: `${u.name} ${u.username} ${u.department || ''}`, user: u })),
  ]
  return <SearchMenu placeholder="담당자 지정…" items={items} activeKey={value ?? 'none'} onPick={onPick}
    renderItem={(x) => <><Avatar user={x.user} size={18} /><span className="grow ellipsis">{x.user ? x.user.name : '담당자 없음'}</span>
      {x.user?.department && <span className="menu-meta">{x.user.department}</span>}</>} />
}

export function StatusPicker({ statuses, value, onPick }) {
  const items = [
    ...statuses.map((s) => ({ key: s.id, value: s.id, search: s.label, status: s })),
    { key: 'none', value: null, search: '상태 없음', status: null },
  ]
  return <SearchMenu placeholder="상태 변경…" items={items} activeKey={value ?? 'none'} onPick={onPick}
    renderItem={(x) => <><StatusIcon status={x.status} statuses={statuses} /><span className="grow">{x.status ? x.status.label : '상태 없음'}</span></>} />
}

export function PriorityPicker({ value, onPick }) {
  const items = [...PRIORITIES.map((p) => ({ key: p.value, value: p.value, search: p.label, p })), { key: 'none', value: null, search: '우선순위 없음', p: null }]
  return <SearchMenu placeholder="우선순위…" items={items} activeKey={value ?? 'none'} onPick={onPick}
    renderItem={(x) => <><PriorityIcon value={x.value} /><span className="grow">{x.p ? x.p.label : '우선순위 없음'}</span></>} />
}

export function Spinner() { return <div className="spinner" aria-label="불러오는 중" /> }

export function Empty({ icon = 'sparkle', title, children }) {
  return (
    <div className="empty">
      <div className="empty-icon"><Icon name={icon} size={20} /></div>
      <div className="empty-title">{title}</div>
      {children && <div className="empty-body">{children}</div>}
    </div>
  )
}

export function Kbd({ children }) { return <kbd className="kbd">{children}</kbd> }

/** 담당업무 트리 선택 (검색 시 경로로 매칭) */
export function DutyPicker({ index, value, onPick, allowNone = true }) {
  const [q, setQ] = useState('')
  const [hi, setHi] = useState(0)
  const all = index.flatten()
  const k = q.trim().toLowerCase()
  const list = [
    ...(allowNone && !k ? [{ id: null, name: '담당업무 없음', depth: 0, none: true }] : []),
    ...all.filter((d) => !k || index.path(d.id).toLowerCase().includes(k)),
  ]
  return (
    <div className="menu">
      <input className="menu-search" autoFocus placeholder="담당업무 검색…" value={q}
        onChange={(e) => { setQ(e.target.value); setHi(0) }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') { e.preventDefault(); setHi((h) => Math.min(h + 1, list.length - 1)) }
          if (e.key === 'ArrowUp') { e.preventDefault(); setHi((h) => Math.max(h - 1, 0)) }
          if (e.key === 'Enter' && list[hi]) { e.preventDefault(); onPick(list[hi].id) }
        }} />
      <div className="menu-list">
        {list.map((d, i) => (
          <button key={d.id ?? 'none'} className={`menu-row ${i === hi ? 'hi' : ''}`} onMouseEnter={() => setHi(i)} onClick={() => onPick(d.id)}
            style={{ paddingLeft: sc(8 + (k ? 0 : d.depth * 14)) }} title={d.none ? '' : index.path(d.id)}>
            {d.none ? <span className="muted">담당업무 없음</span> : <>
              <Icon name="tree" size={13} />
              <span className="grow ellipsis">{k ? index.path(d.id) : d.name}</span>
              {d.primary && <span className="menu-meta">{d.primary.name}</span>}
            </>}
            {value === d.id && <Icon name="check" size={14} className="menu-check" />}
          </button>
        ))}
        {list.length === 0 && <div className="menu-empty">{all.length ? '결과 없음' : '등록된 담당업무가 없습니다. 담당업무 탭에서 추가하세요.'}</div>}
      </div>
    </div>
  )
}
