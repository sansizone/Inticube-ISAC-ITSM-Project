import React, { useEffect, useMemo, useRef, useState } from 'react'
import { Avatar, Popover, usePopover } from './common.jsx'
import { Icon } from './icons.jsx'
import { api } from '../api.js'

/** 사용자 정의 컬럼 유형 */
export const COLUMN_TYPES = [
  ['TEXT', '텍스트'], ['LONG_TEXT', '긴 텍스트'], ['NUMBER', '숫자'], ['DATE', '날짜'], ['DATETIME', '날짜+시간'],
  ['PEOPLE', '사람'], ['DROPDOWN', '드롭다운'], ['LINK', '링크'], ['RELATION', '연결'],
]

/** 기본 컬럼 (이름 변경·숨김·순서 변경 가능) */
export const BUILTIN_COLUMNS = [
  { key: 'type', label: '유형' },
  { key: 'status', label: '상태' },
  { key: 'assignee', label: '담당자' },
  { key: 'sla', label: 'SLA' },
  { key: 'due', label: '마감일' },
  { key: 'start', label: '시작일' },
  { key: 'priority', label: '우선순위' },
  { key: 'duty', label: '담당업무' },
  { key: 'version', label: '버전' },
  { key: 'progress', label: '진척률' },
  { key: 'estimate', label: '예상 시간' },
  { key: 'spent', label: '소요 시간' },
  { key: 'group', label: '그룹' },
  { key: 'created', label: '등록일' },
  { key: 'resolved', label: '처리 완료' },
  { key: 'updated', label: '수정일' },
]
const BUILTIN_DEFAULT_HIDDEN = ['start', 'group', 'updated', 'version', 'progress', 'estimate', 'spent', 'resolved']
const BUILTIN_LABEL = Object.fromEntries(BUILTIN_COLUMNS.map((c) => [c.key, c.label]))

/** board.columnConfig(JSON) → { labels: {key: 이름}, hidden: [key], order: [key] } */
export function columnConfig(board) {
  let c = null
  try { c = board.columnConfig ? JSON.parse(board.columnConfig) : null } catch { c = null }
  // 설정을 저장한 뒤 새로 생긴 기본 컬럼(known 에 없는 것)은 기본 숨김 규칙을 따른다
  const known = c?.known || null
  let hidden = c?.hidden || BUILTIN_DEFAULT_HIDDEN
  if (c && known) hidden = [...new Set([...hidden, ...BUILTIN_DEFAULT_HIDDEN.filter((k) => !known.includes(k))])]
  else if (c && !known) hidden = [...new Set([...hidden, ...BUILTIN_DEFAULT_HIDDEN.filter((k) => !['start', 'group', 'updated'].includes(k))])]
  return { labels: c?.labels || {}, hidden, order: c?.order || [], widths: c?.widths || {}, known: BUILTIN_COLUMNS.map((b) => b.key) }
}

/** 기본 컬럼 표시 이름 (설정에서 바꾼 이름) */
export function builtinLabel(board, key) {
  return columnConfig(board).labels[key] || BUILTIN_LABEL[key] || key
}

/**
 * 워크스페이스의 전체 컬럼 (기본 + 사용자 정의 업무 컬럼), 설정 순서대로.
 * { key, label, builtin, col?, hidden }  — key: 'status' … / 'c:<id>'
 */
export function boardColumns(board) {
  const cfg = columnConfig(board)
  const all = [
    ...BUILTIN_COLUMNS.map((b) => ({ key: b.key, label: cfg.labels[b.key] || b.label, builtin: true, hidden: cfg.hidden.includes(b.key) })),
    ...columnsFor(board, 'ITEM').map((c) => ({ key: `c:${c.id}`, label: c.name, builtin: false, col: c, hidden: !c.visible })),
  ]
  const byKey = new Map(all.map((c) => [c.key, c]))
  const out = []
  for (const k of cfg.order) if (byKey.has(k)) { out.push(byKey.get(k)); byKey.delete(k) }
  return [...out, ...byKey.values()]
}

/** 보기에서 보여 줄 컬럼: 워크스페이스에서 숨기지 않았고 보기에서도 숨기지 않은 것 */
export function viewColumns(board, viewHidden = []) {
  return boardColumns(board).filter((c) => !c.hidden && !viewHidden.includes(c.key))
}

// ---------- 연결(RELATION) 컬럼 ----------
const refCache = new Map()
export function rememberRefs(board) {
  for (const [cid, m] of Object.entries(board?.refLabels || {})) for (const [id, label] of Object.entries(m)) refCache.set(`${cid}:${id}`, label)
}
export const refLabel = (colId, id) => refCache.get(`${colId}:${id}`) || `#${id}`
export const relIds = (v) => (v || '').split(',').map((x) => Number(x.trim())).filter((x) => x > 0)

function RelationView({ col, value, compact }) {
  const ids = relIds(value)
  const shown = compact ? ids.slice(0, 2) : ids
  return (
    <span className="rel-cell">
      {shown.map((id) => (
        <a key={id} className="rel-chip" href={`#/board/${col.refBoardId}?item=${id}`} onClick={(e) => e.stopPropagation()} title={`${refLabel(col.id, id)} 열기`}>
          <Icon name="link" size={11} /><span className="ellipsis">{refLabel(col.id, id)}</span>
        </a>
      ))}
      {compact && ids.length > 2 && <span className="muted small">+{ids.length - 2}</span>}
    </span>
  )
}

/** 연결 컬럼 편집: 연결된 항목 칩 + 검색해서 추가 */
export function RelationEditor({ col, value, onSave, targetName }) {
  const ids = relIds(value)
  const pop = usePopover()
  const [q, setQ] = useState('')
  const [opts, setOpts] = useState(null)
  useEffect(() => {
    if (!pop.anchor) return
    const t = setTimeout(() => {
      api.get(`/api/columns/${col.id}/options?q=${encodeURIComponent(q)}&limit=40`).then((list) => {
        list.forEach((o) => refCache.set(`${col.id}:${o.id}`, o.label))
        setOpts(list)
      }).catch(() => setOpts([]))
    }, 200)
    return () => clearTimeout(t)
  }, [q, pop.anchor, col.id])
  const save = (next) => onSave(next.join(','))
  return (
    <div className="rel-editor">
      {ids.map((id) => (
        <span key={id} className="rel-chip">
          <a href={`#/board/${col.refBoardId}?item=${id}`} title="열기"><Icon name="link" size={11} /> {refLabel(col.id, id)}</a>
          <button className="rel-x" aria-label="연결 해제" onClick={() => save(ids.filter((x) => x !== id))}><Icon name="x" size={11} /></button>
        </span>
      ))}
      <button className="rel-add" onClick={(e) => { setQ(''); setOpts(null); pop.open(e) }}><Icon name="plus" size={12} /> {ids.length ? '추가' : '연결'}</button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={320}>
          <div className="menu">
            <div className="menu-title">{targetName || '대상 워크스페이스'}에서 선택</div>
            <div className="filter-search" style={{ margin: '0 6px 6px' }}>
              <Icon name="search" size={13} />
              <input autoFocus placeholder="검색" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }} />
            </div>
            <div style={{ maxHeight: '18rem', overflowY: 'auto' }}>
              {opts == null && <div className="menu-empty">불러오는 중…</div>}
              {opts?.map((o) => {
                const on = ids.includes(o.id)
                return (
                  <button key={o.id} className={`menu-row ${on ? 'active' : ''}`} onClick={() => save(on ? ids.filter((x) => x !== o.id) : [...ids, o.id])}>
                    <span className={`check-box ${on ? 'on' : ''}`}>{on && <Icon name="check" size={11} />}</span>
                    <span className="grow ellipsis">{o.label}{o.sub && <span className="muted small"> · {o.sub}</span>}</span>
                  </button>
                )
              })}
              {opts && opts.length === 0 && <div className="menu-empty">결과가 없습니다.</div>}
            </div>
          </div>
        </Popover>
      )}
    </div>
  )
}
export const TYPE_LABEL = Object.fromEntries(COLUMN_TYPES)

/** 이름만 있는 사람(계정 없음)용 가짜 사용자 — 아바타 색을 이름으로 고정 */
export function nameUser(name) {
  let h = 0
  for (const ch of name || '') h = (h * 31 + ch.charCodeAt(0)) | 0
  return { id: Math.abs(h), name }
}

export const splitPeople = (v) => (v || '').split(/[,;\n]/).map((s) => s.trim()).filter(Boolean)

const TAG_COLORS = ['#0061F2', '#6900C7', '#00AC69', '#F4A100', '#E81500', '#00CFD5', '#F76400', '#E30059', '#5A6270']
export function tagColor(v) { return TAG_COLORS[nameUser(v).id % TAG_COLORS.length] }

/** 보드 컬럼 목록 (범위별) */
export function columnsFor(board, scope) {
  return (board.columns || []).filter((c) => c.scope === scope).sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)
}

/** 컬럼별 고유값 (드롭다운 후보·필터용) */
export function distinctValues(items, col) {
  const m = new Map()
  for (const i of items) {
    const v = i.values?.[col.id]
    if (!v) continue
    const list = col.type === 'PEOPLE' ? splitPeople(v) : col.type === 'RELATION' ? relIds(v).map(String) : [v]
    for (const x of list) m.set(x, (m.get(x) || 0) + 1)
  }
  return [...m.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'ko'))
}

/** 값 표시 */
export function FieldView({ col, value, compact }) {
  if (value == null || value === '') return <span className="muted">-</span>
  switch (col.type) {
    case 'RELATION':
      return <RelationView col={col} value={value} compact={compact} />
    case 'PEOPLE': {
      const names = splitPeople(value)
      return (
        <span className="people-cell" title={value}>
          {names.slice(0, compact ? 2 : 20).map((n) => (
            <span key={n} className="person-chip"><Avatar user={nameUser(n)} size={18} />{(!compact || names.length === 1) && <span>{n}</span>}</span>
          ))}
          {compact && names.length > 2 && <span className="muted small">+{names.length - 2}</span>}
        </span>
      )
    }
    case 'DROPDOWN':
      return <span className="dd-tag" style={{ '--c': tagColor(value) }} title={value}>{value}</span>
    case 'LINK':
      return /^https?:\/\//i.test(value)
        ? <a className="link ellipsis" href={value} target="_blank" rel="noopener noreferrer" onClick={(e) => e.stopPropagation()} title={value}>{value}</a>
        : <span className="ellipsis" title={value}>{value}</span>
    case 'LONG_TEXT':
      return compact ? <span className="ellipsis" title={value}>{value.replace(/\s+/g, ' ')}</span> : <div className="long-text">{value}</div>
    case 'NUMBER':
      return <span className="num">{value}</span>
    default:
      return <span className={compact ? 'ellipsis' : ''} title={value}>{value}</span>
  }
}

/** 상세 화면 편집기: 보기 상태에서 누르면 편집, 포커스를 잃거나 Enter 면 저장 */
export function FieldEditor(props) {
  if (props.col.type === 'RELATION') return <RelationEditor col={props.col} value={props.value} onSave={props.onSave} targetName={props.targetName} />
  return <PlainFieldEditor {...props} />
}

function PlainFieldEditor({ col, value, onSave, options = [], users = [] }) {
  const [editing, setEditing] = useState(false)
  const [v, setV] = useState(value || '')
  const ref = useRef(null)
  useEffect(() => { if (!editing) setV(value || '') }, [value, editing])
  useEffect(() => { if (editing) ref.current?.focus() }, [editing])
  const listId = useMemo(() => `dl-${col.id}`, [col.id])

  const commit = () => {
    setEditing(false)
    let out = v
    if (col.type === 'DATETIME' && out) out = out.replace('T', ' ')
    if ((out || '') !== (value || '')) onSave(out)
  }
  if (!editing) {
    return (
      <button className={`field-view ${col.type === 'LONG_TEXT' ? 'multi' : ''}`} onClick={() => setEditing(true)} title="눌러서 수정">
        {value ? <FieldView col={col} value={value} /> : <span className="muted">비어 있음</span>}
      </button>
    )
  }
  const common = {
    ref, value: v, onChange: (e) => setV(e.target.value), onBlur: commit,
    onKeyDown: (e) => {
      if (e.key === 'Escape') { setV(value || ''); setEditing(false) }
      if (e.key === 'Enter' && (col.type !== 'LONG_TEXT' || e.ctrlKey || e.metaKey)) { e.preventDefault(); commit() }
    },
  }
  switch (col.type) {
    case 'LONG_TEXT':
      return <textarea className="input field-input" rows={Math.min(14, Math.max(4, (v.match(/\n/g) || []).length + 2))} {...common} />
    case 'DATE':
      return <input type="date" className="input input-sm field-input" {...common} value={v.slice(0, 10)} />
    case 'DATETIME':
      return <input type="datetime-local" className="input input-sm field-input" {...common} value={v.replace(' ', 'T').slice(0, 16)} />
    case 'NUMBER':
      return <input inputMode="decimal" className="input input-sm field-input" {...common} />
    case 'DROPDOWN':
    case 'PEOPLE':
      return (
        <>
          <input className="input input-sm field-input" list={listId} placeholder={col.type === 'PEOPLE' ? '이름, 이름' : ''} {...common} />
          <datalist id={listId}>
            {(col.type === 'PEOPLE' ? [...new Set([...options.map(([o]) => o), ...users.map((u) => u.name)])] : options.map(([o]) => o))
              .slice(0, 300).map((o) => <option key={o} value={o} />)}
          </datalist>
        </>
      )
    default:
      return <input className="input input-sm field-input" {...common} />
  }
}
