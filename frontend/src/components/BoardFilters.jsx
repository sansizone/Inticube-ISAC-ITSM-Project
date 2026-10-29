import React, { useEffect, useRef, useState } from 'react'
import { useApp, navigate } from '../App.jsx'
import { Avatar, Popover, usePopover, UserPicker, StatusPicker, DutyPicker } from './common.jsx'
import { BoardMark } from './Sidebar.jsx'
import { Icon, StatusIcon } from './icons.jsx'
import { columnsFor, distinctValues, splitPeople, TYPE_LABEL, COLUMN_TYPES, relIds, relTokens, refLabel, builtinLabel, itemNoun } from './Fields.jsx'
import { api } from '../api.js'
import { RelationTarget } from './BoardSettings.jsx'
import { slaOf, itemKey, blockedBy } from '../sla.js'

export const EMPTY_FILTERS = { q: '', assignee: '', status: '', duty: '', groups: [], priority: '', due: '', fields: {}, type: '', version: '', sla: '', watch: '' }

export const SORTS = [
  { key: 'manual', label: '직접 정한 순서' },
  { key: 'name', label: '업무명' },
  { key: 'due', label: '마감일' },
  { key: 'start', label: '시작일' },
  { key: 'priority', label: '우선순위' },
  { key: 'status', label: '상태' },
  { key: 'assignee', label: '담당자' },
  { key: 'updated', label: '최근 수정' },
  { key: 'id', label: '등록 순서(번호)' },
  { key: 'progress', label: '진척률' },
  { key: 'version', label: '버전' },
]
export const SLAS = [['breach', 'SLA 초과'], ['warn', '4시간 내 마감'], ['open', 'SLA 진행 중'], ['met', 'SLA 준수 완료'], ['none', 'SLA 없음']]
export const SlaFilter = (p) => <SimpleFilter icon="clock" label="SLA" allLabel="모든 SLA" options={SLAS} {...p} />
export function TypeFilter({ value, onChange, types }) {
  return <SimpleFilter icon="tag" label="유형" allLabel="모든 유형" options={[...types.map((t) => [String(t.id), t.name]), ['none', '유형 없음']]} value={value} onChange={onChange} />
}
export function VersionFilter({ value, onChange, versions }) {
  return <SimpleFilter icon="flag" label="버전" allLabel="모든 버전" options={[...versions.map((v) => [String(v.id), v.name]), ['none', '버전 없음']]} value={value} onChange={onChange} />
}
export const PRIORITIES = [['CRITICAL', '긴급'], ['HIGH', '높음'], ['MEDIUM', '보통'], ['LOW', '낮음'], ['none', '우선순위 없음']]
const PRIORITY_RANK = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 }
export const DUES = [['overdue', '지연됨'], ['today', '오늘 마감'], ['week', '이번 주 마감'], ['next7', '7일 이내 마감'], ['none', '마감일 없음']]

export function isFiltering(f) {
  return !!(f.q || f.assignee || f.status || f.groups.length || f.priority || f.due || Object.keys(f.fields || {}).length || f.version || f.watch)
}

const ymd = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

/** 필터 + 정렬 적용 (상위 업무만 대상, 하위 업무는 상위 업무를 따라 표시) */
export function applyFilters(board, f, sort, { me, statusById, dutyIndex, watching }) {
  const typeById = Object.fromEntries((board.types || []).map((t) => [t.id, t]))
  const versionById = Object.fromEntries((board.versions || []).map((v) => [v.id, v]))
  const slaState = (i) => { const s = slaOf(i, typeById[i.typeId], board); return s ? (s.done ? s.state : s.state === 'met' ? 'ok' : s.state) : 'none' }
  const fieldFilters = Object.entries(f.fields || {}).filter(([, vs]) => vs && vs.length)
  const colById = Object.fromEntries((board.columns || []).map((c) => [String(c.id), c]))
  const dutyScope = null // 담당업무는 화면에서 제거됨 — 예전에 저장된 보기의 담당업무 필터는 무시
  const now = new Date()
  const today = ymd(now)
  const in7 = ymd(new Date(now.getFullYear(), now.getMonth(), now.getDate() + 7))
  const weekEnd = ymd(new Date(now.getFullYear(), now.getMonth(), now.getDate() + ((7 - now.getDay()) % 7)))
  const q = f.q.trim().toLowerCase()
  const groupSet = f.groups.length ? new Set(f.groups) : null
  const out = board.items.filter((i) => {
    if (i.parentId) return false
    if (groupSet && !groupSet.has(i.groupId)) return false
    if (dutyScope && !dutyScope.has(i.dutyId)) return false
    // 유형 필터는 화면에서 제거됨 — 예전에 저장된 보기의 유형 값은 무시
    if (f.version === 'none' ? i.versionId != null : f.version && i.versionId !== Number(f.version)) return false
    if (f.watch === 'me' && !(watching && watching.has(i.id))) return false
    if (f.watch === 'blocked' && blockedBy(i, board, statusById).length === 0) return false
    if (false) { // SLA 는 화면에서 제거됨 — 예전에 저장된 보기의 SLA 필터는 무시
      const st = slaState(i)
      if (f.sla === 'open' ? !['ok', 'warn'].includes(st) : f.sla === 'met' ? st !== 'met' : st !== f.sla) return false
    }
    if (q && !i.name.toLowerCase().includes(q) && String(i.id) !== q.replace(/^#/, '') && itemKey(board, i).toLowerCase() !== q
      && !Object.entries(i.values || {}).some(([cid, v]) => v && (colById[cid]?.type === 'RELATION'
        ? relTokens(v).some((id) => refLabel(cid, id).toLowerCase().includes(q)) : v.toLowerCase().includes(q)))) return false
    for (const [cid, vs] of fieldFilters) {
      const v = i.values?.[cid] || ''
      const col = colById[cid]
      const have = col?.type === 'PEOPLE' ? splitPeople(v) : col?.type === 'RELATION' ? relTokens(v) : [v]
      if (!vs.some((x) => (x === '' ? v === '' : have.includes(x)))) return false
    }
    if (f.assignee === 'me' && i.assignee?.id !== me.id) return false
    if (f.assignee === 'none' && i.assignee) return false
    if (f.assignee && !['me', 'none'].includes(f.assignee) && i.assignee?.id !== Number(f.assignee)) return false
    if (f.status === 'none' && i.statusId != null) return false
    if (f.status === 'open' && statusById[i.statusId]?.done) return false
    if (f.status && !['none', 'open'].includes(f.status) && i.statusId !== Number(f.status)) return false
    if (f.priority === 'none' && i.priority) return false
    if (f.priority && f.priority !== 'none' && i.priority !== f.priority) return false
    if (f.due) {
      const done = statusById[i.statusId]?.done
      if (f.due === 'none') { if (i.dueDate) return false }
      else if (!i.dueDate) return false
      else if (f.due === 'overdue' && (done || i.dueDate >= today)) return false
      else if (f.due === 'today' && i.dueDate !== today) return false
      else if (f.due === 'week' && (i.dueDate < today || i.dueDate > weekEnd)) return false
      else if (f.due === 'next7' && (i.dueDate < today || i.dueDate > in7)) return false
    }
    return true
  })
  const manual = (a, b) => a.sortOrder - b.sortOrder || a.id - b.id
  if (sort.key === 'manual') return out.sort(manual)
  const dir = sort.dir === 'desc' ? -1 : 1
  const val = {
    name: (i) => i.name.toLowerCase(),
    due: (i) => i.dueDate,
    start: (i) => i.startDate,
    priority: (i) => PRIORITY_RANK[i.priority],
    status: (i) => statusById[i.statusId]?.sortOrder,
    assignee: (i) => i.assignee?.name,
    updated: (i) => i.updatedAt,
    id: (i) => i.id,
    created: (i) => i.createdAt,
    type: (i) => typeById[i.typeId]?.sortOrder,
    sla: (i) => { const s = slaOf(i, typeById[i.typeId], board); return s && !s.done && s.ms != null ? s.ms : null },
    progress: (i) => i.progress,
    version: (i) => versionById[i.versionId]?.sortOrder,
  }[sort.key] || (sort.key.startsWith('c:') ? colValue(colById[sort.key.slice(2)]) : () => null)
  return out.sort((a, b) => {
    const x = val(a), y = val(b)
    if (x == null && y == null) return manual(a, b)
    if (x == null) return 1           // 값 없는 업무는 항상 뒤로
    if (y == null) return -1
    const c = typeof x === 'number' ? x - y : String(x).localeCompare(String(y), 'ko')
    return c * dir || manual(a, b)
  })
}

function colValue(col) {
  if (!col) return () => null
  return (i) => {
    const v = i.values?.[col.id]
    if (v == null || v === '') return null
    if (col.type === 'NUMBER') { const n = Number(v); return Number.isNaN(n) ? v : n }
    if (col.type === 'RELATION') return relTokens(v).map((id) => refLabel(col.id, id)).join(', ').toLowerCase()
    return v.toLowerCase()
  }
}

/** 사용자 정의 컬럼 값으로 거르기 (드롭다운·사람·텍스트·숫자·날짜) */
export function FieldFilter({ board, value, onChange }) {
  const pop = usePopover()
  const [colId, setColId] = useState(null)
  const [q, setQ] = useState('')
  const cols = columnsFor(board, 'ITEM').filter((c) => c.type !== 'LONG_TEXT')
  const active = Object.entries(value).filter(([, vs]) => vs?.length)
  const top = board.items.filter((i) => !i.parentId)
  const col = cols.find((c) => c.id === colId)
  const sel = new Set(col ? value[col.id] || [] : [])
  const set = (cid, vs) => { const n = { ...value }; if (vs.length) n[cid] = vs; else delete n[cid]; onChange(n) }
  if (!cols.length) return null
  const label = active.length === 1 ? `${cols.find((c) => String(c.id) === active[0][0])?.name}: ${active[0][1].length === 1 ? (active[0][1][0] || '비어 있음') : `${active[0][1].length}개`}`
    : active.length ? `필드 ${active.length}개` : '필드'
  return (
    <>
      <button className={`chip-btn ${active.length ? 'on' : ''}`} onClick={(e) => { setColId(null); setQ(''); pop.open(e) }}>
        <Icon name="sheet" size={13} /> <span className="ellipsis" style={{ maxWidth: '12rem' }}>{label}</span>
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={300}>
          {!col ? (
            <div className="menu">
              <div className="menu-title">거를 컬럼</div>
              <div style={{ maxHeight: '22rem', overflowY: 'auto' }}>
                {cols.map((c) => (
                  <button key={c.id} className="menu-row" onClick={() => { setColId(c.id); setQ('') }}>
                    <span className="grow ellipsis">{c.name}</span>
                    {value[c.id]?.length ? <span className="tag tag-accent">{value[c.id].length}</span> : <span className="menu-meta">{TYPE_LABEL[c.type]}</span>}
                  </button>
                ))}
              </div>
              {active.length > 0 && <><div className="menu-sep" /><button className="menu-row" onClick={() => { onChange({}); pop.close() }}>필드 필터 모두 해제</button></>}
            </div>
          ) : (
            <div className="menu">
              <button className="menu-row" onClick={() => setColId(null)}><Icon name="chevronLeft" size={14} /> <b className="grow ellipsis">{col.name}</b></button>
              <div className="filter-search" style={{ margin: '4px 2px 6px' }}>
                <Icon name="search" size={13} />
                <input autoFocus placeholder="값 찾기" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }} />
              </div>
              <div style={{ maxHeight: '20rem', overflowY: 'auto' }}>
                <label className="menu-row check-row">
                  <input type="checkbox" checked={sel.has('')} onChange={() => set(col.id, sel.has('') ? [...sel].filter((x) => x !== '') : [...sel, ''])} />
                  <span className="grow muted">비어 있음</span>
                </label>
                {distinctValues(top, col).map(([v, n]) => [v, n, col.type === 'RELATION' ? refLabel(col.id, v) : v])
                  .filter(([, , t]) => !q || t.toLowerCase().includes(q.toLowerCase())).slice(0, 300).map(([v, n, t]) => (
                  <label key={v} className="menu-row check-row">
                    <input type="checkbox" checked={sel.has(v)} onChange={() => set(col.id, sel.has(v) ? [...sel].filter((x) => x !== v) : [...sel, v])} />
                    <span className="grow ellipsis" title={t}>{t}</span><span className="menu-meta">{n}</span>
                  </label>
                ))}
              </div>
              {sel.size > 0 && <><div className="menu-sep" /><button className="menu-row" onClick={() => set(col.id, [])}>이 컬럼 필터 해제</button></>}
            </div>
          )}
        </Popover>
      )}
    </>
  )
}

/** 아이콘만 있다가 누르면 펼쳐지는 검색창 */
export function SearchToggle({ value, onChange, board }) {
  const noun = board ? itemNoun(board) : '업무', nameLabel = board ? builtinLabel(board, 'name') : '업무명'
  const [open, setOpen] = useState(!!value)
  const ref = useRef(null)
  useEffect(() => { if (open) ref.current?.focus() }, [open])
  useEffect(() => { if (value) setOpen(true) }, [value])
  if (!open) {
    return <button className="chip-btn icon-only" onClick={() => setOpen(true)} title={`${noun} 검색`} aria-label={`${noun} 검색`}><Icon name="search" size={14} /></button>
  }
  return (
    <div className="filter-search">
      <Icon name="search" size={13} />
      <input ref={ref} placeholder={`${nameLabel}, 키(${board?.keyPrefix || 'REQ'}-12) 또는 #번호`} value={value} onChange={(e) => onChange(e.target.value)} aria-label={`${noun} 검색`}
        onBlur={() => { if (!value) setOpen(false) }}
        onKeyDown={(e) => { if (e.key === 'Escape') { onChange(''); setOpen(false) } }} />
      {value && <button className="icon-btn icon-btn-xs" onMouseDown={(e) => e.preventDefault()} onClick={() => { onChange(''); ref.current?.focus() }} aria-label="지우기"><Icon name="x" size={12} /></button>}
    </div>
  )
}

export function GroupFilter({ value, onChange, groups }) {
  const pop = usePopover()
  const sel = new Set(value)
  const label = value.length === 0 ? '그룹' : value.length === 1 ? (groups.find((g) => g.id === value[0])?.name || '그룹') : `그룹 ${value.length}개`
  const toggle = (id) => onChange(sel.has(id) ? value.filter((x) => x !== id) : [...value, id])
  return (
    <>
      <button className={`chip-btn ${value.length ? 'on' : ''}`} onClick={pop.open}><Icon name="list" size={13} /> <span className="ellipsis" style={{ maxWidth: '9rem' }}>{label}</span></button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={260}>
          <div className="menu">
            <button className="menu-row" onClick={() => onChange([])}><Icon name="check" size={14} style={{ visibility: value.length ? 'hidden' : 'visible' }} /> 모든 그룹</button>
            <div className="menu-sep" />
            {groups.map((g) => (
              <label key={g.id} className="menu-row check-row">
                <input type="checkbox" checked={sel.has(g.id)} onChange={() => toggle(g.id)} />
                <span className="group-dot" style={{ background: g.color }} />
                <span className="grow ellipsis">{g.name}</span>
              </label>
            ))}
          </div>
        </Popover>
      )}
    </>
  )
}

function SimpleFilter({ icon, label, value, options, onChange, allLabel }) {
  const pop = usePopover()
  const cur = options.find(([k]) => k === value)
  return (
    <>
      <button className={`chip-btn ${value ? 'on' : ''}`} onClick={pop.open}><Icon name={icon} size={13} /> {cur ? cur[1] : label}</button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={200}>
          <div className="menu">
            <button className="menu-row" onClick={() => { onChange(''); pop.close() }}>{allLabel}</button>
            <div className="menu-sep" />
            {options.map(([k, l]) => (
              <button key={k} className={`menu-row ${value === k ? 'active' : ''}`} onClick={() => { onChange(k); pop.close() }}>
                <span className="grow">{l}</span>{value === k && <Icon name="check" size={14} />}
              </button>
            ))}
          </div>
        </Popover>
      )}
    </>
  )
}

export const PriorityFilter = (p) => <SimpleFilter icon="chart" label="우선순위" allLabel="모든 우선순위" options={PRIORITIES} {...p} />
export const DueFilter = (p) => <SimpleFilter icon="calendar" label="마감일" allLabel="모든 마감일" options={DUES} {...p} />

export function SortMenu({ sort, onChange, board }) {
  const pop = usePopover()
  const colSorts = board ? columnsFor(board, 'ITEM').filter((c) => c.type !== 'LONG_TEXT').map((c) => ({ key: `c:${c.id}`, label: c.name })) : []
  const BL = ['name', 'status', 'assignee', 'due', 'start', 'priority', 'created', 'type', 'version', 'progress']
  const all = [...SORTS, { key: 'created', label: '등록일' }, ...colSorts].map((s) => (board && BL.includes(s.key) ? { ...s, label: builtinLabel(board, s.key) } : s))
  const cur = all.find((s) => s.key === sort.key) || SORTS[0]
  const on = sort.key !== 'manual'
  return (
    <>
      <button className={`chip-btn ${on ? 'on' : ''}`} onClick={pop.open} title="정렬">
        <Icon name={on && sort.dir === 'desc' ? 'arrowDown' : 'arrowUp'} size={13} /> {on ? `정렬: ${cur.label}` : '정렬'}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={220} align="right">
          <div className="menu">
            <div style={{ maxHeight: '22rem', overflowY: 'auto' }}>
            {all.map((s, idx) => (
              <React.Fragment key={s.key}>
              {idx === SORTS.length + 1 && <div className="menu-title">컬럼</div>}
              <button className={`menu-row ${sort.key === s.key ? 'active' : ''}`} onClick={() => onChange({ key: s.key, dir: s.key === sort.key ? sort.dir : ['updated', 'created'].includes(s.key) ? 'desc' : 'asc' })}>
                <span className="grow ellipsis">{s.label}</span>{sort.key === s.key && <Icon name="check" size={14} />}
              </button>
              </React.Fragment>
            ))}
            </div>
            {on && <>
              <div className="menu-sep" />
              <div className="seg" role="group" aria-label="정렬 방향">
                <button className={sort.dir !== 'desc' ? 'active' : ''} onClick={() => onChange({ ...sort, dir: 'asc' })}><Icon name="arrowUp" size={12} /> {sort.key === 'priority' ? '높은 순' : '오름차순'}</button>
                <button className={sort.dir === 'desc' ? 'active' : ''} onClick={() => onChange({ ...sort, dir: 'desc' })}><Icon name="arrowDown" size={12} /> {sort.key === 'priority' ? '낮은 순' : '내림차순'}</button>
              </div>
              <div className="menu-empty" style={{ padding: '6px 10px 2px' }}>정렬 중에는 끌어서 순서 바꾸기가 꺼집니다.</div>
            </>}
          </div>
        </Popover>
      )}
    </>
  )
}

export function DutyFilter({ value, onChange, index }) {
  const pop = usePopover()
  const d = value && value !== 'none' ? index.byId.get(Number(value)) : null
  return (
    <>
      <button className={`chip-btn ${value ? 'on' : ''}`} onClick={pop.open} title={d ? `${index.path(d.id)} (하위 포함)` : ''}>
        <Icon name="tree" size={13} /> {value === 'none' ? '담당업무 없음' : d ? d.name : '담당업무'}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={300}>
          <button className="menu-row" onClick={() => { onChange(''); pop.close() }} style={{ margin: '4px 4px 0', width: 'calc(100% - 8px)' }}>모든 담당업무</button>
          <DutyPicker index={index} value={value === 'none' ? null : Number(value) || undefined}
            onPick={(id) => { onChange(id == null ? 'none' : String(id)); pop.close() }} />
        </Popover>
      )}
    </>
  )
}

export function StatusFilter({ value, onChange, statuses }) {
  const pop = usePopover()
  const s = statuses.find((x) => x.id === Number(value))
  return (
    <>
      <button className={`chip-btn ${value ? 'on' : ''}`} onClick={pop.open}>
        {s ? <StatusIcon status={s} statuses={statuses} size={12} /> : <Icon name="filter" size={13} />}
        {value === 'none' ? '상태 없음' : value === 'open' ? '완료 제외' : s ? s.label : '상태'}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={220}>
          <div className="menu" style={{ paddingBottom: 0 }}>
            <button className="menu-row" onClick={() => { onChange(''); pop.close() }}>모든 상태</button>
            <button className="menu-row" onClick={() => { onChange('open'); pop.close() }}>완료 제외</button>
          </div>
          <div className="menu-sep" />
          <StatusPicker statuses={statuses} value={value === 'none' ? null : Number(value) || undefined}
            onPick={(id) => { onChange(id == null ? 'none' : String(id)); pop.close() }} />
        </Popover>
      )}
    </>
  )
}

export function PersonFilter({ value, onChange, users, me, noun = '업무' }) {
  const pop = usePopover()
  const label = value === 'me' ? `내 ${noun}` : value === 'none' ? '담당자 없음' : value ? users.find((u) => u.id === Number(value))?.name : '모든 담당자'
  return (
    <>
      <button className={`chip-btn ${value ? 'on' : ''}`} onClick={pop.open}>
        <Icon size={13} name="user" /> {value ? label : '담당자'}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={240}>
          <div className="menu" style={{ paddingBottom: 0 }}>
            <button className="menu-row" onClick={() => { onChange(''); pop.close() }}>모든 담당자</button>
            <button className="menu-row" onClick={() => { onChange('me'); pop.close() }}><Avatar user={me} size={18} /> 내 {noun}만</button>
          </div>
          <div className="menu-sep" />
          <UserPicker users={users} value={value && !['me', 'none'].includes(value) ? Number(value) : undefined}
            onPick={(id) => { onChange(id == null ? 'none' : String(id)); pop.close() }} />
        </Popover>
      )}
    </>
  )
}

/** 워크스페이스 이름 옆 ▾ — 다른 워크스페이스로 이동 */
export function BoardSwitcher({ board }) {
  const { boards } = useApp()
  const pop = usePopover()
  const [q, setQ] = useState('')
  const list = boards.filter((b) => !q || b.name.toLowerCase().includes(q.toLowerCase()))
  return (
    <>
      <button className="board-switch" onClick={(e) => { setQ(''); pop.open(e) }} title="다른 워크스페이스로 이동">
        <span className="ellipsis">{board.name}</span>
        {!board.isPublic && <Icon name="lock" size={14} />}
        <Icon name="chevronDown" size={16} />
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={300}>
          <div className="menu">
            {boards.length > 6 && (
              <div className="filter-search" style={{ margin: '2px 2px 6px' }}>
                <Icon name="search" size={13} />
                <input autoFocus placeholder="워크스페이스 찾기" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }} />
              </div>
            )}
            <div style={{ maxHeight: '22rem', overflowY: 'auto' }}>
              {list.map((b) => (
                <button key={b.id} className={`menu-row ${b.id === board.id ? 'active' : ''}`}
                  onClick={() => { pop.close(); if (b.id !== board.id) navigate(`/board/${b.id}`) }}>
                  <BoardMark board={b} size={16} />
                  <span className="grow ellipsis">{b.name}</span>
                  {!b.isPublic && <Icon name="lock" size={12} />}
                  {b.id === board.id ? <Icon name="check" size={14} /> : <span className="menu-meta">{b.itemCount}건</span>}
                </button>
              ))}
              {list.length === 0 && <div className="menu-empty">일치하는 워크스페이스가 없습니다.</div>}
            </div>
          </div>
        </Popover>
      )}
    </>
  )
}


/** 필터 키 → 라벨/아이콘 (툴바 '＋ 필터' 메뉴, 컬럼 헤더 메뉴에서 공용) */
export const FILTER_META = {
  assignee: { label: '담당자', icon: 'user' }, status: { label: '상태', icon: 'filter' }, type: { label: '유형', icon: 'tag' },
  due: { label: '마감일', icon: 'calendar' }, sla: { label: 'SLA', icon: 'clock' }, priority: { label: '우선순위', icon: 'chart' },
  version: { label: '버전', icon: 'flag' }, duty: { label: '담당업무', icon: 'tree' }, groups: { label: '그룹', icon: 'folder' },
  fields: { label: '사용자 정의 필드', icon: 'sheet' }, watch: { label: '감시 중', icon: 'eye' }, blocked: { label: '차단됨', icon: 'alert' },
}

/** 필터 하나의 값 비우기 */
export function clearOne(f, key) {
  if (key === 'blocked' || key === 'watch') return { ...f, watch: '' }
  if (key === 'groups') return { ...f, groups: [] }
  if (key === 'fields') return { ...f, fields: {} }
  return { ...f, [key]: '' }
}

/** 필터 값이 있는지 */
export function hasValue(f, key) {
  if (key === 'blocked') return f.watch === 'blocked'
  if (key === 'watch') return f.watch === 'me'
  if (key === 'groups') return f.groups.length > 0
  if (key === 'fields') return Object.values(f.fields || {}).some((v) => v?.length)
  return !!f[key]
}

/** '＋ 필터' — 자주 안 쓰는 조건을 골라 툴바에 꺼내는 메뉴 */
export function MoreFilters({ available, shown, onPick }) {
  const pop = usePopover()
  const rest = available.filter((k) => !shown.includes(k))
  if (!rest.length) return null
  return (
    <>
      <button className="chip-btn chip-more" onClick={pop.open} title="필터 더 보기"><Icon name="plus" size={13} /> 필터</button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={210}>
          <div className="menu">
            <div className="menu-title">조건 추가</div>
            {rest.map((k) => (
              <button key={k} className="menu-row" onClick={() => { pop.close(); onPick(k) }}><Icon name={FILTER_META[k].icon} size={14} /> {FILTER_META[k].label}</button>
            ))}
          </div>
        </Popover>
      )}
    </>
  )
}

/** 컬럼 키 → 정렬 키 / 필터 키 (표 헤더 메뉴) */
const HEADER_SORT = { name: 'name', key: 'id', status: 'status', assignee: 'assignee', due: 'due', start: 'start', priority: 'priority', type: 'type', version: 'version', progress: 'progress', created: 'created', updated: 'updated', sla: 'sla' }
const HEADER_FILTER = { status: 'status', assignee: 'assignee', due: 'due', priority: 'priority', type: 'type', version: 'version', sla: 'sla', duty: 'duty', group: 'groups' }

/**
 * 표 컬럼 헤더 메뉴: 오름/내림차순 정렬, 컬럼 필터, 컬럼 숨기기.
 * colKey: 'name' | 'key' | 기본 컬럼 키 | 'c:<id>'
 */
export function HeaderMenu({ colKey, label, col, board, sort, onSort, filters, onFilter, onHide, onSettings, onAddRight, onRename, users, me, statusById }) {
  const pop = usePopover()
  const [newName, setNewName] = useState('')
  const [saving, setSaving] = useState(false)
  const [renameErr, setRenameErr] = useState(null)
  const [mode, setMode] = useState('menu') // menu | filter
  const [q, setQ] = useState('')
  const sortKey = colKey.startsWith('c:') ? (col?.type === 'LONG_TEXT' ? null : colKey) : HEADER_SORT[colKey] || null
  const filterKey = colKey.startsWith('c:') ? (col?.type === 'LONG_TEXT' ? null : 'fields') : HEADER_FILTER[colKey] || null
  const sorted = sortKey && sort.key === sortKey
  const filtered = filterKey === 'fields' ? !!filters.fields?.[col.id]?.length : filterKey ? hasValue(filters, filterKey) : false
  const open = (e) => { setMode('menu'); setQ(''); setRenameErr(null); pop.open(e) }
  const submitRename = async (e) => {
    e.preventDefault()
    const v = newName.trim()
    if (!v || v === label) { pop.close(); return }
    setSaving(true); setRenameErr(null)
    try { await onRename(v); pop.close() } catch (err) { setRenameErr(err.message) } finally { setSaving(false) }
  }
  const pick = (patch) => { onFilter(patch); pop.close() }

  // 컬럼별 거르기 옵션
  const options = () => {
    switch (filterKey) {
      case 'status': return [['open', '완료 제외'], ...board.statuses.map((s) => [String(s.id), s.label]), ['none', '상태 없음']]
      case 'assignee': return [['me', `내 ${itemNoun(board)}`], ...users.map((u) => [String(u.id), u.name]), ['none', '담당자 없음']]
      case 'type': return [...(board.types || []).map((t) => [String(t.id), t.name]), ['none', '유형 없음']]
      case 'version': return [...(board.versions || []).map((v) => [String(v.id), v.name]), ['none', '버전 없음']]
      case 'priority': return PRIORITIES
      case 'due': return DUES
      case 'sla': return SLAS
      case 'duty': return [['none', '담당업무 없음']]
      default: return []
    }
  }
  const top = board.items.filter((i) => !i.parentId)
  const sel = filterKey === 'fields' ? new Set(filters.fields?.[col.id] || []) : null
  const setField = (vs) => { const n = { ...(filters.fields || {}) }; if (vs.length) n[col.id] = vs; else delete n[col.id]; onFilter({ fields: n }) }

  return (
    <>
      <span className={`th-label ${sorted || filtered ? 'on' : ''}`}>
        <span className="ellipsis">{label}</span>
        {sorted && <Icon name={sort.dir === 'desc' ? 'arrowDown' : 'arrowUp'} size={11} />}
        {filtered && <Icon name="filter" size={11} />}
      </span>
      <button type="button" className={`th-more ${pop.anchor ? 'open' : ''}`} onClick={open} title={`${label} — 정렬·필터·설정`} aria-label={`${label} 컬럼 메뉴`}><Icon name="more" size={14} /></button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={230}>
          {mode === 'menu' ? (
            <div className="menu">
              {sortKey && <>
                <button className={`menu-row ${sorted && sort.dir !== 'desc' ? 'active' : ''}`} onClick={() => { onSort({ key: sortKey, dir: 'asc' }); pop.close() }}><Icon name="arrowUp" size={14} /> {sortKey === 'priority' ? '높은 순' : '오름차순 정렬'}</button>
                <button className={`menu-row ${sorted && sort.dir === 'desc' ? 'active' : ''}`} onClick={() => { onSort({ key: sortKey, dir: 'desc' }); pop.close() }}><Icon name="arrowDown" size={14} /> {sortKey === 'priority' ? '낮은 순' : '내림차순 정렬'}</button>
                {sorted && <button className="menu-row" onClick={() => { onSort({ key: 'manual', dir: 'asc' }); pop.close() }}><Icon name="x" size={14} /> 정렬 해제</button>}
              </>}
              {filterKey && <>
                {sortKey && <div className="menu-sep" />}
                <button className="menu-row" onClick={() => setMode('filter')}><Icon name="filter" size={14} /> <span className="grow">컬럼 필터</span><Icon name="chevronRight" size={13} /></button>
                {filtered && <button className="menu-row" onClick={() => { if (filterKey === 'fields') setField([]); else onFilter(clearOne(filters, filterKey)); pop.close() }}><Icon name="x" size={14} /> 컬럼 필터 해제</button>}
              </>}
              {(onHide || onSettings || onAddRight || onRename) && <div className="menu-sep" />}
              {onRename && <button className="menu-row" onClick={() => { setNewName(label); setMode('rename') }}><Icon name="edit" size={14} /> 컬럼 이름 변경</button>}
              {onAddRight && <button className="menu-row" onClick={() => setMode('add')}><Icon name="plus" size={14} /> <span className="grow">오른쪽에 컬럼 추가</span><Icon name="chevronRight" size={13} /></button>}
              {onHide && <button className="menu-row" onClick={() => { onHide(); pop.close() }}><Icon name="eyeOff" size={14} /> 이 보기에서 컬럼 숨기기</button>}
              {onSettings && <button className="menu-row" onClick={() => { onSettings(); pop.close() }}><Icon name="settings" size={14} /> 컬럼 설정 (이름·유형·삭제)…</button>}
            </div>
          ) : mode === 'rename' ? (
            <form className="menu" onSubmit={submitRename}>
              <button type="button" className="menu-row" onClick={() => setMode('menu')}><Icon name="chevronLeft" size={14} /> <b className="grow">컬럼 이름 변경</b></button>
              <div style={{ padding: '4px 6px 6px', display: 'flex', flexDirection: 'column', gap: 6 }}>
                <input className="input" autoFocus maxLength={60} value={newName} onChange={(e) => setNewName(e.target.value)}
                  onFocus={(e) => e.target.select()} onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); setMode('menu') } }} />
                {renameErr && <div className="form-error">{renameErr}</div>}
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 6 }}>
                  <button type="button" className="btn btn-sm" onClick={() => setMode('menu')}>취소</button>
                  <button type="submit" className="btn btn-sm btn-primary" disabled={saving || !newName.trim()}>{saving ? '저장 중…' : '저장'}</button>
                </div>
              </div>
            </form>
          ) : mode === 'add' ? (
            <div className="menu">
              <button className="menu-row" onClick={() => setMode('menu')}><Icon name="chevronLeft" size={14} /> <b className="grow ellipsis">'{label}' 오른쪽에 컬럼 추가</b></button>
              <AddColumnForm board={board} onDone={(c) => { pop.close(); onAddRight(c) }} />
            </div>
          ) : filterKey === 'fields' ? (
            <div className="menu">
              <button className="menu-row" onClick={() => setMode('menu')}><Icon name="chevronLeft" size={14} /> <b className="grow ellipsis">{label}</b></button>
              <div className="filter-search" style={{ margin: '4px 2px 6px' }}>
                <Icon name="search" size={13} />
                <input autoFocus placeholder="값 찾기" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }} />
              </div>
              <div style={{ maxHeight: '20rem', overflowY: 'auto' }}>
                <label className="menu-row check-row">
                  <input type="checkbox" checked={sel.has('')} onChange={() => setField(sel.has('') ? [...sel].filter((x) => x !== '') : [...sel, ''])} />
                  <span className="grow muted">비어 있음</span>
                </label>
                {distinctValues(top, col).map(([v, n]) => [v, n, col.type === 'RELATION' ? refLabel(col.id, v) : v])
                  .filter(([, , t]) => !q || t.toLowerCase().includes(q.toLowerCase())).slice(0, 300).map(([v, n, t]) => (
                  <label key={v} className="menu-row check-row">
                    <input type="checkbox" checked={sel.has(v)} onChange={() => setField(sel.has(v) ? [...sel].filter((x) => x !== v) : [...sel, v])} />
                    <span className="grow ellipsis" title={t}>{t}</span><span className="menu-meta">{n}</span>
                  </label>
                ))}
              </div>
            </div>
          ) : (
            <div className="menu">
              <button className="menu-row" onClick={() => setMode('menu')}><Icon name="chevronLeft" size={14} /> <b className="grow ellipsis">{label}</b></button>
              <div className="menu-sep" />
              <div style={{ maxHeight: '20rem', overflowY: 'auto' }}>
                <button className="menu-row" onClick={() => pick(clearOne(filters, filterKey))}>모두</button>
                {options().map(([k, l]) => {
                  const cur = filterKey === 'groups' ? filters.groups.includes(Number(k)) : filters[filterKey] === k
                  return (
                    <button key={k} className={`menu-row ${cur ? 'active' : ''}`} onClick={() => pick({ [filterKey]: k })}>
                      {filterKey === 'status' && board.statuses.find((x) => String(x.id) === k) && <StatusIcon status={board.statuses.find((x) => String(x.id) === k)} statuses={board.statuses} size={12} />}
                      <span className="grow">{l}</span>{cur && <Icon name="check" size={14} />}
                    </button>
                  )
                })}
              </div>
            </div>
          )}
        </Popover>
      )}
    </>
  )
}


/** 컬럼 추가 폼 (이름 + 유형). 만들어진 컬럼을 onDone 으로 돌려줌. 연결 컬럼은 설정 화면에서 */
export function AddColumnForm({ board, onDone, onSettings }) {
  const [name, setName] = useState('')
  const [type, setType] = useState('TEXT')
  const [ref, setRef] = useState({ refBoardId: null, refColumnId: null })
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)
  const submit = async (e) => {
    e.preventDefault()
    if (!name.trim()) return
    if (type === 'RELATION' && !ref.refBoardId) { setErr('연결할 워크스페이스를 선택하세요.'); return }
    setBusy(true); setErr(null)
    try {
      const c = await api.post(`/api/boards/${board.id}/columns`, { name: name.trim(), type, scope: 'ITEM', visible: type !== 'LONG_TEXT', ...(type === 'RELATION' ? ref : {}) })
      setName(''); await onDone?.(c)
    } catch (x) { setErr(x.message) } finally { setBusy(false) }
  }
  return (
    <form className="col-add-pop" onSubmit={submit}>
      <input className="input input-sm" autoFocus placeholder="컬럼 이름 (예: 고객사코드)" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
      <select className="input input-sm" value={type} onChange={(e) => setType(e.target.value)}>
        {COLUMN_TYPES.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
      </select>
      {type === 'RELATION' && <div className="col-add-rel"><RelationTarget board={board} refBoardId={ref.refBoardId} refColumnId={ref.refColumnId} onChange={(rb, rc) => setRef({ refBoardId: rb, refColumnId: rc })} /></div>}
      {err && <div className="form-error">{err}</div>}
      <div className="col-add-actions">
        {onSettings && <button type="button" className="link" onClick={onSettings}>컬럼 상세 설정…</button>}
        <span className="grow" />
        <button className="btn btn-sm btn-primary" disabled={!name.trim() || busy}>추가</button>
      </div>
    </form>
  )
}

/** 표 헤더 오른쪽 끝 '＋' — 맨 뒤에 컬럼 추가 */
export function AddColumnButton({ board, onAdded, onSettings }) {
  const pop = usePopover()
  return (
    <>
      <button type="button" className="icon-btn icon-btn-sm th-add-btn" onClick={pop.open} title="컬럼 추가" aria-label="컬럼 추가"><Icon name="plus" size={14} /></button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={320} align="right">
          <div className="menu">
            <div className="menu-title">컬럼 추가</div>
            <AddColumnForm board={board} onDone={async (c) => { pop.close(); await onAdded?.(c) }} onSettings={onSettings ? () => { pop.close(); onSettings() } : null} />
          </div>
        </Popover>
      )}
    </>
  )
}
