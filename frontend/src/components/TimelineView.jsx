import React, { useEffect, useMemo, useRef, useState } from 'react'
import { Avatar } from './common.jsx'
import { StatusIcon } from './icons.jsx'
import { addDays, diffDays, parseDate, todayStr, fmtDate, sc } from '../util.js'

const ZOOMS = { day: 36, week: 14 }
const WD = ['일', '월', '화', '수', '목', '금', '토']

function mondayOf(s) {
  const d = parseDate(s)
  const wd = (d.getDay() + 6) % 7
  d.setDate(d.getDate() - wd)
  return fmtDate(d)
}

export default function TimelineView({ board, items, statusById, ops }) {
  const [zoom, setZoom] = useState('day')
  const [drag, setDrag] = useState(null) // { id, mode, x0, start, due, dStart, dDue }
  const scrollRef = useRef(null)
  const dayW = sc(ZOOMS[zoom])
  const today = todayStr()

  const { from, days } = useMemo(() => {
    let min = addDays(today, -14), max = addDays(today, 42)
    for (const i of items) {
      for (const d of [i.startDate, i.dueDate]) {
        if (!d) continue
        if (d < min) min = addDays(d, -7)
        if (d > max) max = addDays(d, 14)
      }
    }
    const f = mondayOf(min)
    return { from: f, days: Math.ceil((diffDays(f, max) + 1) / 7) * 7 }
  }, [items, today])

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollLeft = Math.max(0, diffDays(from, today) * dayW - 200)
  }, [zoom]) // eslint-disable-line

  // 드래그 처리
  useEffect(() => {
    if (!drag) return
    const onMove = (e) => {
      const delta = Math.round((e.clientX - drag.x0) / dayW)
      setDrag((d) => ({ ...d, delta }))
    }
    const onUp = () => {
      setDrag((d) => {
        if (d && d.delta) {
          const { start, due } = applyDrag(d)
          ops.updateItem(d.id, { startDate: start, dueDate: due }).catch(() => {})
        } else if (d && !d.moved) {
          ops.openItem(d.id)
        }
        return null
      })
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
    return () => { window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp) }
  }, [drag?.id, dayW]) // eslint-disable-line

  const width = days * dayW
  const dayList = Array.from({ length: days }, (_, i) => addDays(from, i))
  const months = []
  dayList.forEach((d, i) => {
    const key = d.slice(0, 7)
    if (!months.length || months[months.length - 1].key !== key) months.push({ key, start: i, len: 1 })
    else months[months.length - 1].len++
  })
  const todayX = diffDays(from, today) * dayW
  const trackBg = {
    width,
    backgroundImage: `repeating-linear-gradient(90deg, transparent 0, transparent ${dayW * 5}px, var(--weekend) ${dayW * 5}px, var(--weekend) ${dayW * 7}px)`,
    backgroundSize: `${dayW * 7}px 100%`,
  }

  return (
    <div className="timeline">
      <div className="timeline-tools">
        <div className="seg">
          <button className={zoom === 'day' ? 'active' : ''} onClick={() => setZoom('day')}>일</button>
          <button className={zoom === 'week' ? 'active' : ''} onClick={() => setZoom('week')}>주</button>
        </div>
        <button className="chip-btn" onClick={() => { if (scrollRef.current) scrollRef.current.scrollLeft = Math.max(0, todayX - 200) }}>오늘</button>
        <span className="hint hide-sm">막대를 끌어 일정 이동 · 끝을 끌어 기간 조정 · 빈 칸 클릭으로 일정 지정</span>
      </div>
      <div className="timeline-scroll" ref={scrollRef}>
        <div className="tl-row tl-head">
          <div className="tl-left tl-left-head">업무</div>
          <div className="tl-track" style={{ width }}>
            <div className="tl-months">
              {months.map((m) => (
                <div key={m.key} className="tl-month" style={{ left: m.start * dayW, width: m.len * dayW }}>
                  {Number(m.key.slice(5))}월 {m.key.slice(0, 4) !== today.slice(0, 4) ? `(${m.key.slice(0, 4)})` : ''}
                </div>
              ))}
            </div>
            <div className="tl-days">
              {dayList.map((d, i) => {
                const wd = parseDate(d).getDay()
                const show = zoom === 'day' || wd === 1
                return show ? (
                  <div key={d} className={`tl-day ${d === today ? 'is-today' : ''} ${wd === 0 || wd === 6 ? 'weekend' : ''}`} style={{ left: i * dayW, width: zoom === 'day' ? dayW : dayW * 7 }}>
                    {zoom === 'day' ? <>{Number(d.slice(8))}<small>{WD[wd]}</small></> : `${Number(d.slice(5, 7))}/${Number(d.slice(8))}`}
                  </div>
                ) : null
              })}
            </div>
          </div>
        </div>
        {board.groups.map((g) => {
          const gItems = items.filter((i) => i.groupId === g.id)
          if (!gItems.length) return null
          return (
            <React.Fragment key={g.id}>
              <div className="tl-row tl-group">
                <div className="tl-left"><span className="group-dot" style={{ background: g.color }} /> {g.name} <span className="lgroup-count">{gItems.length}</span></div>
                <div className="tl-track" style={trackBg} />
              </div>
              {gItems.map((it) => {
                const s = statusById[it.statusId]
                const live = drag && drag.id === it.id && drag.delta ? applyDrag(drag) : { start: it.startDate, due: it.dueDate }
                const start = live.start || live.due
                const due = live.due || live.start
                return (
                  <div key={it.id} className="tl-row">
                    <div className="tl-left">
                      <StatusIcon status={s} statuses={board.statuses} />
                      <button className="item-name ellipsis" onClick={() => ops.openItem(it.id)}>{it.name}</button>
                      <Avatar user={it.assignee} size={22} />
                    </div>
                    <div className="tl-track" style={trackBg}
                      onClick={(e) => {
                        if (start) return
                        const x = e.clientX - e.currentTarget.getBoundingClientRect().left
                        const d = addDays(from, Math.floor(x / dayW))
                        ops.updateItem(it.id, { startDate: d, dueDate: addDays(d, 2) }).catch(() => {})
                      }}>
                      {todayX >= 0 && todayX < width && <div className="tl-today" style={{ left: todayX + dayW / 2 }} />}
                      {start ? (
                        <div className={`tl-bar ${drag?.id === it.id ? 'dragging' : ''}`}
                          style={{ left: diffDays(from, start) * dayW + 2, width: Math.max(1, diffDays(start, due) + 1) * dayW - 4, '--bar': s ? s.color : '#8B8D98' }}
                          title={`${it.name}\n${start} ~ ${due}`}
                          onMouseDown={(e) => { e.preventDefault(); setDrag({ id: it.id, mode: 'move', x0: e.clientX, start: it.startDate, due: it.dueDate, delta: 0 }) }}>
                          <span className="tl-handle left" onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); setDrag({ id: it.id, mode: 'start', x0: e.clientX, start: it.startDate || it.dueDate, due: it.dueDate || it.startDate, delta: 0, moved: true }) }} />
                          <span className="tl-bar-label">{it.name}</span>
                          <span className="tl-handle right" onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); setDrag({ id: it.id, mode: 'end', x0: e.clientX, start: it.startDate || it.dueDate, due: it.dueDate || it.startDate, delta: 0, moved: true }) }} />
                        </div>
                      ) : <span className="tl-nodate">＋ 클릭해서 일정 지정</span>}
                    </div>
                  </div>
                )
              })}
            </React.Fragment>
          )
        })}
        {items.length === 0 && <div className="muted" style={{ padding: 24 }}>표시할 업무가 없습니다.</div>}
      </div>
    </div>
  )
}

function applyDrag(d) {
  const s0 = d.start || d.due
  const e0 = d.due || d.start
  if (d.mode === 'move') return { start: addDays(s0, d.delta), due: addDays(e0, d.delta) }
  if (d.mode === 'start') {
    const ns = addDays(s0, d.delta)
    return { start: ns > e0 ? e0 : ns, due: e0 }
  }
  const ne = addDays(e0, d.delta)
  return { start: s0, due: ne < s0 ? s0 : ne }
}
