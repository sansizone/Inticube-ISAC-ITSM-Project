import React, { useState, useEffect, useRef, useMemo } from 'react'
import { StatusChip, AssigneeChip, DateChip, PriorityChip, DutyChip, TypeChip, SlaBadge, VersionChip, ProgressCell, HoursCell } from './cells.jsx'
import { fmtHours, itemKey, blockedBy } from '../sla.js'
import { Empty, Popover, usePopover } from './common.jsx'
import { HeaderMenu, AddColumnButton } from './BoardFilters.jsx'
import { SelectBox } from './BulkActions.jsx'
import { boardColumns, columnConfig, builtinLabel, BUILTIN_LABEL, addPlaceholder, itemNoun, josa } from './Fields.jsx'
import { api } from '../api.js'
import { Icon } from './icons.jsx'
import { FieldView, FieldEditor, distinctValues, columnsFor, viewColumns } from './Fields.jsx'
import { isOverdue } from '../util.js'

const PAGE = 100

/**
 * 표 보기: 워크스페이스 컬럼 설정(이름·순서·숨김) + 보기별 숨김을 따라 기본/사용자 정의 컬럼을 한 표에.
 * 상태·담당자·날짜·우선순위·담당업무는 바로 수정, 나머지는 상세 화면에서.
 */
export default function TableView({ board, items, statusById, ops, users, filtering, dutyIndex, subsByParent = {}, hidden = [], header, canManage, onReload, onColumnSettings, selection }) {
  const noun = itemNoun(board)
  /** 헤더 메뉴 '오른쪽에 컬럼 추가': 새 컬럼을 이 컬럼 바로 뒤 순서로 넣고 다시 불러옴 */
  const addRight = async (afterKey, created) => {
    const keys = boardColumns(board).map((c) => c.key).filter((k) => k !== `c:${created.id}`)
    keys.splice(keys.indexOf(afterKey) + 1, 0, `c:${created.id}`)
    try { await api.put(`/api/boards/${board.id}/column-config`, { config: JSON.stringify({ ...columnConfig(board), order: keys }) }) } catch { /* 순서만 실패 → 맨 뒤 */ }
    await onReload?.()
  }
  // 헤더 ⋯ 메뉴에서 컬럼 이름 바꾸기: 기본 컬럼은 워크스페이스 컬럼 설정의 labels, 사용자 정의 컬럼은 컬럼 자체
  const renameCol = async (key, col, name) => {
    const v = name.trim()
    if (!v) return
    if (col) {
      await api.put(`/api/columns/${col.id}`, { name: v, type: col.type, scope: col.scope, visible: col.visible, refBoardId: col.refBoardId, refColumnId: col.refColumnId })
    } else {
      const cfg = columnConfig(board)
      const labels = { ...cfg.labels }
      if (v === BUILTIN_LABEL[key]) delete labels[key]; else labels[key] = v
      await api.put(`/api/boards/${board.id}/column-config`, { config: JSON.stringify({ ...cfg, labels }) })
    }
    await onReload?.()
  }
  const renamer = (key, col) => (canManage ? (name) => renameCol(key, col, name) : null)
  // ---------- 컬럼 너비: 헤더 오른쪽 가장자리를 끌어 조절. 관리자는 워크스페이스 설정에, 그 외는 이 브라우저에 저장 ----------
  const lsKey = `tb.colw.${board.id}`
  const [widths, setWidths] = useState(() => {
    const cfg = columnConfig(board).widths || {}
    try { return { ...cfg, ...(JSON.parse(localStorage.getItem(lsKey) || '{}')) } } catch { return cfg }
  })
  const resizing = useRef(null)
  const startResize = (key, e) => {
    e.preventDefault(); e.stopPropagation()
    const th = e.currentTarget.closest('th')
    resizing.current = { key, x: e.clientX, w: th.getBoundingClientRect().width }
    const move = (ev) => { const r = resizing.current; if (!r) return; setWidths((w) => ({ ...w, [r.key]: Math.max(56, Math.round(r.w + ev.clientX - r.x)) })) }
    const up = () => {
      document.removeEventListener('mousemove', move); document.removeEventListener('mouseup', up)
      const r = resizing.current; resizing.current = null
      if (!r) return
      setWidths((w) => {
        if (canManage) api.put(`/api/boards/${board.id}/column-config`, { config: JSON.stringify({ ...columnConfig(board), widths: w }) }).catch(() => {})
        else { try { localStorage.setItem(lsKey, JSON.stringify(w)) } catch { /* 무시 */ } }
        return w
      })
    }
    document.addEventListener('mousemove', move); document.addEventListener('mouseup', up)
  }
  const resetWidth = (key) => setWidths((w) => { const n = { ...w }; delete n[key]; if (canManage) api.put(`/api/boards/${board.id}/column-config`, { config: JSON.stringify({ ...columnConfig(board), widths: n }) }).catch(() => {}); else { try { localStorage.setItem(lsKey, JSON.stringify(n)) } catch { /* 무시 */ } } return n })
  // 너비를 직접 정하지 않은 컬럼은 헤더 이름이 잘리지 않을 만큼 최소 너비를 준다 (한글 1자 ≈ 0.85rem + 메뉴·여백)
  const wStyle = (key, label) => widths[key] ? { width: widths[key], minWidth: widths[key], maxWidth: widths[key] }
    : label ? { minWidth: `${Math.min(16, Math.max(6, [...label].length * 0.85 + 3))}rem` } : undefined
  const grip = (key) => <span className="th-resize" onMouseDown={(e) => startResize(key, e)} onDoubleClick={() => resetWidth(key)} title="끌어서 너비 조절 · 두 번 눌러 초기화" aria-hidden="true" />
  const [collapsed, setCollapsed] = useState({})
  const [open, setOpen] = useState({})
  const [limit, setLimit] = useState({})
  const cols = viewColumns(board, hidden)
  const subColByName = Object.fromEntries(columnsFor(board, 'SUBITEM').map((c) => [c.name, c]))
  const groupName = Object.fromEntries(board.groups.map((g) => [g.id, g.name]))
  const update = (id) => (p) => ops.updateItem(id, p).catch(() => {})
  const colSpan = 3 + cols.length + (canManage ? 1 : 0)
  // 일괄 선택 (BoardView 가 상태를 가짐)
  const sel = selection
  const allIds = items.map((i) => i.id)
  const selCount = sel ? allIds.filter((id) => sel.has(id)).length : 0
  const checkAll = (ids) => ({ checked: ids.length > 0 && ids.every((id) => sel.has(id)), partial: ids.some((id) => sel.has(id)) })

  // 드롭다운·사람 후보 값은 컬럼별로 한 번만 계산
  const optCache = useMemo(() => new Map(), [board.items]) // eslint-disable-line
  const optionsOf = (col) => {
    if (col.type !== 'DROPDOWN' && col.type !== 'PEOPLE') return []
    if (!optCache.has(col.id)) optCache.set(col.id, distinctValues(board.items, col))
    return optCache.get(col.id)
  }
  const cell = (c, it, sub) => {
    const st = statusById[it.statusId]
    switch (c.key) {
      case 'type': return <TypeChip item={it} types={board.types} onChange={update(it.id)} readOnly={sub} />
      case 'sla': return <SlaBadge item={it} board={board} />
      case 'version': return <VersionChip item={it} versions={board.versions} onChange={update(it.id)} compact />
      case 'progress': return <ProgressCell item={it} onChange={update(it.id)} />
      case 'estimate': return <HoursCell value={it.estimateHours} onChange={(v) => ops.updateItem(it.id, { estimateHours: v }).catch(() => {})} />
      case 'spent': return <span className={it.spentHours == null ? 'muted' : 'num'}>{it.spentHours == null ? '-' : fmtHours(it.spentHours)}</span>
      case 'resolved': return <span className="muted small nowrap">{it.resolvedAt ? it.resolvedAt.slice(0, 16).replace('T', ' ') : ''}</span>
      case 'status': return <StatusChip item={it} statusById={statusById} statuses={board.statuses} onChange={update(it.id)} />
      case 'assignee': return <AssigneeChip item={it} users={users} onChange={update(it.id)} showName />
      case 'due': return <DateChip value={it.dueDate} label={c.label} compact overdue={isOverdue(it, st)} onChange={(v) => ops.updateItem(it.id, { dueDate: v }).catch(() => {})} />
      case 'start': return <DateChip value={it.startDate} label={c.label} compact onChange={(v) => ops.updateItem(it.id, { startDate: v }).catch(() => {})} />
      case 'priority': return <PriorityChip item={it} onChange={update(it.id)} />
      case 'duty': return sub ? null : <DutyChip item={it} index={dutyIndex} onChange={update(it.id)} compact />
      case 'group': return sub ? <span className="small muted">{groupName[it.groupId]}</span> : <GroupCell board={board} value={it.groupId} onChange={(g) => ops.updateItem(it.id, { groupId: g }).catch(() => {})} />
      case 'created': return <span className="muted small nowrap">{it.createdAt ? it.createdAt.slice(0, 10) : ''}</span>
      case 'updated': return <span className="muted small nowrap">{it.updatedAt ? it.updatedAt.slice(0, 16).replace('T', ' ') : ''}</span>
      default: {
        const col = sub ? subColByName[c.col.name] : c.col
        if (!col) return null
        return <FieldEditor compact col={col} itemId={it.id} value={it.values?.[col.id] || ''} users={users} targetName={board.refBoards?.[col.refBoardId]}
          options={optionsOf(col)} onSave={(v) => ops.updateItem(it.id, { values: { [col.id]: v } }).catch(() => {})} />
      }
    }
  }

  const row = (it, sub) => {
    const st = statusById[it.statusId]
    const subs = sub ? [] : (subsByParent[it.id] || [])
    return (
      <tr key={it.id} className={`${sub ? 'trow-sub' : ''} ${st?.done ? 'is-done' : ''} ${sel?.has(it.id) ? 'is-selected' : ''}`}>
        <td className="tcell-num sticky-a muted" title={`#${it.id}`}>
          <span className="tkey">{sel && <SelectBox checked={sel.has(it.id)} label={`${it.name} 선택`} onChange={(on) => sel.set([it.id], on)} />}{itemKey(board, it)}</span>
        </td>
        <td className="tcell-name sticky-b" style={widths.key ? { left: widths.key } : undefined}>
          <div className="tname">
            {sub ? <span className="sub-indent" /> : subs.length > 0
              ? <button className={`sub-toggle ${open[it.id] ? 'on' : ''}`} onClick={() => setOpen({ ...open, [it.id]: !open[it.id] })} title={`하위 ${noun} ${subs.length}개`}>
                  <Icon name={open[it.id] ? 'chevronDown' : 'chevronRight'} size={12} />{subs.length}
                </button>
              : <span className="sub-toggle-space" />}
            {!sub && blockedBy(it, board, statusById).length > 0 && <span className="blocked-mark" title={`미완료 ${noun}에 막혀 있음`}><Icon name="alert" size={12} /></span>}
            <button className="tname-btn" onClick={() => ops.openItem(it.id)} title={it.name}>{it.name}</button>
            {it.commentCount > 0 && <span className="meta-counts"><span><Icon name="chat" size={12} />{it.commentCount}</span></span>}
          </div>
        </td>
        {cols.map((c) => (
          <td key={c.key} className={c.builtin ? `tcell-b-${c.key}` : `tcell-${c.col.type.toLowerCase()}`}
            onDoubleClick={c.builtin ? undefined : () => ops.openItem(it.id)}>{cell(c, it, sub)}</td>
        ))}
        <td className="tcell-act"><RowMenu sub={sub} onOpen={() => ops.openItem(it.id)} onMove={() => ops.moveItem(it.id)} onDelete={ops.canDelete(it.id) ? () => ops.deleteItem(it.id) : null} /></td>
      </tr>
    )
  }

  return (
    <div className={`table-view ${selCount ? 'has-sel' : ''}`}>
      <div className="tv-scroll">
        <table className="tv">
          <thead>
            <tr>
              <th className="sticky-a" style={wStyle('key')}><span className="tkey">{sel && <SelectBox {...checkAll(allIds)} label={`보이는 ${noun} 모두 선택`} onChange={(on) => sel.set(allIds, on)} />}{header ? <HeaderMenu colKey="key" label={builtinLabel(board, 'key')} board={board} users={users} statusById={statusById} {...header} onHide={null} onRename={renamer('key')} /> : builtinLabel(board, 'key')}</span>{grip('key')}</th>
              <th className="sticky-b" style={{ ...wStyle('name'), ...(widths.key ? { left: widths.key } : {}) }}>{header ? <HeaderMenu colKey="name" label={builtinLabel(board, 'name')} board={board} users={users} statusById={statusById} {...header} onHide={null} onRename={renamer('name')} /> : builtinLabel(board, 'name')}{grip('name')}</th>
              {cols.map((c) => (
                <th key={c.key} title={c.label} style={wStyle(c.key, c.label)}>
                  {header ? <HeaderMenu colKey={c.key} label={c.label} col={c.col} board={board} users={users} statusById={statusById} {...header} onHide={() => header.onHide(c.key)} onRename={renamer(c.key, c.builtin ? null : c.col)} onSettings={canManage ? onColumnSettings : null} onAddRight={canManage ? (created) => addRight(c.key, created) : null} /> : c.label}
                  {grip(c.key)}
                </th>
              ))}
              {canManage && <th className="th-add"><AddColumnButton board={board} onAdded={onReload} onSettings={onColumnSettings} /></th>}
              <th className="tcell-act" />
            </tr>
          </thead>
          {board.groups.map((g) => {
            const list = items.filter((i) => i.groupId === g.id)
            if (filtering && list.length === 0) return null
            const shown = collapsed[g.id] ? [] : list.slice(0, limit[g.id] || PAGE)
            return (
              <tbody key={g.id}>
                <tr className="tv-group">
                  <td colSpan={colSpan}>
                    {sel && list.length > 0 && <SelectBox {...checkAll(list.map((i) => i.id))} label={`${g.name} 그룹 모두 선택`} onChange={(on) => sel.set(list.map((i) => i.id), on)} />}
                    <button className="tv-group-btn" onClick={() => setCollapsed({ ...collapsed, [g.id]: !collapsed[g.id] })}>
                      <Icon name={collapsed[g.id] ? 'chevronRight' : 'chevronDown'} size={14} />
                      <span className="group-dot" style={{ background: g.color }} /> <b>{g.name}</b> <span className="muted">{list.length}</span>
                    </button>
                  </td>
                </tr>
                {shown.map((it) => (
                  <React.Fragment key={it.id}>
                    {row(it, false)}
                    {open[it.id] && (subsByParent[it.id] || []).map((sb) => row(sb, true))}
                  </React.Fragment>
                ))}
                {!collapsed[g.id] && list.length > shown.length && (
                  <tr><td colSpan={colSpan}><button className="more-rows" onClick={() => setLimit({ ...limit, [g.id]: (limit[g.id] || PAGE) + PAGE * 2 })}>{list.length - shown.length}개 더 보기</button></td></tr>
                )}
                {!filtering && !collapsed[g.id] && (
                  <tr className="tv-add"><td className="sticky-a" /><td className="sticky-b" colSpan={1} style={widths.key ? { left: widths.key } : undefined}><AddRow placeholder={addPlaceholder(board)} onAdd={(name) => ops.addItem(g.id, name)} /></td><td colSpan={colSpan - 2} /></tr>
                )}
              </tbody>
            )
          })}
        </table>
      </div>
      {filtering && items.length === 0 && <Empty icon="filter" title={`조건에 맞는 ${noun}${josa(noun, '이/가')} 없습니다`} />}
    </div>
  )
}

function AddRow({ onAdd, placeholder }) {
  const [v, setV] = useState('')
  return (
    <div className="tv-add-row">
      <Icon name="plus" size={13} className="muted" />
      <input placeholder={placeholder} value={v} maxLength={300} onChange={(e) => setV(e.target.value)} aria-label={placeholder}
        onKeyDown={async (e) => { if (e.key === 'Enter' && v.trim()) { const name = v.trim(); setV(''); await onAdd(name) } if (e.key === 'Escape') setV('') }} />
    </div>
  )
}

export function RowMenu({ sub, onOpen, onMove, onDelete }) {
  const pop = usePopover()
  return (
    <>
      <button className="icon-btn icon-btn-sm row-more" onClick={pop.open} aria-label="더보기"><Icon name="more" size={14} /></button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={210} align="right">
          <div className="menu">
            <button className="menu-row" onClick={() => { pop.close(); onOpen() }}><Icon name="eye" size={14} /> 열기</button>
            {!sub && onMove && <button className="menu-row" onClick={() => { pop.close(); onMove() }}><Icon name="move" size={14} /> 다른 워크스페이스로 이동</button>}
            {onDelete
              ? <button className="menu-row danger" onClick={() => { pop.close(); onDelete() }}><Icon name="trash" size={14} /> 삭제</button>
              : <div className="menu-row muted" title="등록한 사람이나 관리자만 삭제할 수 있습니다"><Icon name="lock" size={14} /> 삭제 권한 없음</div>}
          </div>
        </Popover>
      )}
    </>
  )
}

/** 표의 그룹 칸: 눌러서 다른 그룹으로 이동 */
function GroupCell({ board, value, onChange }) {
  const pop = usePopover()
  const g = board.groups.find((x) => x.id === value)
  return (
    <>
      <button type="button" className="cell-edit" onClick={pop.open} title="눌러서 그룹 변경">
        {g ? <span className="small row gap-xs"><span className="group-dot" style={{ background: g.color }} />{g.name}</span> : <span className="cell-empty" />}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={220}>
          <div className="menu">
            {board.groups.map((x) => (
              <button key={x.id} className={`menu-row ${x.id === value ? 'active' : ''}`} onClick={() => { pop.close(); if (x.id !== value) onChange(x.id) }}>
                <span className="group-dot" style={{ background: x.color }} /> <span className="grow ellipsis">{x.name}</span>
              </button>
            ))}
          </div>
        </Popover>
      )}
    </>
  )
}
