import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useApp, navigate, PageHeader } from '../App.jsx'
import { Avatar, Spinner, Empty } from './common.jsx'
import { Icon } from './icons.jsx'
import { categoryIcon } from './Sidebar.jsx'
import { relTime } from '../util.js'

function postHref(params) {
  const p = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '' && !(k === 'page' && Number(v) === 0)) p.set(k, v) })
  const s = p.toString()
  return `/posts${s ? `?${s}` : ''}`
}

export default function PostList({ params }) {
  const { me, categories } = useApp()
  const catId = params.cat ? Number(params.cat) : null
  const page = Number(params.page || 0)
  const q = params.q || ''
  const [data, setData] = useState(null)
  const [query, setQuery] = useState(q)
  const cat = categories.find((c) => c.id === catId)
  const canWrite = !cat || !cat.adminOnlyWrite || me.role === 'ADMIN'

  useEffect(() => { setQuery(q) }, [q])
  useEffect(() => {
    setData(null)
    const u = new URLSearchParams({ page, size: 20 })
    if (catId) u.set('categoryId', catId)
    if (q) u.set('q', q)
    api.get(`/api/posts?${u}`).then(setData).catch(() => setData({ pinned: [], content: [], totalPages: 0, totalElements: 0 }))
  }, [catId, page, q])

  const isNew = (iso) => Date.now() - new Date(iso).getTime() < 24 * 3600 * 1000

  return (
    <>
      <PageHeader crumbs={[{ label: '게시판', href: '#/posts' }]} title={cat ? cat.name : '전체 글'} icon={cat ? categoryIcon(cat) : 'doc'}
        subtitle={(cat ? (cat.description || '') : '모든 게시판의 글을 모아봅니다.') + (cat?.adminOnlyWrite ? ' · 관리자만 작성할 수 있습니다.' : '')}
        actions={canWrite && <button className="btn btn-white btn-sm btn-strong" onClick={() => navigate(`/posts/new${catId ? `?cat=${catId}` : ''}`)}><Icon name="edit" size={14} /> 글쓰기</button>} />
      <div className="page">
        <div className="card">
        <div className="card-header post-toolbar">
          <div className="cat-tabs">
            <a href={`#${postHref({ q })}`} className={!catId ? 'active' : ''}>전체</a>
            {categories.map((c) => <a key={c.id} href={`#${postHref({ cat: c.id, q })}`} className={catId === c.id ? 'active' : ''}>{c.name}</a>)}
          </div>
          <form className="filter-search" onSubmit={(e) => { e.preventDefault(); navigate(postHref({ cat: catId, q: query.trim() })) }}>
            <Icon name="search" size={13} />
            <input placeholder="제목·내용·작성자 검색" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="게시글 검색" />
            {q && <button type="button" className="icon-btn icon-btn-sm" aria-label="검색 지우기" onClick={() => navigate(postHref({ cat: catId }))}><Icon name="x" size={12} /></button>}
          </form>
        </div>

        <div className="card-body tight">
        {q && data && <p className="hint" style={{ padding: '0 18px' }}>“{q}” 검색 결과 {data.totalElements}건</p>}

        {!data ? <Spinner /> : (data.pinned.length + data.content.length === 0) ? (
          <Empty icon="doc" title={q ? '검색 결과가 없습니다' : '아직 글이 없습니다'}>
            {!q && canWrite && <button className="btn btn-primary btn-sm" onClick={() => navigate(`/posts/new${catId ? `?cat=${catId}` : ''}`)}>첫 글 쓰기</button>}
          </Empty>
        ) : (
          <div className="post-table">
            <div className="post-row post-row-head" aria-hidden="true">
              <span className="pc-title">제목</span><span className="pc-author">작성자</span><span className="pc-date">작성일</span><span className="pc-views">조회</span>
            </div>
            {[...data.pinned, ...data.content].map((p, idx) => (
              <a key={`${p.pinned && idx < data.pinned.length ? 'pin' : 'row'}-${p.id}`} href={`#/posts/${p.id}`} className={`post-row ${p.pinned && idx < data.pinned.length ? 'pinned' : ''}`}>
                <span className="pc-title">
                  {p.pinned && idx < data.pinned.length ? <span className="pin-badge"><Icon name="pin" size={11} /> 공지</span>
                    : !catId && <span className="tag">{p.categoryName}</span>}
                  <span className="post-title ellipsis">{p.title}</span>
                  {p.commentCount > 0 && <span className="post-cc">{p.commentCount}</span>}
                  {p.fileCount > 0 && <Icon name="clip" size={12} className="muted" />}
                  {isNew(p.createdAt) && <span className="new-dot" title="새 글">N</span>}
                </span>
                <span className="pc-author"><Avatar user={p.author} size={18} /><span className="ellipsis">{p.author.name}</span></span>
                <span className="pc-date" title={p.createdAt.slice(0, 16).replace('T', ' ')}>{relTime(p.createdAt)}</span>
                <span className="pc-views num">{p.viewCount}</span>
              </a>
            ))}
          </div>
        )}

        </div>
        {data && data.totalPages > 1 && (
          <nav className="pager card-footer" aria-label="페이지">
            <a className={`pager-btn ${page === 0 ? 'disabled' : ''}`} href={`#${postHref({ cat: catId, q, page: Math.max(0, page - 1) })}`} aria-label="이전"><Icon name="chevronLeft" size={14} /></a>
            {pageNumbers(page, data.totalPages).map((n, i) => n === '…'
              ? <span key={`e${i}`} className="pager-gap">…</span>
              : <a key={n} className={`pager-btn ${n === page ? 'active' : ''}`} href={`#${postHref({ cat: catId, q, page: n })}`}>{n + 1}</a>)}
            <a className={`pager-btn ${page >= data.totalPages - 1 ? 'disabled' : ''}`} href={`#${postHref({ cat: catId, q, page: Math.min(data.totalPages - 1, page + 1) })}`} aria-label="다음"><Icon name="chevronRight" size={14} /></a>
          </nav>
        )}
        </div>
      </div>
    </>
  )
}

function pageNumbers(cur, total) {
  const set = new Set([0, total - 1, cur - 1, cur, cur + 1].filter((n) => n >= 0 && n < total))
  const arr = [...set].sort((a, b) => a - b)
  const out = []
  arr.forEach((n, i) => { if (i > 0 && n - arr[i - 1] > 1) out.push('…'); out.push(n) })
  return out
}
