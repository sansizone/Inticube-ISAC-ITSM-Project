import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useToast, Empty, Spinner } from './common.jsx'
import { Icon } from './icons.jsx'

/**
 * 감사 로그 (시스템 관리자): 누가, 언제, 무엇을 했는지 — 기간·사용자·종류·검색어로 조회, 엑셀 내보내기.
 * 기록은 수정·삭제할 수 없다 (DB 트리거로도 막음).
 */
const ACTIONS = [
  ['ITEM_CREATE', '업무 생성'], ['ITEM_UPDATE', '업무 변경'], ['ITEM_DELETE', '업무 삭제(휴지통)'], ['ITEM_RESTORE', '업무 복원'], ['ITEM_PURGE', '업무 영구 삭제'], ['ITEM_MOVE', '업무 이동·복제'],
  ['COMMENT_DELETE', '댓글 삭제'], ['FILE_ADD', '파일 첨부'], ['FILE_DELETE', '파일 삭제'], ['PHONE_REVEAL', '개인정보 열람'],
  ['BOARD_CREATE', '워크스페이스 생성'], ['BOARD_UPDATE', '워크스페이스 설정'], ['BOARD_MEMBERS', '워크스페이스 멤버'], ['BOARD_DELETE', '워크스페이스 삭제'],
  ['WSGROUP_CREATE', '그룹 생성'], ['WSGROUP_UPDATE', '그룹 설정'], ['WSGROUP_MEMBERS', '그룹 멤버'], ['WSGROUP_DELETE', '그룹 삭제'],
  ['USER_CREATE', '사용자 생성'], ['USER_UPDATE', '사용자 변경'], ['USER_PW_RESET', '비밀번호 초기화'], ['USER_PW_CHANGE', '비밀번호 변경'],
  ['LOGIN', '로그인'], ['LOGIN_FAIL', '로그인 실패'], ['AUDIT_EXPORT', '감사 로그 내보내기'],
]
const LABEL = Object.fromEntries(ACTIONS)
const TONE = {
  ITEM_DELETE: 'danger', ITEM_PURGE: 'danger', ITEM_RESTORE: 'success', BOARD_DELETE: 'danger', WSGROUP_DELETE: 'danger', COMMENT_DELETE: 'danger', FILE_DELETE: 'danger', LOGIN_FAIL: 'danger',
  PHONE_REVEAL: 'warn', USER_PW_RESET: 'warn', USER_UPDATE: 'warn', BOARD_MEMBERS: 'warn', WSGROUP_MEMBERS: 'warn', AUDIT_EXPORT: 'warn',
  ITEM_CREATE: 'success', BOARD_CREATE: 'success', WSGROUP_CREATE: 'success', USER_CREATE: 'success',
}
const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const daysAgo = (n) => { const d = new Date(); d.setDate(d.getDate() - n); return iso(d) }
const fmt = (s) => (s ? s.replace('T', ' ').slice(0, 19) : '')

export function AuditLogs() {
  const toast = useToast()
  const [users, setUsers] = useState([])
  const [f, setF] = useState({ from: daysAgo(6), to: iso(new Date()), actorId: '', action: '', q: '' })
  const [applied, setApplied] = useState(f)
  const [page, setPage] = useState(0)
  const [data, setData] = useState(null)
  const [open, setOpen] = useState(null)

  useEffect(() => { api.get('/api/admin/users').then(setUsers).catch(() => {}) }, [])
  const qs = (x, extra = {}) => {
    const p = new URLSearchParams()
    Object.entries({ ...x, ...extra }).forEach(([k, v]) => { if (v !== '' && v != null) p.set(k, v) })
    return p.toString()
  }
  useEffect(() => {
    setData(null)
    api.get(`/api/admin/audit-logs?${qs(applied, { page, size: 50 })}`).then(setData).catch((e) => { toast(e.message, 'error'); setData({ enabled: true, content: [], total: 0 }) })
  }, [applied, page]) // eslint-disable-line

  const search = (e) => { e?.preventDefault(); setPage(0); setApplied({ ...f }) }
  const quick = (n) => { const next = { ...f, from: daysAgo(n - 1), to: iso(new Date()) }; setF(next); setPage(0); setApplied(next) }
  const pages = data ? Math.max(1, Math.ceil(data.total / 50)) : 1

  return (
    <div className="audit">
      <form className="audit-filters" onSubmit={search}>
        <label className="field"><span>기간</span>
          <div className="audit-range">
            <input type="date" className="input input-sm" value={f.from} max={f.to} onChange={(e) => setF({ ...f, from: e.target.value })} aria-label="시작일" />
            <span className="muted">~</span>
            <input type="date" className="input input-sm" value={f.to} min={f.from} onChange={(e) => setF({ ...f, to: e.target.value })} aria-label="종료일" />
          </div>
        </label>
        <label className="field"><span>사용자</span>
          <select className="input input-sm" value={f.actorId} onChange={(e) => setF({ ...f, actorId: e.target.value })}>
            <option value="">전체</option>
            {users.map((u) => <option key={u.id} value={u.id}>{u.name} ({u.username})</option>)}
          </select>
        </label>
        <label className="field"><span>종류</span>
          <select className="input input-sm" value={f.action} onChange={(e) => setF({ ...f, action: e.target.value })}>
            <option value="">전체</option>
            {ACTIONS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
          </select>
        </label>
        <label className="field grow"><span>검색어</span>
          <input className="input input-sm" placeholder="대상·워크스페이스·내용·사용자" value={f.q} onChange={(e) => setF({ ...f, q: e.target.value })} />
        </label>
        <div className="audit-actions">
          <button className="btn btn-primary btn-sm" type="submit"><Icon name="search" size={14} /> 조회</button>
          <a className="btn btn-sm" href={`/api/admin/audit-logs/excel?${qs(applied)}`} title="지금 조회 조건으로 엑셀 내보내기 (최대 20,000건)"><Icon name="download" size={14} /> 엑셀</a>
        </div>
      </form>
      <div className="audit-quick">
        {[[1, '오늘'], [7, '최근 7일'], [30, '최근 30일'], [90, '최근 90일']].map(([n, l]) => <button key={n} type="button" className="chip-btn" onClick={() => quick(n)}>{l}</button>)}
        <span className="muted small" style={{ marginLeft: 'auto' }}>{data?.enabled ? `${(data.total || 0).toLocaleString()}건 · 기록은 수정·삭제할 수 없습니다` : ''}</span>
      </div>

      {data == null ? <Spinner /> : !data.enabled ? (
        <Empty icon="shield" title="감사 로그 테이블이 아직 없습니다">DB에 <code>deploy/postgres-upgrade-3.4.sql</code>을 실행하고 서버를 다시 시작하면 이후 기록부터 여기에 쌓입니다. 그 전까지는 서버 로그 파일(AUDIT)에만 남습니다.</Empty>
      ) : data.content.length === 0 ? (
        <Empty icon="search" title="조건에 맞는 기록이 없습니다">기간이나 조건을 바꿔 보세요.</Empty>
      ) : (
        <div className="table-wrap">
          <table className="table audit-table">
            <thead><tr><th style={{ width: '9.5rem' }}>일시</th><th>사용자</th><th>종류</th><th>워크스페이스</th><th>대상</th><th>내용</th><th>IP</th></tr></thead>
            <tbody>
              {data.content.map((a) => (
                <tr key={a.id} className={open === a.id ? 'open' : ''} onClick={() => setOpen(open === a.id ? null : a.id)}>
                  <td className="muted">{fmt(a.createdAt)}</td>
                  <td>{a.actorName || <span className="muted">-</span>}</td>
                  <td><span className={`tag audit-tag ${TONE[a.action] || ''}`}>{LABEL[a.action] || a.action}</span></td>
                  <td><div className="audit-cell" style={{ maxWidth: '10rem' }}>{a.boardId ? <a href={`#/board/${a.boardId}`} onClick={(e) => e.stopPropagation()}>{a.boardName}</a> : <span className="muted">-</span>}</div></td>
                  <td><div className="audit-cell" style={{ maxWidth: '14rem' }} title={a.targetName || ''}>{a.targetName || <span className="muted">-</span>}</div></td>
                  <td><div className={`audit-cell audit-detail ${open === a.id ? 'open' : ''}`} title={open === a.id ? '' : a.detail || ''}>{a.detail}</div></td>
                  <td className="muted small">{a.clientIp}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data?.enabled && pages > 1 && (
        <nav className="pager" aria-label="페이지">
          <button className={`pager-btn ${page === 0 ? 'disabled' : ''}`} disabled={page === 0} onClick={() => setPage(page - 1)} aria-label="이전"><Icon name="chevronLeft" size={14} /></button>
          <span className="small muted" style={{ padding: '0 .5rem' }}>{page + 1} / {pages}</span>
          <button className={`pager-btn ${page >= pages - 1 ? 'disabled' : ''}`} disabled={page >= pages - 1} onClick={() => setPage(page + 1)} aria-label="다음"><Icon name="chevronRight" size={14} /></button>
        </nav>
      )}
    </div>
  )
}
