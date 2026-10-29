import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useApp } from '../App.jsx'
import { Avatar, Modal, useToast, useConfirm } from './common.jsx'
import { Icon } from './icons.jsx'

/**
 * 워크스페이스 그룹 (그룹 > 워크스페이스)
 * - 그룹 멤버만 그 그룹의 워크스페이스를 볼 수 있다. 그룹 관리자는 그룹 안 워크스페이스 전체를 보고 관리
 * - 그룹 생성·삭제·순서는 시스템 관리자, 이름·멤버는 그룹 관리자도
 */
export const GROUP_ROLE = { ADMIN: '그룹 관리자', MEMBER: '멤버' }

/** 그룹 설정: 이름·설명 + 멤버 관리 (+ 시스템 관리자는 삭제) */
export function GroupSettingsModal({ group, onClose }) {
  const { users, me, refreshBoards, refreshGroups } = useApp()
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [name, setName] = useState(group?.name || '')
  const [description, setDescription] = useState(group?.description || '')
  const [list, setList] = useState(group ? null : [])
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState(false)
  const isNew = !group
  useEffect(() => {
    if (group) api.get(`/api/ws-groups/${group.id}/members`).then(setList).catch((e) => toast(e.message, 'error'))
  }, [group?.id]) // eslint-disable-line

  const ids = new Set((list || []).map((m) => m.userId))
  const candidates = users.filter((u) => !ids.has(u.id) && (!q || u.name.includes(q) || u.username.includes(q) || (u.department || '').includes(q)))
  const addAll = () => setList([...(list || []), ...candidates.map((u) => ({ userId: u.id, username: u.username, name: u.name, department: u.department, active: true, role: 'MEMBER' }))])

  const save = async () => {
    setBusy(true)
    try {
      let id = group?.id
      if (isNew) id = (await api.post('/api/ws-groups', { name, description })).id
      else await api.put(`/api/ws-groups/${id}`, { name, description })
      const members = list.map((m) => ({ userId: m.userId, role: m.role }))
      if (isNew && !members.some((m) => m.userId === me.id)) members.push({ userId: me.id, role: 'ADMIN' })
      await api.put(`/api/ws-groups/${id}/members`, members)
      toast(isNew ? `'${name}' 그룹을 만들었습니다.` : '그룹 설정을 저장했습니다.', 'success')
      refreshGroups(); refreshBoards(); onClose()
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }
  const remove = async () => {
    if (!(await confirm(`'${group.name}' 그룹을 삭제할까요? 워크스페이스가 남아 있으면 삭제할 수 없습니다.`, { okText: '삭제', danger: true }))) return
    try { await api.del(`/api/ws-groups/${group.id}`); toast('그룹을 삭제했습니다.'); refreshGroups(); refreshBoards(); onClose() }
    catch (e) { toast(e.message, 'error') }
  }

  return (
    <Modal title={isNew ? '새 워크스페이스 그룹' : `워크스페이스 그룹 설정 · ${group.name}`} onClose={onClose} width={760}
      footer={<>
        {!isNew && me.role === 'ADMIN' && <button className="btn btn-danger-ghost" style={{ marginRight: 'auto' }} onClick={remove}>그룹 삭제</button>}
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn-primary" disabled={busy || !name.trim() || !list} onClick={save}>{isNew ? '만들기' : '저장'}</button>
      </>}>
      <div className="form-grid">
        <label className="field"><span>그룹 이름</span>
          <input className="input" autoFocus={isNew} maxLength={100} value={name} onChange={(e) => setName(e.target.value)} placeholder="예: 인티큐브서비스관리" /></label>
        <label className="field"><span>설명 <em>선택</em></span>
          <input className="input" maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="이 그룹에서 관리하는 업무" /></label>
      </div>
      <div className="field"><span>멤버 <small className="muted">그룹 멤버만 이 그룹의 워크스페이스를 볼 수 있습니다. 그룹 관리자는 그룹 안 워크스페이스 전체와 멤버를 관리합니다.</small></span></div>
      {!list ? <div className="muted">불러오는 중…</div> : (
        <div className="ws-members">
          <div className="ws-member-list">
            {list.map((m) => (
              <div key={m.userId} className={`ws-member ${m.active ? '' : 'inactive'}`}>
                <Avatar user={{ id: m.userId, name: m.name }} size={24} />
                <span className="grow ellipsis"><b>{m.name}</b> <span className="muted small">{m.username}{m.department ? ` · ${m.department}` : ''}{m.active ? '' : ' · 비활성'}</span></span>
                <select className="input input-sm" value={m.role} aria-label="역할" onChange={(e) => setList(list.map((x) => (x.userId === m.userId ? { ...x, role: e.target.value } : x)))}>
                  <option value="ADMIN">그룹 관리자</option><option value="MEMBER">멤버</option>
                </select>
                <button className="icon-btn icon-btn-sm" aria-label="빼기" disabled={m.userId === me.id && me.role !== 'ADMIN'}
                  onClick={() => setList(list.filter((x) => x.userId !== m.userId))}><Icon name="x" size={14} /></button>
              </div>
            ))}
            {list.length === 0 && <div className="menu-empty">멤버가 없습니다. 오른쪽에서 추가하세요.</div>}
          </div>
          <div className="ws-add">
            <div className="filter-search" style={{ width: '100%' }}>
              <Icon name="search" size={13} />
              <input placeholder="추가할 사용자 검색 (이름·아이디·부서)" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }} />
            </div>
            <div className="ws-candidates">
              {candidates.slice(0, 50).map((u) => (
                <button key={u.id} className="menu-row" onClick={() => setList([...list, { userId: u.id, username: u.username, name: u.name, department: u.department, active: true, role: 'MEMBER' }])}>
                  <Avatar user={u} size={20} /><span className="grow ellipsis">{u.name} <span className="muted small">{u.username}{u.department ? ` · ${u.department}` : ''}</span></span><Icon name="plus" size={13} />
                </button>
              ))}
              {candidates.length === 0 && <div className="menu-empty">추가할 사용자가 없습니다.</div>}
            </div>
            {candidates.length > 1 && <button className="link small" onClick={addAll}>{q ? `검색된 ${candidates.length}명 모두 추가` : `나머지 ${candidates.length}명 모두 추가`}</button>}
          </div>
        </div>
      )}
      <p className="hint" style={{ marginTop: 8 }}>멤버 {list ? list.length : 0}명 · 저장을 눌러야 반영됩니다.</p>
      {confirmDialog}
    </Modal>
  )
}

/** 워크스페이스 그룹 선택 (새 워크스페이스·설정에서) */
export function GroupSelect({ value, onChange, disabled, onlyAdmin }) {
  const { wsGroups } = useApp()
  const list = onlyAdmin ? wsGroups.filter((g) => g.myRole === 'ADMIN') : wsGroups
  return (
    <select className="input" value={value || ''} disabled={disabled} onChange={(e) => onChange(Number(e.target.value) || null)}>
      {list.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
      {value && !list.some((g) => g.id === value) && <option value={value}>(현재 그룹)</option>}
    </select>
  )
}
