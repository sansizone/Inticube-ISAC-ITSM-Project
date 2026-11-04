import React, { useState } from 'react'
import { PageHeader, useApp } from '../App.jsx'
import { api } from '../api.js'
import { AdminUsers } from './AdminUsers.jsx'
import { AuditLogs } from './AuditLogs.jsx'
import { TrashAdmin } from './TrashAdmin.jsx'
import { Modal, useToast, useConfirm, Empty } from './common.jsx'
import { Icon } from './icons.jsx'
import { categoryIcon } from './Sidebar.jsx'

export default function AdminPage({ tab }) {
  const t = ['boards', 'audit', 'trash'].includes(tab) ? tab : 'users'
  const meta = {
    users: ['사용자 관리', 'users', '계정 발급, 권한, 비밀번호 초기화를 관리합니다.'],
    boards: ['게시판 관리', 'doc', '게시판을 추가하고 순서와 글쓰기 권한을 정합니다.'],
    trash: ['휴지통', 'trash', '삭제된 업무를 확인하고 복원하거나 영구 삭제합니다. 삭제자·사유·시각이 함께 표시됩니다.'],
    audit: ['감사 로그', 'shield', '업무·권한·설정 변경과 삭제, 개인정보 열람, 로그인 기록을 조회합니다. 기록은 수정·삭제할 수 없습니다.'],
  }[t]
  return (
    <>
      <PageHeader crumbs={[{ label: '관리자 설정' }]} title={meta[0]} icon={meta[1]} subtitle={meta[2]} />
      <div className="page"><div className="card">
        <div className="card-header page-tabs">
          <a href="#/admin" className={t === 'users' ? 'active' : ''}><Icon name="users" size={14} /> 사용자</a>
          <a href="#/admin/boards" className={t === 'boards' ? 'active' : ''}><Icon name="doc" size={14} /> 게시판</a>
          <a href="#/admin/trash" className={t === 'trash' ? 'active' : ''}><Icon name="trash" size={14} /> 휴지통</a>
          <a href="#/admin/audit" className={t === 'audit' ? 'active' : ''}><Icon name="shield" size={14} /> 감사 로그</a>
        </div>
        <div className="card-body">{t === 'boards' ? <CategoryAdmin /> : t === 'audit' ? <AuditLogs /> : t === 'trash' ? <TrashAdmin /> : <AdminUsers />}</div>
      </div></div>
    </>
  )
}

function CategoryAdmin() {
  const { categories, refreshCategories } = useApp()
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [editing, setEditing] = useState(null)

  const move = async (idx, dir) => {
    const ids = categories.map((c) => c.id)
    const to = idx + dir
    if (to < 0 || to >= ids.length) return
    ;[ids[idx], ids[to]] = [ids[to], ids[idx]]
    try { await api.post('/api/admin/post-categories/reorder', { ids }); refreshCategories() } catch (e) { toast(e.message, 'error') }
  }
  const remove = async (c) => {
    if (!(await confirm(`'${c.name}' 게시판을 삭제할까요?`, { okText: '삭제' }))) return
    try { await api.del(`/api/admin/post-categories/${c.id}`); refreshCategories(); toast('삭제했습니다.') } catch (e) { toast(e.message, 'error') }
  }

  return (
    <div>
      <div className="section-toolbar">
        <p className="hint" style={{ margin: 0 }}>게시판 순서는 사이드바 표시 순서와 같습니다. 글이 있는 게시판은 삭제할 수 없습니다.</p>
        <span className="grow" />
        <button className="btn btn-primary btn-sm" onClick={() => setEditing({})}><Icon name="plus" size={14} /> 게시판 추가</button>
      </div>
      {categories.length === 0 ? <Empty icon="doc" title="게시판이 없습니다" /> : (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>이름</th><th>설명</th><th>글쓰기 권한</th><th>게시글</th><th /></tr></thead>
            <tbody>
              {categories.map((c, i) => (
                <tr key={c.id}>
                  <td><span className="row gap-sm"><Icon name={categoryIcon(c)} size={14} /> <b>{c.name}</b></span></td>
                  <td className="muted">{c.description || '-'}</td>
                  <td>{c.adminOnlyWrite ? <span className="tag tag-accent">관리자만</span> : <span className="muted">모든 사용자</span>}</td>
                  <td className="num">{c.postCount}</td>
                  <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                    <button className="icon-btn icon-btn-sm" onClick={() => move(i, -1)} disabled={i === 0} aria-label="위로">↑</button>
                    <button className="icon-btn icon-btn-sm" onClick={() => move(i, 1)} disabled={i === categories.length - 1} aria-label="아래로">↓</button>
                    <button className="btn btn-sm" onClick={() => setEditing(c)}>수정</button>{' '}
                    <button className="btn btn-sm btn-ghost-danger" onClick={() => remove(c)} disabled={c.postCount > 0} title={c.postCount > 0 ? '게시글이 있어 삭제할 수 없습니다' : ''}>삭제</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && <CategoryForm cat={editing} onClose={() => setEditing(null)} onSaved={() => { refreshCategories(); toast('저장했습니다.', 'success') }} />}
      {confirmDialog}
    </div>
  )
}

function CategoryForm({ cat, onClose, onSaved }) {
  const toast = useToast()
  const [f, setF] = useState({ name: cat.name || '', description: cat.description || '', adminOnlyWrite: !!cat.adminOnlyWrite })
  const [busy, setBusy] = useState(false)
  const submit = async (e) => {
    e.preventDefault(); setBusy(true)
    try {
      if (cat.id) await api.put(`/api/admin/post-categories/${cat.id}`, f)
      else await api.post('/api/admin/post-categories', f)
      onSaved(); onClose()
    } catch (err) { toast(err.message, 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title={cat.id ? '게시판 수정' : '게시판 추가'} onClose={onClose}>
      <form onSubmit={submit}>
        <label className="field"><span>이름</span><input className="input" autoFocus maxLength={50} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required placeholder="예: 기술 공유" /></label>
        <label className="field"><span>설명 <em>선택</em></span><input className="input" maxLength={300} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></label>
        <label className="check"><input type="checkbox" checked={f.adminOnlyWrite} onChange={(e) => setF({ ...f, adminOnlyWrite: e.target.checked })} /> 관리자만 글쓰기 (공지용 — 새 글이 올라오면 전체 알림)</label>
        <div className="form-actions">
          <button type="button" className="btn" onClick={onClose}>취소</button>
          <button className="btn btn-primary" disabled={busy}>저장</button>
        </div>
      </form>
    </Modal>
  )
}
