import React, { useState } from 'react'
import { Avatar } from './common.jsx'
import { Icon, StatusIcon, PriorityIcon } from './icons.jsx'
import { friendlyDate, isOverdue } from '../util.js'

const PAGE = 50

export default function KanbanView({ board, items, statusById, ops, dutyIndex }) {
  const [drag, setDrag] = useState(null)
  const [over, setOver] = useState(null)
  const [adding, setAdding] = useState(null)
  const [text, setText] = useState('')
  const [limit, setLimit] = useState({})
  const groupById = Object.fromEntries(board.groups.map((g) => [g.id, g]))
  const columns = [...board.statuses.map((s) => ({ key: s.id, status: s })), ...(items.some((i) => i.statusId == null) ? [{ key: 'none', status: null }] : [])]

  const submit = async (statusId) => {
    const name = text.trim()
    if (!name) { setAdding(null); return }
    setText('')
    await ops.addItem(board.groups[0].id, name, statusId ? { statusId } : undefined)
  }

  return (
    <div className="kanban">
      {columns.map(({ key, status }) => {
        const colItems = items.filter((i) => (status ? i.statusId === status.id : i.statusId == null))
        return (
          <div key={key} className={`kcol ${over === key ? 'drag-over' : ''}`}
            onDragOver={(e) => { if (drag) { e.preventDefault(); setOver(key) } }}
            onDragLeave={(e) => { if (!e.currentTarget.contains(e.relatedTarget)) setOver(null) }}
            onDrop={(e) => {
              e.preventDefault(); setOver(null)
              if (drag && drag.statusId !== (status ? status.id : null)) ops.updateItem(drag.id, { statusId: status ? status.id : null }).catch(() => {})
              setDrag(null)
            }}>
            <div className="kcol-head">
              <StatusIcon status={status} statuses={board.statuses} />
              <span className="kcol-title">{status ? status.label : '상태 없음'}</span>
              <span className="kcol-count">{colItems.length}</span>
              <span className="grow" />
              <button className="icon-btn icon-btn-sm" aria-label="업무 추가" onClick={() => { setAdding(key); setText('') }}><Icon name="plus" size={14} /></button>
            </div>
            <div className="kcards">
              {adding === key && (
                <textarea className="input kadd" autoFocus rows={2} placeholder="업무 이름 입력 후 Enter" value={text} maxLength={300}
                  onChange={(e) => setText(e.target.value)} onBlur={() => submit(status?.id)}
                  onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(status?.id) } if (e.key === 'Escape') { setText(''); setAdding(null) } }} />
              )}
              {colItems.slice(0, limit[key] || PAGE).map((it) => {
                const g = groupById[it.groupId]
                const overdue = isOverdue(it, statusById[it.statusId])
                return (
                  <div key={it.id} className={`kcard ${drag?.id === it.id ? 'dragging' : ''}`} draggable
                    onDragStart={(e) => { e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(it.id)); setDrag(it) }}
                    onDragEnd={() => { setDrag(null); setOver(null) }}
                    onClick={() => ops.openItem(it.id)} role="button" tabIndex={0}
                    onKeyDown={(e) => { if (e.key === 'Enter') ops.openItem(it.id) }}>
                    <div className="kcard-top">
                      <span className="lrow-id">#{it.id}</span>
                      {g && <span className="kcard-group"><span className="group-dot" style={{ background: g.color }} />{g.name}</span>}
                      <span className="grow" />
                      <Avatar user={it.assignee} size={20} />
                    </div>
                    <div className="kcard-title">{it.name}</div>
                    <div className="kcard-meta">
                      {it.dutyId != null && dutyIndex.byId.get(it.dutyId) && <span className="mini-chip" title={dutyIndex.path(it.dutyId)}><Icon name="tree" size={12} />{dutyIndex.byId.get(it.dutyId).name}</span>}
                      {it.priority && <span className="mini-chip"><PriorityIcon value={it.priority} size={12} /></span>}
                      {it.dueDate && <span className={`mini-chip ${overdue ? 'danger' : ''}`}><Icon name="calendar" size={12} />{friendlyDate(it.dueDate)}</span>}
                      {it.commentCount > 0 && <span className="mini-chip"><Icon name="chat" size={12} />{it.commentCount}</span>}
                      {it.fileCount > 0 && <span className="mini-chip"><Icon name="clip" size={12} />{it.fileCount}</span>}
                    </div>
                  </div>
                )
              })}
              {colItems.length > (limit[key] || PAGE) && (
                <button className="more-rows kmore" onClick={() => setLimit({ ...limit, [key]: (limit[key] || PAGE) + PAGE * 2 })}>
                  {colItems.length - (limit[key] || PAGE)}개 더 보기
                </button>
              )}
              {colItems.length === 0 && adding !== key && <div className="kempty">여기로 끌어다 놓기</div>}
            </div>
          </div>
        )
      })}
    </div>
  )
}
