import { itemNoun, josa, itemTitle } from './Fields.jsx'
import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useApp } from '../App.jsx'
import { Avatar, Modal, useToast } from './common.jsx'
import { Icon } from './icons.jsx'

/** 워크스페이스 공개 범위 선택 */
export function VisibilityPicker({ value, onChange, disabled }) {
  const { wsGroups } = useApp()
  const grouped = wsGroups?.length > 0
  return (
    <div className="vis-picker">
      <button type="button" disabled={disabled} className={`vis-opt ${!value ? 'active' : ''}`} onClick={() => onChange(false)}>
        <Icon name="lock" size={15} /><span><b>멤버만</b><em>추가한 멤버와 관리자만 볼 수 있음</em></span>
      </button>
      <button type="button" disabled={disabled} className={`vis-opt ${value ? 'active' : ''}`} onClick={() => onChange(true)}>
        <Icon name="globe" size={15} /><span><b>{grouped ? '그룹 전체' : '모든 사용자'}</b><em>{grouped ? '이 워크스페이스 그룹 멤버 누구나 보고 쓸 수 있음' : '로그인한 사람 누구나 보고 쓸 수 있음'}</em></span>
      </button>
    </div>
  )
}

/** 워크스페이스 멤버·공개 범위 편집 (설정 › 멤버 탭) */
export function MembersEditor({ board, onSaved }) {
  const { users: allUsers, me, refreshBoards, boards, wsGroups } = useApp()
  // 워크스페이스 그룹이 있으면 그 그룹 멤버 중에서만 추가 (그룹 밖 사용자는 추가해도 볼 수 없음)
  const groupId = boards.find((b) => b.id === board.id)?.groupId
  const [groupUsers, setGroupUsers] = useState(null)
  useEffect(() => {
    if (!groupId || !wsGroups.length) { setGroupUsers(null); return }
    api.get(`/api/ws-groups/${groupId}/members`).then((l) => setGroupUsers(new Set(l.map((m) => m.userId)))).catch(() => setGroupUsers(null))
  }, [groupId, wsGroups.length])
  const users = groupUsers ? allUsers.filter((u) => groupUsers.has(u.id)) : allUsers
  const toast = useToast()
  const [list, setList] = useState(null)
  const [isPublic, setPublic] = useState(board.isPublic)
  const [q, setQ] = useState('')
  const [dirty, setDirty] = useState(false)
  const can = board.canManage
  useEffect(() => { api.get(`/api/boards/${board.id}/members`).then(setList).catch((e) => toast(e.message, 'error')) }, [board.id]) // eslint-disable-line
  if (!list) return <div className="muted">불러오는 중…</div>
  const ids = new Set(list.map((m) => m.userId))
  const candidates = users.filter((u) => !ids.has(u.id) && (!q || u.name.includes(q) || u.username.includes(q) || (u.department || '').includes(q)))
  const set = (next) => { setList(next); setDirty(true) }
  const save = async () => {
    try {
      const r = await api.put(`/api/boards/${board.id}/members`, { isPublic, members: list.map((m) => ({ userId: m.userId, role: m.role })) })
      setList(r); setDirty(false); toast('멤버·공개 범위를 저장했습니다.', 'success'); refreshBoards(); onSaved?.()
    } catch (e) { toast(e.message, 'error') }
  }
  return (
    <div className="ws-members-wrap">
      <div className="field"><span>공개 범위</span><VisibilityPicker value={isPublic} disabled={!can} onChange={(v) => { setPublic(v); setDirty(true) }} /></div>
      <div className="ws-members">
        <div className="ws-member-list">
          {list.map((m) => (
            <div key={m.userId} className={`ws-member ${m.active ? '' : 'inactive'}`}>
              <Avatar user={{ id: m.userId, name: m.name }} size={24} />
              <span className="grow ellipsis"><b>{m.name}</b> <span className="muted small">{m.username}{m.department ? ` · ${m.department}` : ''}{m.active ? '' : ' · 비활성'}</span></span>
              <select className="input input-sm" disabled={!can} value={m.role} onChange={(e) => set(list.map((x) => (x.userId === m.userId ? { ...x, role: e.target.value } : x)))}>
                <option value="OWNER">관리자</option><option value="MEMBER">멤버</option>
              </select>
              {can && <button className="icon-btn icon-btn-sm" aria-label="빼기" disabled={m.userId === me.id && me.role !== 'ADMIN'}
                onClick={() => set(list.filter((x) => x.userId !== m.userId))}><Icon name="x" size={14} /></button>}
            </div>
          ))}
          {list.length === 0 && <div className="menu-empty">멤버가 없습니다.</div>}
        </div>
        {can && (
          <div className="ws-add">
            <div className="filter-search" style={{ width: '100%' }}>
              <Icon name="search" size={13} />
              <input placeholder="추가할 사용자 검색 (이름·아이디·부서)" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }} />
            </div>
            <div className="ws-candidates">
              {candidates.slice(0, 50).map((u) => (
                <button key={u.id} className="menu-row" onClick={() => set([...list, { userId: u.id, username: u.username, name: u.name, department: u.department, active: true, role: 'MEMBER' }])}>
                  <Avatar user={u} size={20} /><span className="grow ellipsis">{u.name} <span className="muted small">{u.username}</span></span><Icon name="plus" size={13} />
                </button>
              ))}
              {candidates.length === 0 && <div className="menu-empty">추가할 사용자가 없습니다.</div>}
            </div>
          </div>
        )}
      </div>
      <div className="form-actions">
        <span className="hint grow" style={{ margin: 0 }}>
          멤버 {list.length}명 · 관리자는 설정·컬럼·멤버를 바꿀 수 있습니다. {isPublic ? '모든 사용자 공개 상태라 멤버가 아니어도 볼 수 있습니다.' : ''}
        </span>
        {can && <button className="btn btn-primary btn-sm" disabled={!dirty} onClick={save}>저장</button>}
      </div>
    </div>
  )
}

/** 업무를 다른 워크스페이스로 이동 */
export function MoveItemModal({ item, board, onClose, onMoved }) {
  const noun = itemNoun(board)
  const { boards } = useApp()
  const toast = useToast()
  const targets = boards.filter((b) => b.id !== board.id)
  const [boardId, setBoardId] = useState(targets[0]?.id || null)
  const [target, setTarget] = useState(null)
  const [groupId, setGroupId] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    setTarget(null); setGroupId('')
    if (boardId) api.get(`/api/boards/${boardId}`).then(setTarget).catch((e) => toast(e.message, 'error'))
  }, [boardId]) // eslint-disable-line

  const key = (s) => (s || '').trim().toLowerCase()
  let keep = [], lose = []
  if (target) {
    const tcols = new Set(target.columns.map((c) => key(c.name)))
    const used = board.columns.filter((c) => c.scope === 'ITEM' && item.values?.[c.id])
    keep = used.filter((c) => tcols.has(key(c.name))).map((c) => c.name)
    lose = used.filter((c) => !tcols.has(key(c.name))).map((c) => c.name)
  }
  const st = board.statuses.find((s) => s.id === item.statusId)
  const stOk = !st || (target && target.statuses.some((s) => key(s.label) === key(st.label)))

  const submit = async () => {
    setBusy(true)
    try {
      await api.post(`/api/items/${item.id}/move`, { boardId, groupId: groupId ? Number(groupId) : null })
      toast(`'${target.name}' 워크스페이스로 옮겼습니다.`, 'success')
      onMoved?.(boardId)
      onClose()
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title="다른 워크스페이스로 이동" onClose={onClose} width={520}
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn-primary" disabled={busy || !target} onClick={submit}>이동</button>
      </>}>
      <p className="move-name"><b>{item.name}</b></p>
      {targets.length === 0 ? <p className="muted">옮길 수 있는 다른 워크스페이스가 없습니다.</p> : <>
        <label className="field"><span>워크스페이스</span>
          <select className="input" value={boardId || ''} onChange={(e) => setBoardId(Number(e.target.value))}>
            {targets.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select></label>
        <label className="field"><span>그룹</span>
          <select className="input" value={groupId} onChange={(e) => setGroupId(e.target.value)} disabled={!target}>
            <option value="">같은 이름 그룹 (없으면 첫 그룹)</option>
            {target?.groups.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select></label>
        {target && (
          <div className="move-summary">
            <div><Icon name="check" size={13} /> 댓글·첨부파일·하위 {noun}{josa(noun, '은/는')} 함께 이동합니다.</div>
            {st && <div className={stOk ? '' : 'warn'}><Icon name={stOk ? 'check' : 'x'} size={13} /> 상태 '{st.label}' {stOk ? '유지' : '— 대상에 없어 비워집니다'}</div>}
            {keep.length > 0 && <div><Icon name="check" size={13} /> 값 유지: {keep.join(', ')}</div>}
            {lose.length > 0 && <div className="warn"><Icon name="x" size={13} /> 대상에 같은 이름 컬럼이 없어 빠지는 값: {lose.join(', ')}</div>}
          </div>
        )}
      </>}
    </Modal>
  )
}
