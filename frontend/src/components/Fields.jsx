import React, { useEffect, useMemo, useRef, useState } from 'react'
import { Avatar, Popover, usePopover } from './common.jsx'
import { Icon } from './icons.jsx'
import { api } from '../api.js'

/** 사용자 정의 컬럼 유형 */
export const COLUMN_TYPES = [
  ['TEXT', '텍스트'], ['LONG_TEXT', '긴 텍스트'], ['NUMBER', '숫자'], ['DATE', '날짜'], ['DATETIME', '날짜+시간'],
  ['PEOPLE', '사람'], ['DROPDOWN', '드롭다운'], ['LINK', 'URL 링크'], ['RELATION', '연결 (다른 워크스페이스)'],
]

/** 기본 컬럼 (이름 변경·숨김·순서 변경 가능) */
export const BUILTIN_COLUMNS = [
  { key: 'status', label: '상태' },
  { key: 'assignee', label: '담당자' },
  { key: 'due', label: '마감일' },
  { key: 'start', label: '시작일' },
  { key: 'priority', label: '우선순위' },
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
// name(업무명)·key(키)는 항상 보이는 고정 컬럼이지만 이름은 워크스페이스마다 바꿀 수 있음 (예: 업무명 → 솔루션명)
const BUILTIN_LABEL = Object.fromEntries([...BUILTIN_COLUMNS.map((c) => [c.key, c.label]), ['name', '업무명'], ['key', '키']])

/** board.columnConfig(JSON) → { labels: {key: 이름}, hidden: [key], order: [key] } */
export function columnConfig(board) {
  let c = null
  try { c = board.columnConfig ? JSON.parse(board.columnConfig) : null } catch { c = null }
  // 설정을 저장한 뒤 새로 생긴 기본 컬럼(known 에 없는 것)은 기본 숨김 규칙을 따른다
  const known = c?.known || null
  let hidden = c?.hidden || BUILTIN_DEFAULT_HIDDEN
  if (c && known) hidden = [...new Set([...hidden, ...BUILTIN_DEFAULT_HIDDEN.filter((k) => !known.includes(k))])]
  else if (c && !known) hidden = [...new Set([...hidden, ...BUILTIN_DEFAULT_HIDDEN.filter((k) => !['start', 'group', 'updated'].includes(k))])]
  return { labels: c?.labels || {}, hidden, order: c?.order || [], widths: c?.widths || {}, known: BUILTIN_COLUMNS.map((b) => b.key), showProgress: c?.showProgress !== false }
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
        // 드롭다운 값처럼 색 태그로 표시 (링크 아이콘 없이), 마우스를 올리면 강조 + 누르면 원본 항목 열기
        <a key={id} className="dd-tag rel-tag" style={{ '--c': tagColor(refLabel(col.id, id)) }} href={`#/board/${col.refBoardId}?item=${id}`}
          onClick={(e) => e.stopPropagation()} title={`${refLabel(col.id, id)} — 눌러서 열기`}>{refLabel(col.id, id)}</a>
      ))}
      {compact && ids.length > 2 && <span className="muted small">+{ids.length - 2}</span>}
    </span>
  )
}

/** 연결 컬럼 편집: 연결된 항목 칩 + 검색해서 추가 */
export function RelationEditor({ col, value, onSave, targetName, compact }) {
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
  const renderPop = () => pop.anchor && (
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
  )
  const openPick = (e) => { setQ(''); setOpts(null); pop.open(e) }
  // 표 칸: 태그만 보이고 칸을 누르면 고르기 창
  // 표 칸: 할당된 값은 눌러서 원본 항목 열기 + 옆 ×로 할당 해제, 비어 있으면 칸을 눌러 선택
  if (compact) return (
    <>
      {ids.length === 0
        ? <button type="button" className="cell-edit" onClick={openPick} title="눌러서 선택"><span className="cell-empty" /></button>
        : <span className="rel-cell rel-cell-edit">
            {ids.map((id) => (
              <span key={id} className="dd-tag rel-tag rel-tag-edit" style={{ '--c': tagColor(refLabel(col.id, id)) }}>
                <a href={`#/board/${col.refBoardId}?item=${id}`} onClick={(e) => e.stopPropagation()} title={`${refLabel(col.id, id)} — 눌러서 열기`}>{refLabel(col.id, id)}</a>
                <button type="button" className="rel-x" aria-label="할당 해제" title="할당 해제"
                  onClick={(e) => { e.preventDefault(); e.stopPropagation(); save(ids.filter((x) => x !== id)) }}><Icon name="x" size={11} /></button>
              </span>
            ))}
          </span>}
      {renderPop()}
    </>
  )
  return (
    <div className="rel-editor">
      {ids.map((id) => (
        <span key={id} className="dd-tag rel-tag rel-tag-edit" style={{ '--c': tagColor(refLabel(col.id, id)) }}>
          <a href={`#/board/${col.refBoardId}?item=${id}`} title={`${refLabel(col.id, id)} — 눌러서 열기`}>{refLabel(col.id, id)}</a>
          <button className="rel-x" aria-label="연결 해제" title="연결 해제" onClick={() => save(ids.filter((x) => x !== id))}><Icon name="x" size={11} /></button>
        </span>
      ))}
      <button className="rel-add" onClick={(e) => { setQ(''); setOpts(null); pop.open(e) }}><Icon name="search" size={12} /> {ids.length ? '추가' : `${targetName || ''}에서 선택`}</button>
      {renderPop()}
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
  if (props.col.type === 'RELATION') return <RelationEditor col={props.col} value={props.value} onSave={props.onSave} targetName={props.targetName} compact={props.compact} />
  return <PlainFieldEditor {...props} />
}

function PlainFieldEditor({ col, value, onSave, options = [], users = [], compact }) {
  if (col.type === 'PEOPLE') return <PeopleEditor value={value} onSave={onSave} options={options} users={users} compact={compact} />
  if (col.type === 'DROPDOWN') return <DropdownEditor value={value} onSave={onSave} options={options} compact={compact} />
  return <TextishFieldEditor col={col} value={value} onSave={onSave} compact={compact} />
}

/** 사람 컬럼 편집: 선택된 사람 칩 + 검색해서 고르기 (계정 목록 기준, 계정 없는 이름도 추가 가능) */
export function PeopleEditor({ value, onSave, options = [], users = [], compact }) {
  const names = splitPeople(value)
  const pop = usePopover()
  const [q, setQ] = useState('')
  const save = (next) => onSave([...new Set(next)].join(', '))
  const byName = new Map(users.map((u) => [u.name, u]))
  // 후보: 계정 사용자 + 이 컬럼에 이미 쓰인 이름(계정 없는 사람)
  const extra = options.map(([o]) => o).flatMap(splitPeople).filter((n) => !byName.has(n))
  const cands = [...users.map((u) => ({ name: u.name, user: u, sub: [u.department, u.username].filter(Boolean).join(' · ') })),
    ...[...new Set(extra)].map((n) => ({ name: n, user: nameUser(n), sub: '계정 없음' }))]
  const ql = q.trim().toLowerCase()
  const shown = (ql ? cands.filter((c) => c.name.toLowerCase().includes(ql) || (c.sub || '').toLowerCase().includes(ql)) : cands).slice(0, 80)
  const canAdd = ql && !cands.some((c) => c.name === q.trim())
  const renderPop = () => pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={320}>
          <div className="menu">
            <div className="filter-search" style={{ margin: '0 6px 6px' }}>
              <Icon name="search" size={13} />
              <input autoFocus placeholder="이름·부서·아이디 검색" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }}
                onKeyDown={(e) => { if (e.key === 'Enter' && canAdd) { e.preventDefault(); save([...names, q.trim()]); setQ('') } }} />
            </div>
            <div style={{ maxHeight: '18rem', overflowY: 'auto' }}>
              {shown.map((c) => {
                const on = names.includes(c.name)
                return (
                  <button key={`${c.name}|${c.sub}`} className={`menu-row ${on ? 'active' : ''}`} onClick={() => save(on ? names.filter((x) => x !== c.name) : [...names, c.name])}>
                    <span className={`check-box ${on ? 'on' : ''}`}>{on && <Icon name="check" size={11} />}</span>
                    <Avatar user={c.user} size={20} />
                    <span className="grow ellipsis">{c.name}{c.sub && <span className="muted small"> · {c.sub}</span>}</span>
                  </button>
                )
              })}
              {canAdd && <button className="menu-row" onClick={() => { save([...names, q.trim()]); setQ('') }}><Icon name="plus" size={13} /> '{q.trim()}' 이름으로 추가</button>}
              {shown.length === 0 && !canAdd && <div className="menu-empty">결과가 없습니다.</div>}
            </div>
          </div>
        </Popover>
  )
  if (compact) return (
    <>
      <button type="button" className="cell-edit" onClick={(e) => { setQ(''); pop.open(e) }} title="눌러서 선택">
        {names.length ? <FieldView col={{ type: 'PEOPLE' }} value={value} compact /> : <span className="cell-empty" />}
      </button>
      {renderPop()}
    </>
  )
  return (
    <div className="rel-editor">
      {names.map((n) => (
        <span key={n} className="rel-chip person-pick">
          <Avatar user={byName.get(n) || nameUser(n)} size={16} /> <span className="ellipsis">{n}</span>
          <button className="rel-x" aria-label={`${n} 빼기`} onClick={() => save(names.filter((x) => x !== n))}><Icon name="x" size={11} /></button>
        </span>
      ))}
      <button className="rel-add" onClick={(e) => { setQ(''); pop.open(e) }}><Icon name="search" size={12} /> {names.length ? '추가' : '사람 선택'}</button>
      {renderPop()}
    </div>
  )
}

/** 드롭다운 컬럼 편집: 지금까지 쓰인 값 목록에서 고르기 + 검색 + 새 값 */
export function DropdownEditor({ value, onSave, options = [], compact }) {
  const pop = usePopover()
  const [q, setQ] = useState('')
  const ql = q.trim().toLowerCase()
  const shown = (ql ? options.filter(([o]) => o.toLowerCase().includes(ql)) : options).slice(0, 100)
  const canAdd = ql && !options.some(([o]) => o === q.trim())
  const pick = (v) => { pop.close(); if ((v || '') !== (value || '')) onSave(v) }
  return (
    <>
      {compact
        ? <button type="button" className="cell-edit" onClick={(e) => { setQ(''); pop.open(e) }} title="눌러서 선택">
            {value ? <span className="dd-tag" style={{ '--c': tagColor(value) }}>{value}</span> : <span className="cell-empty" />}
          </button>
        : <button className="field-view dd-pick" onClick={(e) => { setQ(''); pop.open(e) }} title="눌러서 선택">
            {value ? <span className="dd-tag" style={{ '--c': tagColor(value) }}>{value}</span> : <span className="muted">선택</span>}
            <Icon name="chevronDown" size={13} className="muted" />
          </button>}
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={280}>
          <div className="menu">
            <div className="filter-search" style={{ margin: '0 6px 6px' }}>
              <Icon name="search" size={13} />
              <input autoFocus placeholder="검색 또는 새 값" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); if (canAdd) pick(q.trim()); else if (shown[0]) pick(shown[0][0]) } }} />
            </div>
            <div style={{ maxHeight: '18rem', overflowY: 'auto' }}>
              {value && <button className="menu-row muted" onClick={() => pick('')}><Icon name="x" size={13} /> 비우기</button>}
              {shown.map(([o, n]) => (
                <button key={o} className={`menu-row ${o === value ? 'active' : ''}`} onClick={() => pick(o)}>
                  <span className="dd-tag" style={{ '--c': tagColor(o) }}>{o}</span><span className="grow" /><span className="muted small">{n}</span>
                </button>
              ))}
              {canAdd && <button className="menu-row" onClick={() => pick(q.trim())}><Icon name="plus" size={13} /> '{q.trim()}' 새 값</button>}
              {shown.length === 0 && !canAdd && <div className="menu-empty">값이 없습니다. 입력해서 새로 만드세요.</div>}
            </div>
          </div>
        </Popover>
      )}
    </>
  )
}

function TextishFieldEditor({ col, value, onSave, compact }) {
  const [editing, setEditing] = useState(false)
  const pop = usePopover()
  const [v, setV] = useState(value || '')
  const ref = useRef(null)
  useEffect(() => { if (!editing) setV(value || '') }, [value, editing])
  useEffect(() => { if (editing) ref.current?.focus() }, [editing])

  const commit = () => {
    setEditing(false)
    let out = v
    if (col.type === 'DATETIME' && out) out = out.replace('T', ' ')
    if ((out || '') !== (value || '')) onSave(out)
  }
  // 표 칸의 긴 텍스트: 칸 안이 아니라 떠 있는 창에서 편집
  if (compact && col.type === 'LONG_TEXT') {
    return (
      <>
        <button type="button" className="cell-edit" onClick={(e) => { setV(value || ''); pop.open(e) }} title="눌러서 수정">
          {value ? <FieldView col={col} value={value} compact /> : <span className="cell-empty" />}
        </button>
        {pop.anchor && (
          <Popover anchor={pop.anchor} onClose={() => { pop.close(); if ((v || '') !== (value || '')) onSave(v) }} width={380}>
            <div className="menu" style={{ padding: 6 }}>
              <textarea className="input field-input" autoFocus rows={8} value={v} onChange={(e) => setV(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Escape') { setV(value || ''); pop.close() }
                  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); pop.close(); if ((v || '') !== (value || '')) onSave(v) }
                }} />
              <div className="hint" style={{ margin: '4px 2px 0' }}>바깥을 누르거나 Ctrl+Enter 로 저장 · Esc 취소</div>
            </div>
          </Popover>
        )}
      </>
    )
  }
  if (!editing) {
    return compact
      ? <button type="button" className="cell-edit" onClick={() => setEditing(true)} title="눌러서 수정">
          {value ? <FieldView col={col} value={value} compact /> : <span className="cell-empty" />}
        </button>
      : (
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
    default:
      return <input className="input input-sm field-input" {...common} />
  }
}
