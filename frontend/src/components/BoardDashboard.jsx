import { itemNoun, josa, itemTitle } from './Fields.jsx'
import React from 'react'
import { Avatar } from './common.jsx'
import { StatusIcon } from './icons.jsx'
import { KpiCard } from './Home.jsx'
import { StatusBattery } from './cells.jsx'
import { isOverdue, shortDate, todayStr, addDays } from '../util.js'
import { slaOf, fmtHours, fmtRemain } from '../sla.js'
import { SlaBadge } from './cells.jsx'

export default function BoardDashboard({ board, items, statusById, ops, dutyIndex }) {
  const noun = itemNoun(board)
  const total = items.length
  const done = items.filter((i) => statusById[i.statusId]?.done).length
  const overdue = items.filter((i) => isOverdue(i, statusById[i.statusId]))
  const today = todayStr()
  const soonLimit = addDays(today, 7)
  const dueSoon = items.filter((i) => i.dueDate && i.dueDate >= today && i.dueDate <= soonLimit && !statusById[i.statusId]?.done)
    .sort((a, b) => a.dueDate.localeCompare(b.dueDate))
  const pct = total ? Math.floor((done / total) * 100) : 0

  // ---- v3.0 유지보수 지표 ----
  const typeById = Object.fromEntries((board.types || []).map((t) => [t.id, t]))
  const hasSla = false // SLA 는 화면에서 제거됨
  const slaRows = items.map((i) => ({ i, s: slaOf(i, typeById[i.typeId], board) })).filter((x) => x.s)
  const slaOpen = slaRows.filter((x) => !x.s.done && x.s.state !== 'met')
  const slaBreach = slaOpen.filter((x) => x.s.state === 'breach')
  const slaWarn = slaOpen.filter((x) => x.s.state === 'warn')
  const slaDone = slaRows.filter((x) => x.s.done)
  const slaMet = slaDone.filter((x) => x.s.state === 'met').length
  const slaRate = slaDone.length ? Math.floor((slaMet / slaDone.length) * 100) : null
  const resolved = items.filter((i) => i.resolvedAt)
  const avgResolveH = resolved.length ? resolved.reduce((a, i) => a + (new Date(i.resolvedAt) - new Date(i.createdAt)) / 3600000, 0) / resolved.length : null
  const responded = items.filter((i) => i.respondedAt)
  const avgRespondH = responded.length ? responded.reduce((a, i) => a + (new Date(i.respondedAt) - new Date(i.createdAt)) / 3600000, 0) / responded.length : null
  const spentTotal = items.reduce((a, i) => a + Number(i.spentHours || 0), 0)
  const estTotal = items.reduce((a, i) => a + Number(i.estimateHours || 0), 0)
  // 월별 접수·처리 추이 (최근 12개월)
  const months = []
  const now = new Date()
  for (let k = 11; k >= 0; k--) { const d = new Date(now.getFullYear(), now.getMonth() - k, 1); months.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`) }
  const trend = months.map((m) => ({ m, created: items.filter((i) => (i.createdAt || '').startsWith(m)).length, resolved: items.filter((i) => (i.resolvedAt || '').startsWith(m)).length }))
  const trendMax = Math.max(1, ...trend.map((t) => Math.max(t.created, t.resolved)))

  // 담당자별 업무량 (상태별 누적)
  const byPerson = new Map()
  for (const i of items) {
    const key = i.assignee ? i.assignee.id : 0
    if (!byPerson.has(key)) byPerson.set(key, { user: i.assignee, items: [] })
    byPerson.get(key).items.push(i)
  }
  const people = [...byPerson.values()].sort((a, b) => b.items.length - a.items.length)
  const maxLoad = Math.max(1, ...people.map((p) => p.items.length))
  const maxStatus = Math.max(1, ...board.statuses.map((s) => items.filter((i) => i.statusId === s.id).length))

  return (
    <div className="dash">
      <div className="kpi-row">
        <KpiCard tone="primary" icon="list" label={`전체 ${noun}`} value={total} />
        <KpiCard tone="success" icon="check" label="완료율" value={`${pct}%`} sub={`${done} / ${total} 완료`} />
        <KpiCard tone="info" icon="timeline" label="진행 중" value={total - done} />
        <KpiCard tone="danger" icon="calendar" label="마감 지남" value={overdue.length} sub={overdue.length ? '확인 필요' : '지연 없음'} />
      </div>
      {hasSla && (
        <div className="kpi-row">
          <KpiCard tone={slaBreach.length ? 'danger' : 'success'} icon="alert" label="SLA 초과 (진행 중)" value={slaBreach.length} sub={slaWarn.length ? `4시간 내 마감 ${slaWarn.length}건` : '임박 없음'} />
          <KpiCard tone="primary" icon="target" label="SLA 준수율" value={slaRate == null ? '-' : `${slaRate}%`} sub={slaDone.length ? `처리 완료 ${slaDone.length}건 중 ${slaMet}건` : '완료 데이터 없음'} />
          <KpiCard tone="info" icon="clock" label="평균 응답 시간" value={avgRespondH == null ? '-' : fmtRemain(avgRespondH * 3600000)} sub={responded.length ? `${responded.length}건 기준` : '응답 기록 없음'} />
          <KpiCard tone="success" icon="check" label="평균 처리 시간" value={avgResolveH == null ? '-' : fmtRemain(avgResolveH * 3600000)} sub={resolved.length ? `${resolved.length}건 기준` : '처리 기록 없음'} />
        </div>
      )}

      <div className="dash-grid">
        <div className="panel">
          <div className="panel-head"><h3>상태별 {noun}</h3></div>
          <div className="hbars">
            {board.statuses.map((s) => {
              const n = items.filter((i) => i.statusId === s.id).length
              return (
                <div key={s.id} className="hbar-row" title={`${s.label}: ${n}개`}>
                  <span className="hbar-label">{s.label}</span>
                  <span className="hbar-track"><span className="hbar" style={{ width: `${(n / maxStatus) * 100}%`, background: s.color }} /></span>
                  <span className="hbar-value">{n}</span>
                </div>
              )
            })}
          </div>
        </div>

        <div className="panel">
          <div className="panel-head"><h3>담당자별 {noun} 수</h3></div>
          <div className="legend">
            {board.statuses.map((s) => <span key={s.id}><i style={{ background: s.color }} />{s.label}</span>)}
          </div>
          <div className="hbars">
            {people.map(({ user, items: list }) => (
              <div key={user ? user.id : 0} className="hbar-row">
                <span className="hbar-label row gap-sm"><Avatar user={user} size={20} /> <span className="ellipsis">{user ? user.name : '미지정'}</span></span>
                <span className="hbar-track">
                  <span className="stack" style={{ width: `${(list.length / maxLoad) * 100}%` }}>
                    {board.statuses.map((s) => {
                      const n = list.filter((i) => i.statusId === s.id).length
                      return n ? <span key={s.id} style={{ flex: n, background: s.color }} title={`${user ? user.name : '미지정'} · ${s.label}: ${n}개`} /> : null
                    })}
                    {list.some((i) => i.statusId == null) && <span style={{ flex: list.filter((i) => i.statusId == null).length, background: 'var(--surface-3)' }} title="상태 없음" />}
                  </span>
                </span>
                <span className="hbar-value">{list.length}</span>
              </div>
            ))}
            {people.length === 0 && <p className="muted small">{noun}{josa(noun, '이/가')} 없습니다.</p>}
          </div>
        </div>

        <div className="panel">
          <div className="panel-head"><h3>월별 접수·처리 추이</h3><span className="muted small">최근 12개월 · 진한 막대 = 접수, 연한 막대 = 처리 완료</span></div>
          <div className="trend">
            {trend.map((t) => (
              <div key={t.m} className="trend-col" title={`${t.m}: 접수 ${t.created} · 처리 ${t.resolved}`}>
                <div className="trend-bars">
                  <span className="trend-bar" style={{ height: `${(t.created / trendMax) * 100}%` }} />
                  <span className="trend-bar done" style={{ height: `${(t.resolved / trendMax) * 100}%` }} />
                </div>
                <span className="trend-label">{t.m.slice(5)}월</span>
              </div>
            ))}
          </div>
        </div>

        {hasSla && slaOpen.length > 0 && (
          <div className="panel">
            <div className="panel-head"><h3 className={slaBreach.length ? 'text-danger' : ''}>SLA 확인 필요</h3><span className="muted small">초과 → 임박 순</span></div>
            <div className="mini-list">
              {[...slaBreach, ...slaWarn, ...slaOpen.filter((x) => x.s.state === 'ok')].slice(0, 12).map(({ i }) => (
                <button key={i.id} className="mini-item" onClick={() => ops.openItem(i.id)}>
                  <StatusIcon status={statusById[i.statusId]} statuses={board.statuses} />
                  <span className="grow ellipsis">{i.name}</span>
                  <Avatar user={i.assignee} size={20} />
                  <SlaBadge item={i} board={board} />
                </button>
              ))}
            </div>
          </div>
        )}

        {(spentTotal > 0 || estTotal > 0) && (
          <div className="panel">
            <div className="panel-head"><h3>시간: 예상 대비 소요</h3><span className="muted small">예상 {fmtHours(estTotal)} · 소요 {fmtHours(spentTotal)}</span></div>
            <div className="hbars">
              {people.map(({ user, items: list }) => {
                const sp = list.reduce((a, i) => a + Number(i.spentHours || 0), 0)
                const es = list.reduce((a, i) => a + Number(i.estimateHours || 0), 0)
                if (!sp && !es) return null
                const mx = Math.max(1, ...people.map((p) => p.items.reduce((a, i) => a + Math.max(Number(i.spentHours || 0), Number(i.estimateHours || 0)), 0)))
                return (
                  <div key={user ? user.id : 0} className="hbar-row" title={`예상 ${fmtHours(es)} / 소요 ${fmtHours(sp)}`}>
                    <span className="hbar-label row gap-sm"><Avatar user={user} size={20} /> <span className="ellipsis">{user ? user.name : '미지정'}</span></span>
                    <span className="hbar-track hbar-two">
                      <span className="hbar" style={{ width: `${(es / mx) * 100}%`, background: 'var(--surface-3)' }} />
                      <span className="hbar" style={{ width: `${(sp / mx) * 100}%`, background: sp > es && es ? 'var(--danger)' : 'var(--accent)' }} />
                    </span>
                    <span className="hbar-value nowrap">{fmtHours(sp)}{es ? <span className="muted"> / {fmtHours(es)}</span> : null}</span>
                  </div>
                )
              })}
            </div>
          </div>
        )}


        <div className="panel">
          <div className="panel-head"><h3>그룹별 진행률</h3></div>
          {board.groups.map((g) => {
            const list = items.filter((i) => i.groupId === g.id)
            const d = list.filter((i) => statusById[i.statusId]?.done).length
            return (
              <div key={g.id} className="group-progress">
                <div className="row gap-sm"><span className="group-dot" style={{ background: g.color }} /><span className="grow ellipsis">{g.name}</span>
                  <span className="muted small">{d}/{list.length} · {list.length ? Math.round((d / list.length) * 100) : 0}%</span></div>
                <StatusBattery items={list} statuses={board.statuses} height={6} />
              </div>
            )
          })}
        </div>

        <div className="panel">
          <div className="panel-head"><h3>마감 임박 (7일 이내)</h3></div>
          <ItemList list={dueSoon} statuses={board.statuses} statusById={statusById} ops={ops} empty={`7일 이내 마감 ${noun}${josa(noun, '이/가')} 없습니다.`} />
          {overdue.length > 0 && <>
            <div className="panel-head"><h3 className="text-danger">마감 지남</h3></div>
            <ItemList list={overdue} statuses={board.statuses} statusById={statusById} ops={ops} />
          </>}
        </div>
      </div>
    </div>
  )
}

export function Kpi({ label, value, sub, tone }) {
  return (
    <div className={`stat ${tone ? `stat-${tone}` : ''}`}>
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}{sub && <span> · {sub}</span>}</div>
    </div>
  )
}

function ItemList({ list, statuses, statusById, ops, empty }) {
  if (!list.length) return <p className="muted small">{empty}</p>
  return (
    <div className="mini-list">
      {list.slice(0, 10).map((i) => {
        const s = statusById[i.statusId]
        return (
          <button key={i.id} className="mini-item" onClick={() => ops.openItem(i.id)}>
            <StatusIcon status={s} statuses={statuses} />
            <span className="grow ellipsis">{i.name}</span>
            <Avatar user={i.assignee} size={20} />
            <span className={`small ${isOverdue(i, s) ? 'text-danger' : 'muted'}`}>{shortDate(i.dueDate)}</span>
          </button>
        )
      })}
    </div>
  )
}

/** 최상위 담당업무별 업무 수(하위 포함)와 완료율 */
function DutyPanel({ items, index, statusById }) {
  const roots = index.children.get(null) || []
  const rows = roots.map((r) => {
    const scope = index.descendants(r.id)
    const list = items.filter((i) => scope.has(i.dutyId))
    return { r, n: list.length, done: list.filter((i) => statusById[i.statusId]?.done).length }
  })
  const none = items.filter((i) => i.dutyId == null).length
  const max = Math.max(1, ...rows.map((x) => x.n), none)
  return (
    <div className="panel">
      <div className="panel-head"><h3>담당업무별 업무</h3><span className="muted small">하위 포함 · 진한 부분 = 완료</span></div>
      <div className="hbars">
        {rows.map(({ r, n, done }) => (
          <div key={r.id} className="hbar-row" title={`${r.name}: ${n}개 중 ${done}개 완료`}>
            <span className="hbar-label ellipsis">{r.name}</span>
            <span className="hbar-track">
              <span className="stack" style={{ width: `${(n / max) * 100}%` }}>
                {done > 0 && <span style={{ flex: done, background: 'var(--accent)' }} />}
                {n - done > 0 && <span style={{ flex: n - done, background: 'color-mix(in srgb, var(--accent) 30%, var(--surface))' }} />}
              </span>
            </span>
            <span className="hbar-value">{n}</span>
          </div>
        ))}
        {none > 0 && (
          <div className="hbar-row" title={`담당업무 없음: ${none}개`}>
            <span className="hbar-label muted">미지정</span>
            <span className="hbar-track"><span className="hbar" style={{ width: `${(none / max) * 100}%`, background: 'var(--surface-3)' }} /></span>
            <span className="hbar-value">{none}</span>
          </div>
        )}
      </div>
    </div>
  )
}
