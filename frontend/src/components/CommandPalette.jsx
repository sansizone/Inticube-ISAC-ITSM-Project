import React, { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { api } from '../api.js'
import { useApp, navigate } from '../App.jsx'
import { Icon } from './icons.jsx'
import { BoardMark, categoryIcon } from './Sidebar.jsx'
import { friendlyDate } from '../util.js'

export default function CommandPalette({ onClose }) {
  const { boards, categories, me, openItem } = useApp()
  const [q, setQ] = useState('')
  const [items, setItems] = useState([])
  const [posts, setPosts] = useState([])
  const [hi, setHi] = useState(0)
  const listRef = useRef(null)

  useEffect(() => {
    const k = q.trim()
    if (!k) { setItems([]); setPosts([]); return }
    const t = setTimeout(() => {
      api.get(`/api/search?q=${encodeURIComponent(k)}`).then((r) => setItems(r.slice(0, 8))).catch(() => {})
      api.get(`/api/posts?q=${encodeURIComponent(k)}&size=6`).then((r) => setPosts(r.content)).catch(() => {})
    }, 200)
    return () => clearTimeout(t)
  }, [q])

  const results = useMemo(() => {
    const k = q.trim().toLowerCase()
    const nav = [
      { key: 'home', group: '이동', icon: <Icon name="home" />, label: '홈', go: () => navigate('/') },
      { key: 'inbox', group: '이동', icon: <Icon name="inbox" />, label: '알림함', go: () => navigate('/inbox') },
      { key: 'posts', group: '이동', icon: <Icon name="doc" />, label: '전체 게시글', go: () => navigate('/posts') },
      { key: 'write', group: '이동', icon: <Icon name="edit" />, label: '새 글 쓰기', go: () => navigate('/posts/new') },
      ...categories.map((c) => ({ key: `c${c.id}`, group: '이동', icon: <Icon name={categoryIcon(c)} />, label: c.name, meta: '게시판', go: () => navigate(`/posts?cat=${c.id}`) })),
      ...boards.map((b) => ({ key: `b${b.id}`, group: '이동', icon: <BoardMark board={b} size={16} />, label: b.name, meta: '워크스페이스', go: () => navigate(`/board/${b.id}`) })),
      ...(me.role === 'ADMIN' ? [{ key: 'admin', group: '이동', icon: <Icon name="settings" />, label: '관리자 설정', go: () => navigate('/admin') }] : []),
    ].filter((x) => !k || x.label.toLowerCase().includes(k))
    const its = items.map((i) => ({
      key: `i${i.id}`, group: '업무', icon: <span className="status-dot" style={{ background: i.status ? i.status.color : 'var(--fg-4)' }} />,
      label: `${i.key ? i.key + ' ' : ''}${i.name}`, meta: `${i.boardName}${i.dueDate ? ` · ${friendlyDate(i.dueDate)}` : ''}`, go: () => openItem(i.boardId, i.id),
    }))
    const ps = posts.map((p) => ({
      key: `p${p.id}`, group: '게시글', icon: <Icon name="doc" />, label: p.title, meta: `${p.categoryName} · ${p.author.name}`, go: () => navigate(`/posts/${p.id}`),
    }))
    return [...(k ? its : []), ...(k ? ps : []), ...nav.slice(0, k ? 6 : 20)]
  }, [q, items, posts, boards, categories, me, openItem])

  useEffect(() => { setHi(0) }, [q, items.length, posts.length])
  useEffect(() => {
    const el = listRef.current?.querySelector('.cmd-row.hi')
    if (el) el.scrollIntoView({ block: 'nearest' })
  }, [hi])

  const run = (r) => { onClose(); r.go() }
  let lastGroup = null

  return createPortal(
    <div className="cmd-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="cmd" role="dialog" aria-label="검색">
        <div className="cmd-input">
          <Icon name="search" />
          <input autoFocus placeholder="업무, 게시글, 워크스페이스 검색 또는 이동…" value={q} onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') onClose()
              if (e.key === 'ArrowDown') { e.preventDefault(); setHi((h) => Math.min(h + 1, results.length - 1)) }
              if (e.key === 'ArrowUp') { e.preventDefault(); setHi((h) => Math.max(h - 1, 0)) }
              if (e.key === 'Enter' && results[hi]) { e.preventDefault(); run(results[hi]) }
            }} />
          <button type="button" className="kbd kbd-btn" onClick={onClose} title="닫기 (Esc)" aria-label="검색 닫기">ESC</button>
        </div>
        <div className="cmd-list" ref={listRef}>
          {results.map((r, i) => {
            const head = r.group !== lastGroup ? <div className="cmd-group">{r.group}</div> : null
            lastGroup = r.group
            return (
              <React.Fragment key={r.key}>
                {head}
                <button className={`cmd-row ${i === hi ? 'hi' : ''}`} onMouseEnter={() => setHi(i)} onClick={() => run(r)}>
                  <span className="cmd-icon">{r.icon}</span>
                  <span className="grow ellipsis">{r.label}</span>
                  {r.meta && <span className="cmd-meta ellipsis">{r.meta}</span>}
                </button>
              </React.Fragment>
            )
          })}
          {results.length === 0 && <div className="cmd-empty">“{q}”에 대한 결과가 없습니다.</div>}
        </div>
        <div className="cmd-foot"><span><kbd className="kbd">↑</kbd><kbd className="kbd">↓</kbd> 이동</span><span><kbd className="kbd">Enter</kbd> 열기</span></div>
      </div>
    </div>,
    document.body,
  )
}
