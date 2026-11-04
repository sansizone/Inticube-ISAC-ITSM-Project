import React, { useEffect, useRef, useState } from 'react'
import { Avatar, Popover, StatusPicker, UserPicker, PriorityPicker, DutyPicker, usePopover } from './common.jsx'
import { StatusIcon, PriorityIcon, Icon } from './icons.jsx'
import { priorityOf, friendlyDate, todayStr, fmtDate, parseDate, addDays, weekday, sc } from '../util.js'
import { slaOf, fmtHours, recurLabel, recurBase, RECUR_OPTIONS } from '../sla.js'

/** 상태: 아이콘만(variant=icon) 또는 아이콘+라벨 칩 */
export function StatusChip({ item, statusById, statuses, onChange, variant = 'chip' }) {
  const pop = usePopover()
  const s = statusById[item.statusId]
  return (
    <>
      <button className={`prop ${variant === 'icon' ? 'prop-icon' : ''}`} onClick={pop.open} title={`상태: ${s ? s.label : '없음'}`} aria-label={`상태: ${s ? s.label : '없음'}`}>
        <StatusIcon status={s} statuses={statuses} />
        {variant !== 'icon' && <span>{s ? s.label : '상태 없음'}</span>}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={220}>
          <StatusPicker statuses={statuses} value={item.statusId} onPick={(id) => { pop.close(); onChange({ statusId: id }) }} />
        </Popover>
      )}
    </>
  )
}

export function AssigneeChip({ item, users, onChange, showName }) {
  const pop = usePopover()
  return (
    <>
      <button className={`prop ${showName ? '' : 'prop-icon'}`} onClick={pop.open} title={`담당자: ${item.assignee ? item.assignee.name : '없음'}`} aria-label={`담당자: ${item.assignee ? item.assignee.name : '없음'}`}>
        <Avatar user={item.assignee} size={showName ? 18 : 20} />
        {showName && <span className={item.assignee ? '' : 'muted'}>{item.assignee ? item.assignee.name : '담당자 없음'}</span>}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={250}>
          <UserPicker users={users} value={item.assignee?.id ?? null} onPick={(id) => { pop.close(); onChange({ assigneeId: id }) }} />
        </Popover>
      )}
    </>
  )
}

export function PriorityChip({ item, onChange, variant = 'chip' }) {
  const pop = usePopover()
  const p = priorityOf(item.priority)
  return (
    <>
      <button className={`prop ${variant === 'icon' ? 'prop-icon' : ''}`} onClick={pop.open} title={`우선순위: ${p ? p.label : '없음'}`} aria-label={`우선순위: ${p ? p.label : '없음'}`}>
        <PriorityIcon value={item.priority} />
        {variant !== 'icon' && <span className={p ? '' : 'muted'}>{p ? p.label : '우선순위'}</span>}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={200}>
          <PriorityPicker value={item.priority} onPick={(v) => { pop.close(); onChange({ priority: v }) }} />
        </Popover>
      )}
    </>
  )
}

/** 담당업무 칩: 목록에서는 마지막 이름, 상세에서는 전체 경로 */
export function DutyChip({ item, index, onChange, full, compact, readOnly }) {
  const pop = usePopover()
  const d = item.dutyId != null ? index.byId.get(item.dutyId) : null
  const path = d ? index.path(d.id) : ''
  if (compact && !d && !index.byId.size) return null
  return (
    <>
      <button className={`prop ${compact ? 'prop-compact prop-duty' : ''} ${d ? '' : 'is-empty'}`} onClick={readOnly ? undefined : pop.open}
        title={d ? `담당업무: ${path}` : '담당업무 지정'} aria-label={d ? `담당업무: ${path}` : '담당업무 지정'}>
        <Icon name="tree" size={13} />
        {(d || !compact) && <span className={d ? '' : 'muted'}>{d ? (full ? path : d.name) : '담당업무'}</span>}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={300}>
          <DutyPicker index={index} value={item.dutyId ?? null} onPick={(id) => { pop.close(); onChange({ dutyId: id }) }} />
        </Popover>
      )}
    </>
  )
}

/** 날짜 칩: 클릭 시 빠른 선택(오늘/내일/다음 주) + 달력 입력 */
export function DateChip({ value, onChange, label, overdue, hideEmpty, compact }) {
  const pop = usePopover()
  const today = todayStr()
  const nextMon = (() => { const d = parseDate(today); d.setDate(d.getDate() + ((8 - d.getDay()) % 7 || 7)); return fmtDate(d) })()
  if (hideEmpty && !value) return null
  return (
    <>
      <button className={`prop ${overdue ? 'prop-danger' : ''} ${compact ? 'prop-compact' : ''} ${value ? '' : 'is-empty'}`} onClick={pop.open} title={value ? `${label}: ${value}` : `${label} 지정`} aria-label={value ? `${label}: ${value}` : `${label} 지정`}>
        <Icon name="calendar" size={13} />
        {(value || !compact) && <span className={value ? '' : 'muted'}>{value ? friendlyDate(value) : label}</span>}
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={220}>
          <div className="menu">
            <div className="menu-label">{label}</div>
            {[['오늘', today], ['내일', addDays(today, 1)], ['다음 주 월요일', nextMon], ['1주 뒤', addDays(today, 7)]].map(([t, d]) => (
              <button key={t} className="menu-row" onClick={() => { onChange(d); pop.close() }}>
                <span className="grow">{t}</span><span className="menu-meta">{Number(d.slice(5, 7))}/{Number(d.slice(8))} ({weekday(d)})</span>
              </button>
            ))}
            <div className="menu-sep" />
            <div style={{ padding: '4px 6px' }}>
              <input type="date" className="input input-sm" style={{ width: '100%' }} value={value || ''} onChange={(e) => { onChange(e.target.value || null); pop.close() }} aria-label={label} />
            </div>
            {value && <button className="menu-row danger" onClick={() => { onChange(null); pop.close() }}>날짜 지우기</button>}
          </div>
        </Popover>
      )}
    </>
  )
}

export function InlineText({ value, onSave, className = '', maxLength = 300, placeholder }) {
  const [editing, setEditing] = useState(false)
  const [v, setV] = useState(value)
  const ref = useRef(null)
  useEffect(() => { if (!editing) setV(value) }, [value, editing])
  useEffect(() => { if (editing && ref.current) { ref.current.focus(); ref.current.select() } }, [editing])
  const commit = () => {
    setEditing(false)
    const t = v.trim()
    if (t && t !== value) onSave(t); else setV(value)
  }
  if (!editing) return <span className={`inline-text ${className}`} onDoubleClick={() => setEditing(true)} title="더블클릭하여 이름 변경">{value || <span className="muted">{placeholder}</span>}</span>
  return <input ref={ref} className={`input input-inline ${className}`} value={v} maxLength={maxLength}
    onChange={(e) => setV(e.target.value)} onBlur={commit}
    onKeyDown={(e) => { if (e.key === 'Enter') commit(); if (e.key === 'Escape') { setV(value); setEditing(false) } }} />
}

/** 얇은 진행 막대 (상태 색 분포) — 세그먼트 사이 2px 간격 */
export function StatusBattery({ items, statuses, height: rawHeight = 6 }) {
  const height = sc(rawHeight)
  const total = items.length
  if (!total) return <div className="battery" style={{ height }}><span style={{ flex: 1, background: 'var(--surface-3)' }} /></div>
  const parts = statuses.map((s) => ({ s, n: items.filter((i) => i.statusId === s.id).length })).filter((p) => p.n)
  const none = items.filter((i) => i.statusId == null).length
  return (
    <div className="battery" style={{ height }}>
      {parts.map(({ s, n }) => <span key={s.id} style={{ flex: n, background: s.color }} title={`${s.label}: ${n}개 (${Math.round((n / total) * 100)}%)`} />)}
      {none > 0 && <span style={{ flex: none, background: 'var(--surface-3)' }} title={`상태 없음: ${none}개`} />}
    </div>
  )
}

// ---------- v3.0 유지보수 ----------

/** 업무 유형 칩 (아이콘+이름, 색상) */
export function TypeChip({ item, types, onChange, variant = 'chip', readOnly }) {
  const pop = usePopover()
  const t = (types || []).find((x) => x.id === item.typeId)
  const inner = (
    <>
      <span className="type-dot" style={{ background: t ? t.color : 'var(--fg-4)' }}><Icon name={t?.icon || 'tag'} size={11} /></span>
      {variant !== 'icon' && <span className={t ? '' : 'muted'}>{t ? t.name : '유형 없음'}</span>}
    </>
  )
  if (readOnly) return <span className={`prop prop-static ${variant === 'icon' ? 'prop-icon' : ''}`} title={`유형: ${t ? t.name : '없음'}`}>{inner}</span>
  return (
    <>
      <button className={`prop ${variant === 'icon' ? 'prop-icon' : ''}`} onClick={pop.open} title={`유형: ${t ? t.name : '없음'}`} aria-label={`유형: ${t ? t.name : '없음'}`}>{inner}</button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={230}>
          <div className="menu">
            {(types || []).map((x) => (
              <button key={x.id} className={`menu-row ${x.id === item.typeId ? 'active' : ''}`} onClick={() => { pop.close(); onChange({ typeId: x.id }) }}>
                <span className="type-dot" style={{ background: x.color }}><Icon name={x.icon || 'tag'} size={11} /></span>
                <span className="grow">{x.name}</span>
                {(x.responseHours || x.resolveHours) && <span className="menu-meta">{x.responseHours ? `응답 ${fmtHours(x.responseHours)}` : ''}{x.responseHours && x.resolveHours ? ' · ' : ''}{x.resolveHours ? `처리 ${fmtHours(x.resolveHours)}` : ''}</span>}
              </button>
            ))}
            <div className="menu-sep" />
            <button className={`menu-row ${item.typeId == null ? 'active' : ''}`} onClick={() => { pop.close(); onChange({ typeId: null }) }}><span className="muted">유형 없음</span></button>
          </div>
        </Popover>
      )}
    </>
  )
}

/** SLA 배지: 응답/처리 목표까지 남은 시간, 초과, 준수 */
export function SlaBadge({ item, types, board, compact }) {
  const t = (types || board?.types || []).find((x) => x.id === item.typeId)
  const s = slaOf(item, t, board)
  if (!s) return compact ? null : <span className="muted">-</span>
  return (
    <span className={`sla sla-${s.state}`} title={s.title}>
      <Icon name={s.state === 'met' ? 'check' : s.state === 'breach' ? 'alert' : 'clock'} size={11} />
      {s.label}
    </span>
  )
}

/** 버전·마일스톤 선택 */
export function VersionChip({ item, versions, onChange, compact }) {
  const pop = usePopover()
  const v = (versions || []).find((x) => x.id === item.versionId)
  return (
    <>
      <button className="prop" onClick={pop.open} title={`버전: ${v ? v.name : '없음'}`}>
        <Icon name="flag" size={13} className={v ? '' : 'muted'} />
        <span className={v ? '' : 'muted'}>{v ? v.name : compact ? '' : '버전 없음'}</span>
      </button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={240}>
          <div className="menu">
            {(versions || []).filter((x) => x.status !== 'CLOSED' || x.id === item.versionId).map((x) => (
              <button key={x.id} className={`menu-row ${x.id === item.versionId ? 'active' : ''}`} onClick={() => { pop.close(); onChange({ versionId: x.id }) }}>
                <Icon name="flag" size={13} /><span className="grow ellipsis">{x.name}</span>{x.dueDate && <span className="menu-meta">{x.dueDate.slice(5)}</span>}
              </button>
            ))}
            {(versions || []).length === 0 && <div className="menu-empty">설정 › 버전에서 버전을 만드세요.</div>}
            <div className="menu-sep" />
            <button className={`menu-row ${item.versionId == null ? 'active' : ''}`} onClick={() => { pop.close(); onChange({ versionId: null }) }}><span className="muted">버전 없음</span></button>
          </div>
        </Popover>
      )}
    </>
  )
}

/** 진척률: 막대 + 클릭 시 10% 단위 선택 */
export function ProgressCell({ item, onChange, readOnly }) {
  const pop = usePopover()
  const p = item.progress
  const bar = (
    <span className="progress-cell" title={p == null ? '진척률 없음' : `진척률 ${p}%`}>
      <span className="progress-bar"><span style={{ width: `${p || 0}%` }} /></span>
      <span className={`small ${p == null ? 'muted' : ''}`}>{p == null ? '-' : `${p}%`}</span>
    </span>
  )
  if (readOnly) return bar
  return (
    <>
      <button className="prop prop-bare" onClick={pop.open}>{bar}</button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={200}>
          <div className="progress-grid">
            {[0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100].map((v) => (
              <button key={v} className={`chip-btn ${p === v ? 'on' : ''}`} onClick={() => { pop.close(); onChange({ progress: v }) }}>{v}%</button>
            ))}
            <button className="chip-btn" onClick={() => { pop.close(); onChange({ progress: null }) }}>없음</button>
          </div>
        </Popover>
      )}
    </>
  )
}

/** 시간 입력 (예상 시간) */
export function HoursCell({ value, onChange, label = '예상', readOnly }) {
  const [editing, setEditing] = useState(false)
  const [v, setV] = useState(value ?? '')
  useEffect(() => { if (!editing) setV(value ?? '') }, [value, editing])
  if (readOnly) return <span className={value == null ? 'muted' : 'num'}>{value == null ? '-' : fmtHours(value)}</span>
  if (!editing) return <button className="prop" onClick={() => setEditing(true)} title={`${label} 시간`}><Icon name="clock" size={13} className={value == null ? 'muted' : ''} /><span className={value == null ? 'muted' : ''}>{value == null ? `${label} 시간` : fmtHours(value)}</span></button>
  const commit = () => { setEditing(false); const n = v === '' ? null : Number(v); if (n !== (value ?? null) && !(Number.isNaN(n))) onChange(n) }
  return <input className="input input-sm" style={{ width: '5.5rem' }} autoFocus inputMode="decimal" placeholder="시간" value={v} onChange={(e) => setV(e.target.value)} onBlur={commit}
    onKeyDown={(e) => { if (e.key === 'Enter') commit(); if (e.key === 'Escape') { setV(value ?? ''); setEditing(false) } }} />
}

/** 반복 규칙 선택 */
export function RecurChip({ item, onChange }) {
  const pop = usePopover()
  const on = !!item.recurRule
  return (
    <>
      <button className="prop" onClick={pop.open} title="반복"><Icon name="repeat" size={13} className={on ? '' : 'muted'} /><span className={on ? '' : 'muted'}>{on ? recurLabel(item.recurRule) : '반복 없음'}</span></button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={200}>
          <div className="menu">
            {RECUR_OPTIONS.map(([k, l]) => <button key={k} className={`menu-row ${recurBase(item.recurRule) === k ? 'active' : ''}`} onClick={() => { pop.close(); onChange({ recurRule: k || null }) }}>{l}</button>)}
            <div className="menu-empty" style={{ padding: '6px 10px 2px' }}>완료 처리하면 다음 회차가 자동으로 만들어집니다.</div>
          </div>
        </Popover>
      )}
    </>
  )
}
