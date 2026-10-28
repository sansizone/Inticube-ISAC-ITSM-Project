import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useApp } from '../App.jsx'
import { useToast, useConfirm, Empty, Spinner } from './common.jsx'
import { Icon } from './icons.jsx'
import { Markdown, MarkdownEditor } from './markdown.jsx'
import { fmtDateTime } from '../util.js'

/** 워크스페이스 위키: 왼쪽 페이지 트리, 오른쪽 본문(마크다운) */
export default function WikiView({ board }) {
  const { users, mentionNames } = useApp()
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [pages, setPages] = useState(null)
  const [cur, setCur] = useState(null)
  const [page, setPage] = useState(null)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState({ title: '', content: '' })
  const [q, setQ] = useState('')
  const storeKey = `tb.wiki.${board.id}`

  const load = () => api.get(`/api/boards/${board.id}/wiki`).then((l) => {
    setPages(l)
    if (cur == null && l.length) { let last = null; try { last = Number(localStorage.getItem(storeKey)) } catch { /* 무시 */ } setCur(l.some((p) => p.id === last) ? last : l[0].id) }
    return l
  }).catch((e) => toast(e.message, 'error'))
  useEffect(() => { load() }, [board.id]) // eslint-disable-line
  useEffect(() => {
    if (cur == null) { setPage(null); return }
    try { localStorage.setItem(storeKey, String(cur)) } catch { /* 무시 */ }
    api.get(`/api/wiki/${cur}`).then((p) => { setPage(p); setEditing(false) }).catch((e) => toast(e.message, 'error'))
  }, [cur]) // eslint-disable-line

  const create = async (parentId) => {
    try {
      const p = await api.post(`/api/boards/${board.id}/wiki`, { title: '새 페이지', content: '', parentId })
      await load(); setCur(p.id); setDraft({ title: p.title, content: '' }); setEditing(true)
    } catch (e) { toast(e.message, 'error') }
  }
  const save = async () => {
    if (!draft.title.trim()) { toast('제목을 입력하세요.', 'error'); return }
    try { const p = await api.put(`/api/wiki/${page.id}`, { title: draft.title, content: draft.content }); setPage(p); setEditing(false); load(); toast('저장했습니다.', 'success') } catch (e) { toast(e.message, 'error') }
  }
  const remove = async () => {
    if (!(await confirm(`'${page.title}' 페이지를 삭제할까요?\n하위 페이지는 한 단계 위로 올라갑니다.`, { okText: '삭제' }))) return
    try { await api.del(`/api/wiki/${page.id}`); setCur(null); const l = await load(); if (l?.length) setCur(l[0].id) } catch (e) { toast(e.message, 'error') }
  }
  const moveParent = async (parentId) => {
    try { const p = await api.put(`/api/wiki/${page.id}`, { parentId: parentId == null ? 0 : parentId }); setPage(p); load() } catch (e) { toast(e.message, 'error') }
  }

  if (!pages) return <div className="center-screen"><Spinner /></div>
  const kids = {}
  for (const p of pages) (kids[p.parentId || 'root'] ||= []).push(p)
  const match = (p) => !q || p.title.toLowerCase().includes(q.toLowerCase())
  const tree = (parent, depth) => (kids[parent] || []).map((p) => (
    <React.Fragment key={p.id}>
      {match(p) && (
        <button className={`wiki-node ${p.id === cur ? 'active' : ''}`} style={{ paddingLeft: `${0.5 + depth * 0.9}rem` }} onClick={() => setCur(p.id)}>
          <Icon name={kids[p.id] ? 'folder' : 'doc'} size={13} /><span className="ellipsis">{p.title}</span>
        </button>
      )}
      {tree(p.id, depth + 1)}
    </React.Fragment>
  ))
  const ancestors = (id) => { const out = []; let x = pages.find((p) => p.id === id); while (x?.parentId) { x = pages.find((p) => p.id === x.parentId); if (x) out.unshift(x) } return out }

  return (
    <div className="wiki">
      <aside className="wiki-side">
        <div className="filter-search" style={{ width: '100%' }}><Icon name="search" size={13} /><input placeholder="페이지 찾기" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }} /></div>
        <div className="wiki-tree">{tree('root', 0)}{pages.length === 0 && <div className="menu-empty">아직 페이지가 없습니다.</div>}</div>
        <button className="btn btn-sm btn-block" onClick={() => create(null)}><Icon name="plus" size={14} /> 새 페이지</button>
      </aside>
      <div className="wiki-main">
        {!page ? <Empty icon="book" title="위키 페이지를 만드세요">운영 절차, 장애 대응 매뉴얼, 고객사별 환경 정보처럼 팀이 같이 봐야 하는 문서를 적어 두세요. 마크다운을 지원합니다.</Empty> : editing ? (
          <>
            <input className="title-input" value={draft.title} maxLength={200} onChange={(e) => setDraft({ ...draft, title: e.target.value })} placeholder="제목" autoFocus />
            <MarkdownEditor value={draft.content} onChange={(v) => setDraft({ ...draft, content: v })} rows={22} users={users} mentionNames={mentionNames}
              placeholder={'## 제목, **굵게**, - 목록, - [ ] 체크리스트, > 인용, `코드`, 표, [링크](https://) 를 지원합니다.'}
              onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) save() }} />
            <div className="form-actions">
              <span className="hint grow" style={{ margin: 0 }}>Ctrl+Enter 로 저장</span>
              <button className="btn btn-sm" onClick={() => setEditing(false)}>취소</button>
              <button className="btn btn-sm btn-primary" onClick={save}>저장</button>
            </div>
          </>
        ) : (
          <>
            <div className="wiki-crumbs">
              {ancestors(page.id).map((a) => <React.Fragment key={a.id}><button className="crumb crumb-link" onClick={() => setCur(a.id)}>{a.title}</button><span className="crumb-sep">/</span></React.Fragment>)}
              <span className="crumb crumb-current">{page.title}</span>
              <span className="grow" />
              <button className="btn btn-sm" onClick={() => { setDraft({ title: page.title, content: page.content || '' }); setEditing(true) }}><Icon name="edit" size={13} /> 편집</button>
              <button className="btn btn-sm" onClick={() => create(page.id)} title="이 페이지 아래에 새 페이지"><Icon name="plus" size={13} /> 하위 페이지</button>
              <select className="input input-sm" style={{ width: 'auto' }} value={page.parentId || ''} onChange={(e) => moveParent(e.target.value ? Number(e.target.value) : null)} title="상위 페이지 바꾸기">
                <option value="">최상위</option>
                {pages.filter((p) => p.id !== page.id).map((p) => <option key={p.id} value={p.id}>{p.title}</option>)}
              </select>
              <button className="icon-btn" aria-label="삭제" onClick={remove}><Icon name="trash" /></button>
            </div>
            <h2 className="wiki-title">{page.title}</h2>
            <div className="wiki-meta muted small">{page.updatedBy?.name || page.createdBy?.name} · {fmtDateTime(page.updatedAt)}</div>
            {page.content ? <div className="desc-view wiki-body" onDoubleClick={() => { setDraft({ title: page.title, content: page.content || '' }); setEditing(true) }}><Markdown text={page.content} mentionNames={mentionNames} /></div>
              : <button className="desc-empty" onClick={() => { setDraft({ title: page.title, content: '' }); setEditing(true) }}>내용을 입력하세요…</button>}
            {(kids[page.id] || []).length > 0 && (
              <div className="wiki-children">
                <div className="dsec-head"><h4>하위 페이지</h4></div>
                {kids[page.id].map((p) => <button key={p.id} className="menu-row" onClick={() => setCur(p.id)}><Icon name="doc" size={13} /> {p.title}</button>)}
              </div>
            )}
          </>
        )}
      </div>
      {confirmDialog}
    </div>
  )
}
