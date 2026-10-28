import React, { useState } from 'react'
import { StatusChip, AssigneeChip, PriorityChip, DateChip, InlineText, StatusBattery, DutyChip, TypeChip, SlaBadge } from './cells.jsx'
import { itemKey, blockedBy } from '../sla.js'
import { Popover, usePopover, Empty } from './common.jsx'
import { Icon } from './icons.jsx'
import { PALETTE, isOverdue } from '../util.js'
import { viewColumns } from './Fields.jsx'
import { RowMenu } from './TableView.jsx'

const LIST_KEYS = ['sla', 'duty', 'status', 'start', 'due', 'assignee']

/** 목록 뷰: 그룹별 섹션, 행 오른쪽에 속성 칩 (Linear 스타일) */
export default function ListView({ board, items, statusById, ops, users, filtering, dutyIndex, onClearFilter, sorted, subsByParent = {}, hidden = [] }) {
  // 워크스페이스 컬럼 설정 순서·이름·숨김 + 보기별 숨김 (목록에는 기본 컬럼만 칩으로 표시)
  const vcols = viewColumns(board, hidden)
  const metaCols = vcols.filter((c) => LIST_KEYS.includes(c.key))
  const showPriority = vcols.some((c) => c.key === 'priority')
  const showType = vcols.some((c) => c.key === 'type')
  // 각 속성은 고정 너비 칸(lm-slot)에 넣어 행마다 상태·담당자·날짜가 같은 세로선에 맞도록 한다
  const chip = (c, it, st, sub) => <span key={c.key} className={`lm-slot lm-${c.key}`}>{chipInner(c, it, st, sub)}</span>
  const chipInner = (c, it, st, sub) => {
    switch (c.key) {
      case 'sla': return <SlaBadge item={it} board={board} compact />
      case 'duty': return sub ? null : <DutyChip item={it} index={dutyIndex} onChange={update(it.id)} compact />
      case 'status': return <StatusChip item={it} statusById={statusById} statuses={board.statuses} onChange={update(it.id)} />
      case 'start': return <DateChip value={it.startDate} label={c.label} compact onChange={(v) => ops.updateItem(it.id, { startDate: v }).catch(() => {})} />
      case 'due': return <DateChip value={it.dueDate} label={c.label} compact overdue={isOverdue(it, st)} onChange={(v) => ops.updateItem(it.id, { dueDate: v }).catch(() => {})} />
      case 'assignee': return <AssigneeChip item={it} users={users} onChange={update(it.id)} />
      default: return null
    }
  }
  const [collapsed, setCollapsed] = useState({})
  const [drag, setDrag] = useState(null)
  const [over, setOver] = useState(null)
  const [newGroup, setNewGroup] = useState(null)
  const [open, setOpen] = useState({})
  const [limit, setLimit] = useState({})
  const allSorted = board.items.filter((i) => !i.parentId).sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)

  const drop = (groupId, beforeId) => {
    if (!drag) return
    const ids = allSorted.filter((i) => i.groupId === groupId && i.id !== drag.id).map((i) => i.id)
    const at = beforeId ? ids.indexOf(beforeId) : -1
    if (at >= 0) ids.splice(at, 0, drag.id); else ids.push(drag.id)
    ops.reorder(groupId, ids)
    setDrag(null); setOver(null)
  }
  const update = (id) => (p) => ops.updateItem(id, p).catch(() => {})

  return (
    <div className="list-view">
      {board.groups.map((g, gi) => {
        const groupItems = items.filter((i) => i.groupId === g.id)
        if (filtering && groupItems.length === 0) return null
        const isCollapsed = collapsed[g.id]
        return (
          <section key={g.id} className={`lgroup ${over?.groupId === g.id && drag ? 'drag-target' : ''}`}
            onDragOver={(e) => { if (drag) { e.preventDefault(); if (!over || over.groupId !== g.id) setOver({ groupId: g.id, beforeId: null }) } }}
            onDrop={(e) => { e.preventDefault(); drop(g.id, over?.groupId === g.id ? over.beforeId : null) }}>
            <div className="lgroup-head">
              <button className="icon-btn icon-btn-sm" onClick={() => setCollapsed({ ...collapsed, [g.id]: !isCollapsed })} aria-label={isCollapsed ? '펼치기' : '접기'}>
                <Icon name={isCollapsed ? 'chevronRight' : 'chevronDown'} size={14} />
              </button>
              <span className="group-dot" style={{ background: g.color }} />
              <InlineText value={g.name} className="lgroup-name" maxLength={100} onSave={(name) => ops.updateGroup(g.id, { name })} />
              <span className="lgroup-count">{groupItems.length}</span>
              <div className="lgroup-battery"><StatusBattery items={groupItems} statuses={board.statuses} height={4} /></div>
              <span className="grow" />
              <GroupMenu group={g} ops={ops} first={gi === 0} last={gi === board.groups.length - 1} />
              <button className="icon-btn icon-btn-sm" title="이 그룹에 업무 추가" aria-label="업무 추가"
                onClick={async () => { const it = await ops.addItem(g.id, '새 업무'); if (it) ops.openItem(it.id) }}><Icon name="plus" size={14} /></button>
            </div>
            {!isCollapsed && (
              <div className="lrows">
                {groupItems.slice(0, limit[g.id] || PAGE).map((it) => {
                  const st = statusById[it.statusId]
                  const subs = subsByParent[it.id] || []
                  return (
                    <React.Fragment key={it.id}>
                    <div
                      className={`lrow ${drag?.id === it.id ? 'dragging' : ''} ${over?.beforeId === it.id ? 'drop-before' : ''} ${st?.done ? 'is-done' : ''}`}
                      onDragOver={(e) => { if (drag) { e.preventDefault(); e.stopPropagation(); setOver({ groupId: g.id, beforeId: it.id }) } }}
                      onDrop={(e) => { e.preventDefault(); e.stopPropagation(); drop(g.id, it.id) }}>
                      <span className={`grip ${sorted ? 'disabled' : ''}`} draggable={!sorted}
                        onDragStart={(e) => { e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(it.id)); setDrag({ id: it.id }) }}
                        onDragEnd={() => { setDrag(null); setOver(null) }} title={sorted ? '정렬 중에는 끌어서 이동할 수 없습니다' : '끌어서 이동'} aria-label="끌어서 이동"><Icon name="grip" size={14} /></span>
                      {showPriority && <PriorityChip item={it} onChange={update(it.id)} variant="icon" />}
                      <span className="lrow-id" title={`#${it.id}`}>{itemKey(board, it)}</span>
                      {showType && <TypeChip item={it} types={board.types} onChange={update(it.id)} variant="icon" />}
                      {subs.length > 0
                        ? <button className={`sub-toggle ${open[it.id] ? 'on' : ''}`} onClick={() => setOpen({ ...open, [it.id]: !open[it.id] })} title={`하위 업무 ${subs.length}개`} aria-label="하위 업무 펼치기">
                            <Icon name={open[it.id] ? 'chevronDown' : 'chevronRight'} size={12} />{subs.length}
                          </button>
                        : <span className="sub-toggle-space" />}
                      <StatusChip item={it} statusById={statusById} statuses={board.statuses} onChange={update(it.id)} variant="icon" />
                      {blockedBy(it, board, statusById).length > 0 && <span className="blocked-mark" title="미완료 업무에 막혀 있음"><Icon name="alert" size={12} /></span>}
                      <button className="lrow-name" onClick={() => ops.openItem(it.id)}>{it.name}</button>
                      <span className="lrow-meta">
                        {(it.commentCount > 0 || it.fileCount > 0) && (
                          <span className="meta-counts">
                            {it.commentCount > 0 && <span><Icon name="chat" size={12} />{it.commentCount}</span>}
                            {it.fileCount > 0 && <span><Icon name="clip" size={12} />{it.fileCount}</span>}
                          </span>
                        )}
                        {metaCols.map((c) => chip(c, it, st, false))}
                        <RowMenu onDelete={() => ops.deleteItem(it.id)} onOpen={() => ops.openItem(it.id)} onMove={() => ops.moveItem(it.id)} />
                      </span>
                    </div>
                    {open[it.id] && subs.map((sb) => {
                      const sst = statusById[sb.statusId]
                      return (
                        <div key={sb.id} className={`lrow lrow-sub ${sst?.done ? 'is-done' : ''}`}>
                          <span className="sub-indent" />
                          <StatusChip item={sb} statusById={statusById} statuses={board.statuses} onChange={update(sb.id)} variant="icon" />
                          <button className="lrow-name" onClick={() => ops.openItem(sb.id)}>{sb.name}</button>
                          <span className="lrow-meta">
                            {sb.commentCount > 0 && <span className="meta-counts"><span><Icon name="chat" size={12} />{sb.commentCount}</span></span>}
                            {metaCols.map((c) => chip(c, sb, sst, true))}
                            <RowMenu sub onDelete={() => ops.deleteItem(sb.id)} onOpen={() => ops.openItem(sb.id)} />
                          </span>
                        </div>
                      )
                    })}
                    {open[it.id] && <AddSubRow onAdd={(name) => ops.addItem(g.id, name, undefined, it.id)} />}
                    </React.Fragment>
                  )
                })}
                {groupItems.length > (limit[g.id] || PAGE) && (
                  <button className="more-rows" onClick={() => setLimit({ ...limit, [g.id]: (limit[g.id] || PAGE) + PAGE * 2 })}>
                    {groupItems.length - (limit[g.id] || PAGE)}개 더 보기
                  </button>
                )}
                {drag && over?.groupId === g.id && over.beforeId == null && <div className="drop-end" />}
                {!filtering && <AddItemRow onAdd={(name) => ops.addItem(g.id, name)} />}
              </div>
            )}
          </section>
        )
      })}
      {filtering && items.length === 0 && <Empty icon="filter" title="조건에 맞는 업무가 없습니다" />}
      {filtering && (
        <div className="filter-note">
          <Icon name="filter" size={14} /> 필터 적용 중에는 업무·그룹 추가가 숨겨집니다.
          {onClearFilter && <button className="link" onClick={onClearFilter}>필터 해제</button>}
        </div>
      )}
      {!filtering && (
        newGroup === null
          ? <button className="add-group-btn" onClick={() => setNewGroup('')}><Icon name="plus" size={14} /> 그룹 추가</button>
          : (
            <form className="add-group" onSubmit={(e) => { e.preventDefault(); if (newGroup.trim()) ops.addGroup(newGroup.trim()); setNewGroup(null) }}>
              <input className="input input-sm" autoFocus placeholder="그룹 이름 (예: 3차 스프린트)" value={newGroup} maxLength={100}
                onChange={(e) => setNewGroup(e.target.value)} onBlur={() => { if (!newGroup.trim()) setNewGroup(null) }}
                onKeyDown={(e) => { if (e.key === 'Escape') setNewGroup(null) }} />
              <button className="btn btn-sm btn-primary">추가</button>
            </form>
          )
      )}
    </div>
  )
}

const PAGE = 100

function AddSubRow({ onAdd }) {
  const [v, setV] = useState('')
  return (
    <div className="lrow lrow-add lrow-sub">
      <span className="sub-indent" />
      <Icon name="plus" size={13} className="muted" />
      <input placeholder="하위 업무 추가 후 Enter" value={v} maxLength={300} onChange={(e) => setV(e.target.value)} aria-label="하위 업무 추가"
        onKeyDown={async (e) => { if (e.key === 'Enter' && v.trim()) { const name = v.trim(); setV(''); await onAdd(name) } if (e.key === 'Escape') setV('') }} />
    </div>
  )
}

function AddItemRow({ onAdd }) {
  const [v, setV] = useState('')
  return (
    <div className="lrow lrow-add">
      <Icon name="plus" size={14} className="muted" />
      <input placeholder="업무 추가 후 Enter" value={v} maxLength={300} onChange={(e) => setV(e.target.value)} aria-label="업무 추가"
        onKeyDown={async (e) => { if (e.key === 'Enter' && v.trim()) { const name = v.trim(); setV(''); await onAdd(name) } if (e.key === 'Escape') setV('') }} />
    </div>
  )
}

function GroupMenu({ group, ops, first, last }) {
  const pop = usePopover()
  return (
    <>
      <button className="icon-btn icon-btn-sm" onClick={pop.open} aria-label="그룹 메뉴"><Icon name="more" size={14} /></button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={210} align="right">
          <div className="menu">
            <div className="menu-label">색상</div>
            <div className="swatches">
              {PALETTE.map((c) => <button key={c} className={`swatch ${c === group.color ? 'active' : ''}`} style={{ background: c }} aria-label={c}
                onClick={() => { ops.updateGroup(group.id, { color: c }); pop.close() }} />)}
            </div>
            <div className="menu-sep" />
            {!first && <button className="menu-row" onClick={() => { ops.moveGroup(group.id, -1); pop.close() }}>위로 이동</button>}
            {!last && <button className="menu-row" onClick={() => { ops.moveGroup(group.id, 1); pop.close() }}>아래로 이동</button>}
            <button className="menu-row danger" onClick={() => { pop.close(); ops.deleteGroup(group.id) }}><Icon name="trash" size={14} /> 그룹 삭제</button>
          </div>
        </Popover>
      )}
    </>
  )
}
