import React, { useState } from 'react'
import { api } from '../api.js'
import { navigate } from '../App.jsx'
import { Popover, usePopover, useToast, useConfirm } from './common.jsx'
import { Icon } from './icons.jsx'
import { boardColumns } from './Fields.jsx'

export const VIEW_TYPES = [
  { type: 'list', label: '목록', icon: 'list' },
  { type: 'table', label: '표', icon: 'sheet' },
  { type: 'kanban', label: '칸반', icon: 'kanban' },
  { type: 'timeline', label: '타임라인', icon: 'timeline' },
  { type: 'roadmap', label: '로드맵', icon: 'map' },
  { type: 'dashboard', label: '인사이트', icon: 'chart' },
  { type: 'wiki', label: '위키', icon: 'book' },
]
export const VIEW_META = Object.fromEntries(VIEW_TYPES.map((v) => [v.type, v]))

/** 보기 설정 JSON → { filters, sort, hidden } */
export function parseViewConfig(v, emptyFilters) {
  let c = null
  try { c = v?.config ? JSON.parse(v.config) : null } catch { c = null }
  return {
    filters: { ...emptyFilters, ...(c?.filters || {}) },
    sort: c?.sort || { key: 'manual', dir: 'asc' },
    hidden: c?.hidden || [],
  }
}

/** 보기 탭 막대: 탭 이동, 이름 변경·복제·순서·삭제, 새 보기 추가 */
export function ViewTabs({ board, active, setBoard, currentConfig }) {
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [renaming, setRenaming] = useState(null)
  const add = usePopover()
  const menu = usePopover()
  const views = board.views

  const setViews = (fn) => setBoard((b) => ({ ...b, views: fn(b.views) }))
  const create = async (type, name, config) => {
    const base = name || VIEW_META[type].label
    let n = base, k = 2
    while (views.some((v) => v.name === n)) n = `${base} ${k++}`
    try {
      const v = await api.post(`/api/boards/${board.id}/views`, { name: n, type, config: config || null })
      setViews((vs) => [...vs, v])
      navigate(`/board/${board.id}/${v.id}`)
    } catch (e) { toast(e.message, 'error') }
  }
  const rename = async (v, name) => {
    setRenaming(null)
    if (!name.trim() || name.trim() === v.name) return
    try { const s = await api.put(`/api/views/${v.id}`, { name: name.trim() }); setViews((vs) => vs.map((x) => (x.id === v.id ? s : x))) } catch (e) { toast(e.message, 'error') }
  }
  const move = async (v, dir) => {
    const list = [...views]
    const i = list.findIndex((x) => x.id === v.id), j = i + dir
    if (j < 0 || j >= list.length) return
    ;[list[i], list[j]] = [list[j], list[i]]
    setViews(() => list)
    try { await api.post(`/api/boards/${board.id}/views/reorder`, { ids: list.map((x) => x.id) }) } catch (e) { toast(e.message, 'error') }
  }
  const remove = async (v) => {
    if (!(await confirm(`'${v.name}' 보기를 삭제할까요?\n업무 데이터는 그대로 남습니다.`, { okText: '삭제' }))) return
    try {
      await api.del(`/api/views/${v.id}`)
      const rest = views.filter((x) => x.id !== v.id)
      setViews(() => rest)
      if (active?.id === v.id) navigate(`/board/${board.id}/${rest[0]?.id || ''}`)
    } catch (e) { toast(e.message, 'error') }
  }
  const idx = active ? views.findIndex((v) => v.id === active.id) : -1

  return (
    <div className="view-tabs" role="tablist">
      {views.map((v) => {
        const meta = VIEW_META[v.type] || VIEW_TYPES[0]
        const on = active?.id === v.id
        if (renaming === v.id) {
          return (
            <input key={v.id} className="input input-sm view-rename" autoFocus defaultValue={v.name} maxLength={50}
              onBlur={(e) => rename(v, e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); if (e.key === 'Escape') setRenaming(null) }} />
          )
        }
        return (
          <span key={v.id} className={`view-tab-wrap ${on ? 'active' : ''}`}>
            <a role="tab" aria-selected={on} className={`view-tab ${on ? 'active' : ''}`} href={`#/board/${board.id}/${v.id}`}
              onDoubleClick={(e) => { e.preventDefault(); setRenaming(v.id) }} title={`${meta.label} 보기 · 두 번 눌러 이름 변경`}>
              <Icon name={meta.icon} size={14} /> {v.name}
            </a>
            {on && <button className="view-tab-more" aria-label="보기 메뉴" onClick={menu.open}><Icon name="chevronDown" size={12} /></button>}
          </span>
        )
      })}
      <button className="view-tab view-tab-add" onClick={add.open} title="보기 추가" aria-label="보기 추가"><Icon name="plus" size={14} /></button>
      {add.anchor && (
        <Popover anchor={add.anchor} onClose={add.close} width={230}>
          <div className="menu">
            <div className="menu-title">새 보기 탭</div>
            {VIEW_TYPES.map((t) => (
              <button key={t.type} className="menu-row" onClick={() => { add.close(); create(t.type) }}><Icon name={t.icon} size={14} /> {t.label}</button>
            ))}
            {active && currentConfig && <>
              <div className="menu-sep" />
              <button className="menu-row" onClick={() => { add.close(); create(active.type, `${active.name} 사본`, JSON.stringify(currentConfig)) }}>
                <Icon name="save" size={14} /> 지금 필터·정렬로 새 보기
              </button>
            </>}
          </div>
        </Popover>
      )}
      {menu.anchor && active && (
        <Popover anchor={menu.anchor} onClose={menu.close} width={200}>
          <div className="menu">
            <button className="menu-row" onClick={() => { menu.close(); setRenaming(active.id) }}><Icon name="edit" size={14} /> 이름 변경</button>
            <button className="menu-row" onClick={() => { menu.close(); create(active.type, `${active.name} 사본`, active.config) }}><Icon name="plus" size={14} /> 복제</button>
            {idx > 0 && <button className="menu-row" onClick={() => { menu.close(); move(active, -1) }}><Icon name="chevronLeft" size={14} /> 왼쪽으로</button>}
            {idx < views.length - 1 && <button className="menu-row" onClick={() => { menu.close(); move(active, 1) }}><Icon name="chevronRight" size={14} /> 오른쪽으로</button>}
            {views.length > 1 && <><div className="menu-sep" /><button className="menu-row danger" onClick={() => { menu.close(); remove(active) }}><Icon name="trash" size={14} /> 보기 삭제</button></>}
          </div>
        </Popover>
      )}
      {confirmDialog}
    </div>
  )
}

/** 이 보기에서 보일 컬럼 고르기 (워크스페이스에서 숨긴 컬럼은 제외) */
export function ColumnPicker({ board, hidden, onChange, onManage, keys }) {
  // (보기 유형별 사용 가능한 컬럼만)
  const pop = usePopover()
  const cols = boardColumns(board).filter((c) => !c.hidden && (!keys || keys.includes(c.key)))
  const shown = cols.filter((c) => !hidden.includes(c.key)).length
  const toggle = (k) => onChange(hidden.includes(k) ? hidden.filter((x) => x !== k) : [...hidden, k])
  return (
    <>
      <button className={`chip-btn ${hidden.length ? 'on' : ''}`} onClick={pop.open} title="이 보기에 표시할 컬럼">
        <Icon name="columns" size={13} /> 컬럼 {shown < cols.length ? `${shown}/${cols.length}` : ''}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={250} align="right">
          <div className="menu">
            <div className="menu-title">이 보기에 표시할 컬럼</div>
            <div style={{ maxHeight: '22rem', overflowY: 'auto' }}>
              {cols.map((c) => (
                <label key={c.key} className="menu-row check-row">
                  <input type="checkbox" checked={!hidden.includes(c.key)} onChange={() => toggle(c.key)} />
                  <span className="grow ellipsis">{c.label}</span>
                  {!c.builtin && <span className="menu-meta">사용자</span>}
                </label>
              ))}
            </div>
            <div className="menu-sep" />
            {hidden.length > 0 && <button className="menu-row" onClick={() => onChange([])}>모두 표시</button>}
            {onManage && <button className="menu-row" onClick={() => { pop.close(); onManage() }}><Icon name="settings" size={14} /> 컬럼 이름·순서·추가…</button>}
          </div>
        </Popover>
      )}
    </>
  )
}
