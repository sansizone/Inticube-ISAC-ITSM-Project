import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useApp } from '../App.jsx'
import { useToast, useConfirm, Empty, Spinner } from './common.jsx'
import { Icon } from './icons.jsx'

/** 휴지통 (시스템 관리자): 삭제된 업무 조회 · 복원 · 영구 삭제 */
const fmt = (s) => (s ? s.replace('T', ' ').slice(0, 16) : '')

export function TrashAdmin() {
  const { boards, refreshBoards } = useApp()
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [boardId, setBoardId] = useState('')
  const [q, setQ] = useState('')
  const [applied, setApplied] = useState({ boardId: '', q: '' })
  const [page, setPage] = useState(0)
  const [data, setData] = useState(null)
  const [busy, setBusy] = useState(null)

  const load = () => {
    const p = new URLSearchParams({ page, size: 50 })
    if (applied.boardId) p.set('boardId', applied.boardId)
    if (applied.q) p.set('q', applied.q)
    api.get(`/api/admin/trash?${p}`).then(setData).catch((e) => { toast(e.message, 'error'); setData({ content: [], total: 0 }) })
  }
  useEffect(load, [applied, page]) // eslint-disable-line

  const restore = async (r) => {
    if (!(await confirm(`'${r.name}'${r.childCount ? ` (하위 업무 ${r.childCount}건 포함)` : ''}을(를) '${r.boardName}'에 복원할까요?`, { okText: '복원', danger: false }))) return
    setBusy(r.id)
    try { await api.post(`/api/admin/trash/${r.id}/restore`); toast('복원했습니다.', 'success'); refreshBoards(); load() }
    catch (e) { toast(e.message, 'error') } finally { setBusy(null) }
  }
  const purge = async (r) => {
    if (!(await confirm(`'${r.name}'${r.childCount ? ` (하위 업무 ${r.childCount}건 포함)` : ''}을(를) 영구 삭제할까요?\n댓글·첨부파일·활동 기록까지 모두 지워지며 되돌릴 수 없습니다. (삭제 사실은 감사 로그에 남습니다)`, { okText: '영구 삭제' }))) return
    setBusy(r.id)
    try { await api.del(`/api/admin/trash/${r.id}`); toast('영구 삭제했습니다.'); load() }
    catch (e) { toast(e.message, 'error') } finally { setBusy(null) }
  }
  const pages = data ? Math.max(1, Math.ceil(data.total / 50)) : 1

  return (
    <div className="audit">
      <form className="audit-filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied({ boardId, q: q.trim() }) }}>
        <label className="field"><span>워크스페이스</span>
          <select className="input input-sm" value={boardId} onChange={(e) => setBoardId(e.target.value)}>
            <option value="">전체</option>
            {boards.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
        </label>
        <label className="field grow"><span>검색어</span>
          <input className="input input-sm" placeholder="업무 이름·사유·워크스페이스" value={q} onChange={(e) => setQ(e.target.value)} />
        </label>
        <div className="audit-actions"><button className="btn btn-primary btn-sm" type="submit"><Icon name="search" size={14} /> 조회</button></div>
      </form>
      <div className="audit-quick"><span className="muted small">삭제된 업무는 여기 남아 있다가 복원하거나 영구 삭제할 수 있습니다.</span>
        <span className="muted small" style={{ marginLeft: 'auto' }}>{data ? `${(data.total || 0).toLocaleString()}건` : ''}</span></div>
      {data == null ? <Spinner /> : data.content.length === 0 ? (
        <Empty icon="trash" title="휴지통이 비어 있습니다">삭제된 업무가 없습니다.</Empty>
      ) : (
        <div className="table-wrap">
          <table className="table audit-table">
            <thead><tr><th>삭제 일시</th><th>워크스페이스</th><th>업무</th><th>삭제자</th><th>사유</th><th>등록자</th><th style={{ width: '10rem' }}></th></tr></thead>
            <tbody>
              {data.content.map((r) => (
                <tr key={r.id} style={{ cursor: 'default' }}>
                  <td className="muted">{fmt(r.deletedAt)}</td>
                  <td><div className="audit-cell" style={{ maxWidth: '10rem' }}>{r.boardName} <span className="muted small">· {r.groupName}</span></div></td>
                  <td><div className="audit-cell" style={{ maxWidth: '16rem' }} title={r.name}>
                    {r.key && <span className="muted small">{r.key} </span>}{r.sub && <span className="tag" style={{ height: '1.1rem', fontSize: '.6875rem' }}>하위</span>} {r.name}
                    {r.childCount > 0 && <span className="muted small"> (+하위 {r.childCount})</span>}</div></td>
                  <td>{r.deletedByName || <span className="muted">-</span>}</td>
                  <td><div className="audit-cell audit-detail" title={r.deleteReason || ''}>{r.deleteReason}</div></td>
                  <td className="muted">{r.createdByName || '-'}</td>
                  <td style={{ textAlign: 'right' }}>
                    <button className="btn btn-sm" disabled={busy === r.id} onClick={() => restore(r)}>복원</button>{' '}
                    <button className="btn btn-sm btn-danger-ghost" disabled={busy === r.id} onClick={() => purge(r)}>영구 삭제</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && pages > 1 && (
        <nav className="pager" aria-label="페이지">
          <button className={`pager-btn ${page === 0 ? 'disabled' : ''}`} disabled={page === 0} onClick={() => setPage(page - 1)} aria-label="이전"><Icon name="chevronLeft" size={14} /></button>
          <span className="small muted" style={{ padding: '0 .5rem' }}>{page + 1} / {pages}</span>
          <button className={`pager-btn ${page >= pages - 1 ? 'disabled' : ''}`} disabled={page >= pages - 1} onClick={() => setPage(page + 1)} aria-label="다음"><Icon name="chevronRight" size={14} /></button>
        </nav>
      )}
      {confirmDialog}
    </div>
  )
}
