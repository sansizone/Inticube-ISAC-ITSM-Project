import React, { useEffect, useMemo, useRef, useState } from 'react'
import { Avatar, Popover, usePopover } from './common.jsx'
import { Icon } from './icons.jsx'
import { api } from '../api.js'
import { itemKey as itemKeyOf } from '../sla.js'

/** 사용자 정의 컬럼 유형 */
export const COLUMN_TYPES = [
  ['TEXT', '텍스트'], ['LONG_TEXT', '긴 텍스트'], ['NUMBER', '숫자'], ['DATE', '날짜'], ['DATETIME', '날짜+시간'],
  ['PEOPLE', '사람'], ['DROPDOWN', '드롭다운'], ['LINK', 'URL 링크'], ['PHONE', '전화번호'], ['RELATION', '연결 (다른 워크스페이스)'],
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
export const BUILTIN_LABEL = Object.fromEntries([...BUILTIN_COLUMNS.map((c) => [c.key, c.label]), ['name', '업무명'], ['key', '키']])

/** board.columnConfig(JSON) → { labels: {key: 이름}, hidden: [key], order: [key] } */
export function columnConfig(board) {
  let c = null
  try { c = board.columnConfig ? JSON.parse(board.columnConfig) : null } catch { c = null }
  // 설정을 저장한 뒤 새로 생긴 기본 컬럼(known 에 없는 것)은 기본 숨김 규칙을 따른다
  const known = c?.known || null
  let hidden = c?.hidden || BUILTIN_DEFAULT_HIDDEN
  if (c && known) hidden = [...new Set([...hidden, ...BUILTIN_DEFAULT_HIDDEN.filter((k) => !known.includes(k))])]
  else if (c && !known) hidden = [...new Set([...hidden, ...BUILTIN_DEFAULT_HIDDEN.filter((k) => !['start', 'group', 'updated'].includes(k))])]
  return { labels: c?.labels || {}, hidden, order: c?.order || [], widths: c?.widths || {}, known: BUILTIN_COLUMNS.map((b) => b.key), showProgress: c?.showProgress !== false, peopleSources: c?.peopleSources || {}, ...(c?.automations ? { automations: c.automations } : {}) }
}

/** 기본 컬럼 표시 이름 (설정에서 바꾼 이름) */
/**
 * 이 워크스페이스에서 한 건을 부르는 말. 업무명 컬럼 이름에서 끝의 '명'을 뗀다
 * (업무명→업무, 고객사명→고객사, 솔루션명→솔루션). '이름'·'Name'·'제목'처럼 일반 이름이면 '항목'.
 */
export function itemNoun(board) {
  const label = builtinLabel(board, 'name').trim()
  if (label === BUILTIN_LABEL.name) return '업무'
  if (/^(이름|성명|명칭|제목|name|title)$/i.test(label)) return '항목'
  return label.replace(/\s*(명|이름|name)$/i, '').trim() || '항목'
}

/** 조사: josa('고객사', '을/를') → '를', josa('업무', '이/가') → '가'. 한글이 아니면 '을(를)'처럼 둘 다 */
export function josa(word, pair) {
  const [a, b] = pair.split('/')
  const c = String(word).trim().slice(-1).charCodeAt(0)
  if (!(c >= 0xac00 && c <= 0xd7a3)) return `${a}(${b})`
  const jong = (c - 0xac00) % 28
  if (a === '으로') return jong === 0 || jong === 8 ? '로' : '으로'
  return jong ? a : b
}

/** 업무 추가 입력칸 안내 문구: 업무명 컬럼 이름을 바꿨으면 그 이름으로 (예: '솔루션명 입력 후 Enter') */
export function addPlaceholder(board) {
  const label = builtinLabel(board, 'name')
  return label === BUILTIN_LABEL.name ? '업무 추가 후 Enter' : `${label} 입력 후 Enter`
}

/** 확인창·알림에 쓰는 한 건의 이름: 키 + 이름 (예: 'CS-12 한솔생명') */
export function itemTitle(board, it) {
  const k = it ? itemKeyOf(board, it) : ''
  return `${k ? k + ' ' : ''}${it?.name || ''}`.trim()
}

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
// 값 = 쉼표로 구분한 대상 워크스페이스 업무 ID
const refCache = new Map()
export function rememberRefs(board) {
  for (const [cid, m] of Object.entries(board?.refLabels || {})) for (const [id, label] of Object.entries(m)) refCache.set(`${cid}:${id}`, label)
}
/** 사람 컬럼마다 후보 워크스페이스(컬럼 설정 peopleSources)를 col.peopleSource = { id, name } 로 붙임 */
export function withPeopleSources(board) {
  const ps = columnConfig(board).peopleSources
  if (!Object.keys(ps).length) return board
  return { ...board, columns: (board.columns || []).map((c) => (c.type === 'PEOPLE' && ps[c.id]
    ? { ...c, peopleSource: { id: Number(ps[c.id]), name: board.refBoards?.[ps[c.id]] || '후보 워크스페이스' } } : c)) }
}
export const refLabel = (colId, id) => refCache.get(`${colId}:${id}`) || `#${id}`
/** 업무 ID (숫자) */
export const relIds = (v) => (v || '').split(',').map((x) => Number(x.trim())).filter((x) => x > 0)
/** 업무 ID (문자열, 저장 순서대로) */
export const relTokens = (v) => relIds(v).map(String)

/** 연결 값 한 개: 색 태그(누르면 원본 항목 열기), 편집 중이면 옆에 × */
function RelTag({ col, tok, onRemove, removeLabel = '연결 해제' }) {
  const label = refLabel(col.id, tok)
  if (!onRemove) {
    return <a className="dd-tag rel-tag" style={{ '--c': tagColor(label) }} href={`#/board/${col.refBoardId}?item=${tok}`}
      onClick={(e) => e.stopPropagation()} title={`${label} — 눌러서 열기`}>{label}</a>
  }
  return (
    <span className="dd-tag rel-tag rel-tag-edit" style={{ '--c': tagColor(label) }}>
      <a href={`#/board/${col.refBoardId}?item=${tok}`} onClick={(e) => e.stopPropagation()} title={`${label} — 눌러서 열기`}>{label}</a>
      <button type="button" className="rel-x" aria-label={removeLabel} title={removeLabel}
        onClick={(e) => { e.preventDefault(); e.stopPropagation(); onRemove() }}><Icon name="x" size={11} /></button>
    </span>
  )
}

function RelationView({ col, value, compact }) {
  const toks = relTokens(value)
  const shown = compact ? toks.slice(0, 2) : toks
  return (
    <span className="rel-cell">
      {shown.map((t) => <RelTag key={t} col={col} tok={t} />)}
      {compact && toks.length > 2 && <span className="muted small">+{toks.length - 2}</span>}
    </span>
  )
}

/** 연결 컬럼 편집: 연결된 항목 칩 + 검색해서 추가. 찾는 이름이 없으면 대상 워크스페이스에 새 항목으로 바로 추가 */
export function RelationEditor({ col, value, onSave, targetName, compact }) {
  const toks = relTokens(value)
  const pop = usePopover()
  const [q, setQ] = useState('')
  const [opts, setOpts] = useState(null)
  const [adding, setAdding] = useState(false)
  const [err, setErr] = useState(null)
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
  const toggle = (tok) => save(toks.includes(tok) ? toks.filter((x) => x !== tok) : [...toks, tok])
  const needle = q.trim().toLowerCase()
  const exact = needle && (opts || []).some((o) => o.label.trim().toLowerCase() === needle)
  const addNew = async () => {
    setAdding(true); setErr(null)
    try {
      const o = await api.post(`/api/columns/${col.id}/options`, { name: q.trim() })
      refCache.set(`${col.id}:${o.id}`, o.label)
      save([...toks, String(o.id)]); setQ('')
    } catch (e) { setErr(e.message) } finally { setAdding(false) }
  }
  const target = targetName || '대상 워크스페이스'
  const renderPop = () => pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={340}>
          <div className="menu">
            <div className="filter-search" style={{ margin: '0 6px 6px' }}>
              <Icon name="search" size={13} />
              <input autoFocus placeholder="검색" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }}
                onKeyDown={(e) => { if (e.key === 'Enter' && needle && !exact && (opts || []).length === 0) { e.preventDefault(); addNew() } }} />
            </div>
            <div style={{ maxHeight: '20rem', overflowY: 'auto' }}>
              {opts == null && <div className="menu-empty">불러오는 중…</div>}
              {opts?.map((o) => {
                const on = toks.includes(String(o.id))
                return (
                  <button key={o.id} className={`menu-row ${on ? 'active' : ''}`} onClick={() => toggle(String(o.id))}>
                    <span className={`check-box ${on ? 'on' : ''}`}>{on && <Icon name="check" size={11} />}</span>
                    <span className="grow ellipsis">{o.label}{o.sub && <span className="muted small"> · {o.sub}</span>}</span>
                  </button>
                )
              })}
              {opts && opts.length === 0 && <div className="menu-empty">{needle ? `${target}에 '${q.trim()}'${josa(q.trim(), '이/가')} 없습니다.` : '항목이 없습니다.'}</div>}
            </div>
            {needle && !exact && (
              <>
                <div className="menu-sep" />
                <button className="menu-row rel-new" disabled={adding} onClick={addNew}>
                  <Icon name="plus" size={14} /> <span className="grow ellipsis">'{q.trim()}' {target}에 새로 추가</span>
                </button>
              </>
            )}
            {err && <div className="form-error" style={{ margin: '4px 6px' }}>{err}</div>}
          </div>
        </Popover>
  )
  const openPick = (e) => { setQ(''); setOpts(null); setErr(null); pop.open(e) }
  // 표 칸: 할당된 값은 눌러서 원본 항목 열기 + 옆 ×로 할당 해제, 비어 있으면 칸을 눌러 선택
  if (compact) return (
    <>
      {toks.length === 0
        ? <button type="button" className="cell-edit" onClick={openPick} title="눌러서 선택"><span className="cell-empty" /></button>
        : <span className="rel-cell rel-cell-edit">
            {toks.map((t) => <RelTag key={t} col={col} tok={t} removeLabel="할당 해제" onRemove={() => save(toks.filter((x) => x !== t))} />)}
            <button type="button" className="rel-add rel-add-sm" onClick={openPick} title="추가" aria-label="추가"><Icon name="plus" size={11} /></button>
          </span>}
      {renderPop()}
    </>
  )
  return (
    <div className="rel-editor">
      {toks.map((t) => <RelTag key={t} col={col} tok={t} onRemove={() => save(toks.filter((x) => x !== t))} />)}
      <button className="rel-add" onClick={openPick}><Icon name="search" size={12} /> {toks.length ? '추가' : `${target}에서 선택`}</button>
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
    const list = col.type === 'PEOPLE' ? splitPeople(v) : col.type === 'RELATION' ? relTokens(v) : [v]
    for (const x of list) m.set(x, (m.get(x) || 0) + 1)
  }
  return [...m.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'ko'))
}

/** 전화번호 표시: 서버가 저장 시 하이픈을 넣지만, 예전에 숫자로 들어온 값(앞자리 0 빠짐)도 같은 규칙으로 보여 줌 */
export function formatPhone(raw) {
  const one = (s) => {
    const v = s.trim()
    if (!/^[\d\-\s().]+$/.test(v)) return v
    let d = v.replace(/\D/g, '')
    if (d.length < 7 || d.length > 11) return v
    if (!d.startsWith('0') && (d.length === 9 || d.length === 10) && !/^1[5-9]/.test(d)) d = '0' + d
    if (d.startsWith('02') && d.length === 9) return `${d.slice(0, 2)}-${d.slice(2, 5)}-${d.slice(5)}`
    if (d.startsWith('02') && d.length === 10) return `${d.slice(0, 2)}-${d.slice(2, 6)}-${d.slice(6)}`
    if (d.length === 8 && /^1[5-9]/.test(d)) return `${d.slice(0, 4)}-${d.slice(4)}`
    if (d.length === 11) return `${d.slice(0, 3)}-${d.slice(3, 7)}-${d.slice(7)}`
    if (d.length === 10) return `${d.slice(0, 3)}-${d.slice(3, 6)}-${d.slice(6)}`
    return v
  }
  return String(raw).split(/\s*[,;\n]\s*/).filter(Boolean).map(one).join(', ')
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
    case 'PHONE':
      return <span className="phone-cell" title={value}>{formatPhone(value)}</span>
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
  if (col.type === 'PEOPLE') return <PeopleEditor col={col} value={value} onSave={onSave} options={options} users={users} compact={compact} />
  if (col.type === 'DROPDOWN') return <DropdownEditor value={value} onSave={onSave} options={options} compact={compact} />
  return <TextishFieldEditor col={col} value={value} onSave={onSave} compact={compact} />
}

/** 사람 컬럼 편집: 선택된 사람 칩 + 검색해서 고르기 (계정 목록 기준, 계정 없는 이름도 추가 가능) */
export function PeopleEditor({ col, value, onSave, options = [], users = [], compact }) {
  const names = splitPeople(value)
  const pop = usePopover()
  const [q, setQ] = useState('')
  const [src, setSrc] = useState(null)       // 후보 워크스페이스 항목 [{ id, label, sub }]
  const [adding, setAdding] = useState(false)
  const [err, setErr] = useState(null)
  const source = col?.peopleSource           // { id, name } — 컬럼 설정에서 고른 후보 워크스페이스 (예: 고객사직원정보)
  useEffect(() => {
    if (!pop.anchor || !source) return
    const t = setTimeout(() => {
      api.get(`/api/columns/${col.id}/people-options?q=${encodeURIComponent(q)}&limit=60`).then(setSrc).catch(() => setSrc([]))
    }, 200)
    return () => clearTimeout(t)
  }, [q, pop.anchor, source?.id]) // eslint-disable-line
  const save = (next) => onSave([...new Set(next)].join(', '))
  const toggle = (n) => save(names.includes(n) ? names.filter((x) => x !== n) : [...names, n])
  const byName = new Map(users.map((u) => [u.name, u]))
  const ql = q.trim().toLowerCase()
  const srcNames = new Set((src || []).map((o) => o.label))
  // 후보: 계정 사용자 + 이 컬럼에 이미 쓰인 이름(계정 없는 사람, 후보 워크스페이스에 있는 이름은 위에서 보여 주므로 제외)
  const extra = options.map(([o]) => o).flatMap(splitPeople).filter((n) => !byName.has(n) && !srcNames.has(n))
  const cands = [...users.map((u) => ({ name: u.name, user: u, sub: [u.department, u.username].filter(Boolean).join(' · ') })),
    ...[...new Set(extra)].map((n) => ({ name: n, user: nameUser(n), sub: '계정 없음' }))]
  const shown = (ql ? cands.filter((c) => c.name.toLowerCase().includes(ql) || (c.sub || '').toLowerCase().includes(ql)) : cands).slice(0, 80)
  const exact = ql && (cands.some((c) => c.name.toLowerCase() === ql) || (src || []).some((o) => o.label.toLowerCase() === ql))
  const canAdd = ql && !exact
  const addToSource = async () => {
    setAdding(true); setErr(null)
    try {
      const o = await api.post(`/api/columns/${col.id}/options`, { name: q.trim() })
      save([...names, o.label]); setQ('')
    } catch (e) { setErr(e.message) } finally { setAdding(false) }
  }
  const row = (key, name, user, sub) => {
    const on = names.includes(name)
    return (
      <button key={key} className={`menu-row ${on ? 'active' : ''}`} onClick={() => toggle(name)}>
        <span className={`check-box ${on ? 'on' : ''}`}>{on && <Icon name="check" size={11} />}</span>
        <Avatar user={user} size={20} />
        <span className="grow ellipsis">{name}{sub && <span className="muted small"> · {sub}</span>}</span>
      </button>
    )
  }
  const renderPop = () => pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={340}>
          <div className="menu">
            <div className="filter-search" style={{ margin: '0 6px 6px' }}>
              <Icon name="search" size={13} />
              <input autoFocus placeholder={source ? `${source.name} · 사용자 검색` : '이름·부서·아이디 검색'} value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }}
                onKeyDown={(e) => { if (e.key === 'Enter' && canAdd) { e.preventDefault(); if (source) addToSource(); else { save([...names, q.trim()]); setQ('') } } }} />
            </div>
            <div style={{ maxHeight: '20rem', overflowY: 'auto' }}>
              {source && <>
                <div className="menu-title">{source.name}</div>
                {src == null && <div className="menu-empty">불러오는 중…</div>}
                {src?.map((o) => row(`s${o.id}`, o.label, nameUser(o.label), o.sub))}
                {src && src.length === 0 && <div className="menu-empty">{ql ? '일치하는 항목이 없습니다.' : '항목이 없습니다.'}</div>}
                <div className="menu-title">등록된 사용자</div>
              </>}
              {shown.map((c) => row(`${c.name}|${c.sub}`, c.name, c.user, c.sub))}
              {shown.length === 0 && <div className="menu-empty">결과가 없습니다.</div>}
            </div>
            {canAdd && <>
              <div className="menu-sep" />
              {source && <button className="menu-row rel-new" disabled={adding} onClick={addToSource}><Icon name="plus" size={14} /> <span className="grow ellipsis">'{q.trim()}' {source.name}에 새로 추가</span></button>}
              <button className="menu-row" onClick={() => { save([...names, q.trim()]); setQ('') }}><Icon name="plus" size={13} /> '{q.trim()}' 이름만 입력</button>
            </>}
            {err && <div className="form-error" style={{ margin: '4px 6px' }}>{err}</div>}
          </div>
        </Popover>
  )
  if (compact) return (
    <>
      <button type="button" className="cell-edit" onClick={(e) => { setQ(''); setSrc(null); setErr(null); pop.open(e) }} title="눌러서 선택">
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
      <button className="rel-add" onClick={(e) => { setQ(''); setSrc(null); setErr(null); pop.open(e) }}><Icon name="search" size={12} /> {names.length ? '추가' : '사람 선택'}</button>
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
    case 'PHONE':
      return <input type="tel" inputMode="tel" placeholder="010-0000-0000" className="input input-sm field-input" {...common} />
    default:
      return <input className="input input-sm field-input" {...common} />
  }
}
