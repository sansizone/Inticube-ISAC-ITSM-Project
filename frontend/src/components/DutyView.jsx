import React, { useState } from 'react'
import { api } from '../api.js'
import { Avatar, Popover, usePopover, UserPicker, useToast, useConfirm, Empty } from './common.jsx'
import { Icon } from './icons.jsx'
import { sc } from '../util.js'

/**
 * 워크스페이스 담당업무 관리 (트리, 깊이 제한 없음).
 * 소유자·관리자만 편집, 나머지는 조회. 업무 수를 누르면 목록 뷰에서 해당 담당업무(하위 포함)로 필터.
 */
export default function DutyView({ board, index, users, canManage, reload, onFilter }) {
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [collapsed, setCollapsed] = useState(new Set())
  const [adding, setAdding] = useState(null) // { parentId } — 새 항목 입력 행
  const [q, setQ] = useState('')
  const rows = index.flatten(q ? new Set() : collapsed)
  const k = q.trim().toLowerCase()
  const visible = k ? rows.filter((d) => index.path(d.id).toLowerCase().includes(k)) : rows
  const counts = (id) => {
    const scope = index.descendants(id)
    return board.items.filter((i) => scope.has(i.dutyId)).length
  }

  const run = async (fn, ok) => {
    try { await fn(); await reload(); if (ok) toast(ok, 'success') } catch (e) { toast(e.message, 'error') }
  }
  const create = (parentId, name) => run(() => api.post(`/api/boards/${board.id}/duties`, { name, parentId }))
  const update = (d, patch) => run(() => api.put(`/api/duties/${d.id}`, {
    name: d.name, primaryId: d.primary?.id ?? null, secondaryId: d.secondary?.id ?? null, ...patch,
  }))
  const siblings = (d) => index.children.get(d.parentId != null && index.byId.has(d.parentId) ? d.parentId : null) || []
  const move = (d, parentId, position) => run(() => api.post(`/api/duties/${d.id}/move`, { parentId, position }))
  const up = (d) => { const s = siblings(d); const i = s.findIndex((x) => x.id === d.id); if (i > 0) move(d, d.parentId ?? null, i - 1) }
  const down = (d) => { const s = siblings(d); const i = s.findIndex((x) => x.id === d.id); if (i < s.length - 1) move(d, d.parentId ?? null, i + 1) }
  const indent = (d) => {
    const s = siblings(d); const i = s.findIndex((x) => x.id === d.id)
    if (i <= 0) return
    const newParent = s[i - 1]
    setCollapsed((c) => { const n = new Set(c); n.delete(newParent.id); return n })
    move(d, newParent.id, (index.children.get(newParent.id) || []).length)
  }
  const outdent = (d) => {
    if (d.parentId == null) return
    const parent = index.byId.get(d.parentId)
    const ps = siblings(parent); const pi = ps.findIndex((x) => x.id === parent.id)
    move(d, parent.parentId ?? null, pi + 1)
  }
  const remove = async (d) => {
    const sub = index.descendants(d.id).size - 1
    const n = counts(d.id)
    const msg = `'${index.path(d.id)}' 담당업무를 삭제할까요?` +
      (sub ? `\n하위 담당업무 ${sub}개도 함께 삭제됩니다.` : '') +
      (n ? `\n연결된 업무 ${n}개는 '담당업무 없음'이 됩니다(업무는 삭제되지 않음).` : '')
    if (!(await confirm(msg, { okText: '삭제' }))) return
    run(() => api.del(`/api/duties/${d.id}`), '삭제했습니다.')
  }
  const toggle = (id) => setCollapsed((c) => { const n = new Set(c); n.has(id) ? n.delete(id) : n.add(id); return n })

  return (
    <div className="duty-view">
      <div className="duty-intro">
        <div>
          <h3>담당업무</h3>
          <p className="hint" style={{ margin: 0 }}>
            이 워크스페이스의 업무 분류 체계입니다. 업무에 담당업무를 지정하면, 담당자가 비어 있을 때 <b>정 담당자</b>(없으면 상위의 정 담당자)가 자동 지정됩니다.
            {!canManage && ' 구성 변경은 워크스페이스 관리자만 할 수 있습니다.'}
          </p>
        </div>
        <span className="grow" />
        {index.byId.size > 0 && <>
          <div className="filter-search"><Icon name="search" size={13} /><input placeholder="담당업무 검색" value={q} onChange={(e) => setQ(e.target.value)} /></div>
          {!q && <button className="chip-btn" onClick={() => setCollapsed(collapsed.size ? new Set() : new Set(rows.filter((r) => r.hasChildren).map((r) => r.id)))}>
            {collapsed.size ? '모두 펼치기' : '모두 접기'}</button>}
        </>}
        {canManage && <button className="btn btn-primary btn-sm" onClick={() => setAdding({ parentId: null })}><Icon name="plus" size={14} /> 최상위 추가</button>}
      </div>

      {index.byId.size === 0 && !adding ? (
        <Empty icon="tree" title="아직 담당업무가 없습니다">
          {canManage ? <>예: 개발 &gt; 백엔드 &gt; 인증·권한 처럼 단계별로 구성하세요.<br /><button className="btn btn-primary btn-sm" style={{ marginTop: 10 }} onClick={() => setAdding({ parentId: null })}>첫 담당업무 추가</button></>
            : '워크스페이스 관리자가 추가할 수 있습니다.'}
        </Empty>
      ) : (
        <div className="duty-table" role="tree">
          <div className="duty-row duty-head" aria-hidden="true">
            <span className="dc-name">담당업무</span><span className="dc-user">정 담당자</span><span className="dc-user">부 담당자</span><span className="dc-count">업무</span><span className="dc-actions" />
          </div>
          {visible.map((d) => {
            const sib = siblings(d); const si = sib.findIndex((x) => x.id === d.id)
            const total = counts(d.id)
            return (
              <React.Fragment key={d.id}>
                <div className="duty-row" role="treeitem" aria-level={d.depth + 1} aria-expanded={d.hasChildren ? !collapsed.has(d.id) : undefined}>
                  <span className="dc-name" style={{ paddingLeft: sc(k ? 7 : 7 + d.depth * 20) }}>
                    {d.hasChildren && !k
                      ? <button className="icon-btn icon-btn-sm" onClick={() => toggle(d.id)} aria-label={collapsed.has(d.id) ? '펼치기' : '접기'}><Icon name={collapsed.has(d.id) ? 'chevronRight' : 'chevronDown'} size={13} /></button>
                      : <span className="duty-bullet" />}
                    {k ? <span className="ellipsis duty-path">{index.path(d.id)}</span>
                      : <DutyName d={d} canEdit={canManage} onSave={(name) => update(d, { name })} />}
                  </span>
                  <span className="dc-user"><UserCell user={d.primary} users={users} canEdit={canManage} onPick={(id) => update(d, { primaryId: id })} placeholder="정 담당자" /></span>
                  <span className="dc-user"><UserCell user={d.secondary} users={users} canEdit={canManage} onPick={(id) => update(d, { secondaryId: id })} placeholder="부 담당자" /></span>
                  <span className="dc-count">
                    {total > 0 ? <button className="link" onClick={() => onFilter(d.id)} title={`직접 ${d.itemCount}개 · 하위 포함 ${total}개 — 목록에서 보기`}>{total}</button> : <span className="muted">0</span>}
                  </span>
                  <span className="dc-actions">
                    {canManage && <>
                      <button className="icon-btn icon-btn-sm" title="하위 추가" aria-label="하위 추가" onClick={() => { setAdding({ parentId: d.id }); setCollapsed((c) => { const n = new Set(c); n.delete(d.id); return n }) }}><Icon name="plus" size={14} /></button>
                      {!k && <>
                        <button className="icon-btn icon-btn-sm" title="위로" aria-label="위로" disabled={si <= 0} onClick={() => up(d)}><Icon name="arrowUp" size={13} /></button>
                        <button className="icon-btn icon-btn-sm" title="아래로" aria-label="아래로" disabled={si >= sib.length - 1} onClick={() => down(d)}><Icon name="arrowDown" size={13} /></button>
                        <button className="icon-btn icon-btn-sm" title="들여쓰기 (위 항목의 하위로)" aria-label="들여쓰기" disabled={si <= 0} onClick={() => indent(d)}><Icon name="indent" size={13} /></button>
                        <button className="icon-btn icon-btn-sm" title="내어쓰기 (상위 단계로)" aria-label="내어쓰기" disabled={d.parentId == null} onClick={() => outdent(d)}><Icon name="outdent" size={13} /></button>
                      </>}
                      <button className="icon-btn icon-btn-sm" title="삭제" aria-label="삭제" onClick={() => remove(d)}><Icon name="trash" size={13} /></button>
                    </>}
                  </span>
                </div>
                {adding && adding.parentId === d.id && <AddRow depth={k ? 0 : d.depth + 1} onCancel={() => setAdding(null)} onSave={(name) => create(d.id, name)} />}
              </React.Fragment>
            )
          })}
          {adding && adding.parentId == null && <AddRow depth={0} onCancel={() => setAdding(null)} onSave={(name) => create(null, name)} />}
          {k && visible.length === 0 && <div className="menu-empty" style={{ padding: 16 }}>“{q}” 담당업무가 없습니다.</div>}
        </div>
      )}
      {confirmDialog}
    </div>
  )
}

function DutyName({ d, canEdit, onSave }) {
  const [editing, setEditing] = useState(false)
  const [v, setV] = useState(d.name)
  if (!editing) return <span className={`duty-name ${d.depth === 0 ? 'root' : ''}`} onDoubleClick={() => { if (canEdit) { setV(d.name); setEditing(true) } }} title={canEdit ? '더블클릭하여 이름 변경' : ''}>{d.name}</span>
  const commit = () => { setEditing(false); const t = v.trim(); if (t && t !== d.name) onSave(t) }
  return <input className="input input-inline" autoFocus value={v} maxLength={100} onChange={(e) => setV(e.target.value)} onBlur={commit}
    onKeyDown={(e) => { if (e.key === 'Enter') commit(); if (e.key === 'Escape') setEditing(false) }} />
}

function AddRow({ depth, onSave, onCancel }) {
  const [v, setV] = useState('')
  const submit = async () => { const t = v.trim(); if (!t) { onCancel(); return } setV(''); await onSave(t) }
  return (
    <div className="duty-row duty-add">
      <span className="dc-name" style={{ paddingLeft: sc(7 + depth * 20) }}>
        <span className="duty-bullet" />
        <input className="input input-inline" autoFocus placeholder="담당업무 이름 입력 후 Enter (계속 추가 가능, Esc 종료)" value={v} maxLength={100}
          onChange={(e) => setV(e.target.value)} onBlur={() => { if (!v.trim()) onCancel() }}
          onKeyDown={(e) => { if (e.key === 'Enter') submit(); if (e.key === 'Escape') onCancel() }} />
      </span>
    </div>
  )
}

function UserCell({ user, users, canEdit, onPick, placeholder }) {
  const pop = usePopover()
  return (
    <>
      <button className="prop" onClick={canEdit ? pop.open : undefined} style={canEdit ? undefined : { cursor: 'default' }} aria-label={`${placeholder}: ${user ? user.name : '없음'}`}>
        <Avatar user={user} size={18} />
        <span className={user ? '' : 'muted'}>{user ? user.name : (canEdit ? placeholder : '-')}</span>
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={250}>
          <UserPicker users={users} value={user?.id ?? null} onPick={(id) => { pop.close(); onPick(id) }} />
        </Popover>
      )}
    </>
  )
}
