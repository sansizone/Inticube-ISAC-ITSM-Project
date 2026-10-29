import { itemNoun, josa, itemTitle } from './Fields.jsx'
import React, { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { api } from '../api.js'
import { useApp } from '../App.jsx'
import { Avatar, Spinner, useToast, useConfirm, Popover, usePopover } from './common.jsx'
import { StatusChip, AssigneeChip, PriorityChip, DateChip, DutyChip, TypeChip, VersionChip, SlaBadge, ProgressCell, HoursCell, RecurChip } from './cells.jsx'
import { itemKey, fmtHours, linkLabel, LINK_TYPES, slaOf } from '../sla.js'
import { buildDutyIndex } from '../dutyTree.js'
import { Icon } from './icons.jsx'
import { Markdown, MarkdownEditor, CommentEditBox } from './markdown.jsx'
import { relTime, fmtSize, isOverdue, fmtDateTime } from '../util.js'
import { FieldEditor, columnsFor, distinctValues, nameUser, rememberRefs, builtinLabel } from './Fields.jsx'

/** 업무 상세 패널 (오른쪽 슬라이드) */
export default function ItemDrawer({ itemId, board, statusById, ops, onClose }) {
  const noun = itemNoun(board)
  const { me, users, mentionNames } = useApp()
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [detail, setDetail] = useState(null)
  const [error, setError] = useState(null)
  const [name, setName] = useState('')
  const [editingDesc, setEditingDesc] = useState(false)
  const [desc, setDesc] = useState('')
  const titleRef = useRef(null)

  const [backlinks, setBacklinks] = useState([])
  const reload = () => api.get(`/api/items/${itemId}`).then((d) => { rememberRefs(d); setDetail(d); return d })
  useEffect(() => {
    reload().then((d) => { setName(d.item.name); setDesc(d.description || '') }).catch((e) => setError(e.message))
    api.get(`/api/items/${itemId}/backlinks`).then(setBacklinks).catch(() => setBacklinks([]))
  }, [itemId]) // eslint-disable-line
  const L = (k) => builtinLabel(board, k)
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape' && !document.querySelector('.popover, .modal') && !editingDesc) onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose, editingDesc])
  useEffect(() => {
    const el = titleRef.current
    if (el) { el.style.height = 'auto'; el.style.height = `${el.scrollHeight}px` }
  }, [name, detail])

  const dutyIndex = React.useMemo(() => buildDutyIndex(board.duties || []), [board.duties])
  const boardItem = board.items.find((i) => i.id === itemId)
  const item = detail ? { ...detail.item, ...(boardItem || {}) } : null

  const update = async (patch) => {
    try { await ops.updateItem(itemId, patch); await reload() } catch { /* ops 에서 토스트 */ }
  }
  const saveName = () => {
    const t = name.trim()
    if (t && t !== item.name) update({ name: t }); else setName(item.name)
  }
  const saveDesc = async () => {
    try {
      await api.patch(`/api/items/${itemId}`, { description: desc })
      await reload(); setEditingDesc(false)
    } catch (e) { toast(e.message, 'error') }
  }

  const group = item && board.groups.find((g) => g.id === item.groupId)
  const parent = item?.parentId ? board.items.find((i) => i.id === item.parentId) : null
  const subs = item && !item.parentId ? board.items.filter((i) => i.parentId === item.id).sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id) : []
  const fieldCols = item ? columnsFor(board, item.parentId ? 'SUBITEM' : 'ITEM') : []
  const values = { ...(boardItem?.values || {}), ...(detail?.values || {}) }
  const sorted = detail ? [...detail.updates].sort((a, b) => a.createdAt.localeCompare(b.createdAt)) : []
  const replies = {}
  for (const u of sorted) if (u.parentId) (replies[u.parentId] ||= []).push(u)
  const activity = sorted.filter((u) => !u.parentId || !sorted.some((x) => x.id === u.parentId))
  const [replyTo, setReplyTo] = useState(null)
  const [editingId, setEditingId] = useState(null)
  const saveField = async (col, v) => {
    try {
      await ops.updateItem(itemId, { values: { [col.id]: v } })
      const d = await reload(); setDetail(d)
    } catch { /* ops 에서 토스트 */ }
  }
  const onPosted = async () => { const d = await reload(); ops.patchLocal(itemId, { commentCount: d.updates.filter((x) => !x.system).length }) }
  const comment = (u, isReply) => (
    <div key={u.id} className={`act-comment ${isReply ? 'act-reply' : ''}`}>
      <div className="act-comment-head">
        <Avatar user={authorOf(u)} size={22} /><b>{authorOf(u).name}</b>
        <span className="act-time" title={fmtDateTime(u.createdAt)}>{fmtDateTime(u.createdAt)}</span>
        {u.updatedAt && <span className="edited-mark" title={`수정: ${fmtDateTime(u.updatedAt)}`}>(수정됨)</span>}
        <span style={{ marginLeft: 'auto' }} />
        {!isReply && <button className="link small" onClick={() => setReplyTo(replyTo === u.id ? null : u.id)}>답글</button>}
        {u.author?.id === me.id && editingId !== u.id && <button className="link small" onClick={() => setEditingId(u.id)}>수정</button>}
        {(u.author?.id === me.id || me.role === 'ADMIN') && (
          <button className="icon-btn icon-btn-sm" aria-label="댓글 삭제" onClick={async () => {
            if (!(await confirm(isReply ? '이 답글을 삭제할까요?' : '이 댓글을 삭제할까요? 답글도 함께 삭제됩니다.', { okText: '삭제' }))) return
            try { await api.del(`/api/updates/${u.id}`); await onPosted() } catch (e) { toast(e.message, 'error') }
          }}><Icon name="trash" size={13} /></button>
        )}
      </div>
      {editingId === u.id
        ? <CommentEditBox initial={u.content} users={users} mentionNames={mentionNames} onCancel={() => setEditingId(null)}
            onSave={async (text) => {
              try { await api.put(`/api/updates/${u.id}`, { content: text }); setEditingId(null); await onPosted() } catch (e) { toast(e.message, 'error') }
            }} />
        : <Markdown text={u.content} mentionNames={mentionNames} className="act-body" />}
      {!isReply && (replies[u.id] || []).map((r) => comment(r, true))}
      {!isReply && replyTo === u.id && (
        <div className="act-reply"><CommentBox itemId={itemId} parentId={u.id} users={users} mentionNames={mentionNames} toast={toast} startOpen
          onCancel={() => setReplyTo(null)} onPosted={async () => { setReplyTo(null); await onPosted() }} /></div>
      )}
    </div>
  )

  return createPortal(
    <div className="drawer-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <aside className="drawer" role="dialog" aria-label={`${noun} 상세`}>
        {error ? <div className="drawer-scroll"><div className="form-error">{error}</div><button className="btn" onClick={onClose}>닫기</button></div>
          : !item ? <div className="center-screen"><Spinner /></div> : (
            <>
              <div className="drawer-head">
                <span className="crumb">{board.name}</span><span className="crumb-sep">/</span>
                <span className="crumb"><span className="group-dot" style={{ background: group?.color }} /> {group?.name}</span>
                {parent && <><span className="crumb-sep">/</span><button className="crumb crumb-link" onClick={() => ops.openItem(parent.id)} title={parent.name}>#{parent.id} {parent.name.length > 18 ? `${parent.name.slice(0, 18)}…` : parent.name}</button></>}
                <span className="crumb-sep">/</span><span className="crumb crumb-current" title={`#${item.id} · 키를 눌러 복사`} role="button" tabIndex={0}
                  onClick={() => navigator.clipboard?.writeText(itemKey(board, item)).then(() => toast('키를 복사했습니다.'))}>{itemKey(board, item)}</span>
                <span className="grow" />
                <button className={`btn btn-sm ${detail.watching ? 'btn-on' : ''}`} title={detail.watching ? '감시 해제' : `이 ${noun}의 변경을 알림으로 받기`} onClick={async () => {
                  try { const w = await (detail.watching ? api.del(`/api/items/${itemId}/watch`) : api.post(`/api/items/${itemId}/watch`)); setDetail({ ...detail, watchers: w, watching: !detail.watching }) } catch (e) { toast(e.message, 'error') }
                }}><Icon name="eye" size={13} /> {detail.watching ? '감시 중' : '감시'}{detail.watchers.length > 0 && <span className="count">{detail.watchers.length}</span>}</button>
                {!item.parentId && boardItem && <button className="icon-btn" title="다른 워크스페이스로 이동" aria-label="다른 워크스페이스로 이동" onClick={() => ops.moveItem(itemId)}><Icon name="move" /></button>}
                {ops.canDelete(itemId) && <button className="icon-btn" title={`${noun} 삭제`} aria-label={`${noun} 삭제`} onClick={async () => { if (await ops.deleteItem(itemId)) onClose() }}><Icon name="trash" /></button>}
                <button className="icon-btn" onClick={onClose} aria-label="닫기"><Icon name="x" /></button>
              </div>

              <div className="drawer-scroll">
                <textarea ref={titleRef} className="drawer-title" value={name} rows={1} maxLength={300}
                  onChange={(e) => setName(e.target.value.replace(/\n/g, ''))} onBlur={saveName}
                  onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); e.currentTarget.blur() } }} aria-label={`${noun} 이름`} />

                <div className="props">
                  <Prop label={L('status')}><StatusChip item={item} statusById={statusById} statuses={board.statuses} onChange={update} /></Prop>
                  <Prop label={L('assignee')}><AssigneeChip item={item} users={users} onChange={update} showName /></Prop>
                  <Prop label={L('priority')}><PriorityChip item={item} onChange={update} /></Prop>
                  {!item.parentId && <Prop label={L('group')}><GroupChip groups={board.groups} value={item.groupId} onChange={(id) => update({ groupId: id })} /></Prop>}
                  <Prop label={L('start')}><DateChip value={item.startDate} label={L('start')} onChange={(v) => update({ startDate: v })} /></Prop>
                  <Prop label={L('due')}><DateChip value={item.dueDate} label={L('due')} overdue={isOverdue(item, statusById[item.statusId])} onChange={(v) => update({ dueDate: v })} /></Prop>
                  <Prop label={L('created')}><span className="prop-static">{fmtDateTime(detail.createdAt)}</span></Prop>
                  {!item.parentId && <Prop label={L('version')}><VersionChip item={item} versions={board.versions} onChange={update} /></Prop>}
                  <Prop label={L('progress')}><ProgressCell item={item} onChange={update} /></Prop>
                  <Prop label={L('estimate')}><HoursCell value={item.estimateHours} onChange={(v) => update({ estimateHours: v })} /></Prop>
                  <Prop label={L('spent')}><span className="prop-static">{item.spentHours != null ? fmtHours(item.spentHours) : <span className="muted">-</span>}{item.estimateHours && item.spentHours != null ? <span className="muted small"> / {fmtHours(item.estimateHours)}</span> : null}</span></Prop>
                  {!item.parentId && <Prop label="반복"><RecurChip item={item} onChange={update} /></Prop>}
                  {item.resolvedAt && <Prop label={L('resolved')}><span className="prop-static">{fmtDateTime(item.resolvedAt)}</span></Prop>}
                </div>

                <LinksSection itemId={itemId} board={board} links={detail.links} ops={ops} toast={toast} onChanged={async () => { const d = await reload(); ops.reload?.() ; return d }} />
                <TimeSection itemId={itemId} entries={detail.timeEntries} me={me} toast={toast} confirm={confirm}
                  onChanged={async () => { const d = await reload(); ops.patchLocal(itemId, { spentHours: d.item.spentHours }) }} />

                {fieldCols.length > 0 && (
                  <section className="dsec">
                    <div className="dsec-head"><h4>{item.parentId ? `하위 ${noun} 필드` : '필드'}</h4></div>
                    <div className="props fields">
                      {fieldCols.map((c) => (
                        <Prop key={c.id} label={c.name}>
                          <FieldEditor col={c} itemId={itemId} value={values[c.id] || ''} users={users} targetName={board.refBoards?.[c.refBoardId]}
                            options={c.type === 'DROPDOWN' || c.type === 'PEOPLE' ? distinctValues(board.items, c) : []}
                            onSave={(v) => saveField(c, v)} />
                        </Prop>
                      ))}
                    </div>
                  </section>
                )}

                {backlinks.length > 0 && (
                  <section className="dsec">
                    <div className="dsec-head"><h4>이 {noun}{josa(noun, '을/를')} 연결한 항목 <span className="count">{backlinks.reduce((n, b) => n + b.items.length, 0)}</span></h4></div>
                    {backlinks.map((b) => (
                      <div key={b.columnId} className="backlink">
                        <div className="backlink-head"><Icon name="link" size={12} /> {b.boardName} · {b.columnName}</div>
                        <div className="backlink-items">
                          {b.items.map((x) => <a key={x.id} className="rel-chip" href={`#/board/${b.boardId}?item=${x.id}`}><span className="ellipsis">{x.label}</span></a>)}
                        </div>
                      </div>
                    ))}
                  </section>
                )}

                {!item.parentId && (
                  <section className="dsec">
                    <div className="dsec-head"><h4>하위 {noun} {subs.length > 0 && <span className="count">{subs.length}</span>}</h4></div>
                    {subs.length > 0 && (
                      <div className="sub-list">
                        {subs.map((sb) => (
                          <div key={sb.id} className="sub-row">
                            <StatusChip item={sb} statusById={statusById} statuses={board.statuses} onChange={(p) => ops.updateItem(sb.id, p).catch(() => {})} variant="icon" />
                            <button className="grow sub-name" onClick={() => ops.openItem(sb.id)}>{sb.name}</button>
                            <DateChip value={sb.dueDate} label="마감일" compact hideEmpty onChange={(v) => ops.updateItem(sb.id, { dueDate: v }).catch(() => {})} />
                            <AssigneeChip item={sb} users={users} onChange={(p) => ops.updateItem(sb.id, p).catch(() => {})} />
                          </div>
                        ))}
                      </div>
                    )}
                    <SubAdd noun={noun} onAdd={(name) => ops.addItem(item.groupId, name, undefined, item.id)} />
                  </section>
                )}

                <section className="dsec">
                  <div className="dsec-head">
                    <h4>설명</h4>
                    {!editingDesc && <button className="link" onClick={() => setEditingDesc(true)}>{detail.description ? '편집' : '추가'}</button>}
                  </div>
                  {editingDesc ? (
                    <>
                      <MarkdownEditor value={desc} onChange={setDesc} rows={8} users={users} mentionNames={mentionNames} autoFocus
                        placeholder="배경, 완료 조건, 참고 링크 등을 적어 주세요. 마크다운을 지원합니다."
                        onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) saveDesc() }} />
                      <div className="form-actions">
                        <button className="btn btn-sm" onClick={() => { setDesc(detail.description || ''); setEditingDesc(false) }}>취소</button>
                        <button className="btn btn-sm btn-primary" onClick={saveDesc}>저장</button>
                      </div>
                    </>
                  ) : detail.description
                    ? <div className="desc-view" onDoubleClick={() => setEditingDesc(true)}><Markdown text={detail.description} mentionNames={mentionNames} /></div>
                    : <button className="desc-empty" onClick={() => setEditingDesc(true)}>설명을 추가하세요…</button>}
                </section>

                <Files itemId={itemId} me={me} files={detail.attachments} toast={toast} confirm={confirm}
                  onChanged={async () => { const d = await reload(); ops.patchLocal(itemId, { fileCount: d.attachments.length }) }} />

                <section className="dsec">
                  <div className="dsec-head"><h4>활동</h4></div>
                  <div className="activity">
                    <div className="act-log"><Avatar user={detail.createdBy} size={18} /><span><b>{detail.createdBy?.name}</b>님이 {noun}{josa(noun, '을/를')} 만들었습니다</span><span className="act-time">{relTime(detail.createdAt)}</span></div>
                    {activity.map((u) => u.system ? (
                      <div key={u.id} className="act-log">
                        <Avatar user={u.author} size={18} /><span><b>{u.author.name}</b> · {u.content}</span><span className="act-time" title={fmtDateTime(u.createdAt)}>{relTime(u.createdAt)}</span>
                      </div>
                    ) : comment(u, false))}
                  </div>
                  <CommentBox itemId={itemId} users={users} mentionNames={mentionNames} toast={toast} onPosted={onPosted} />
                </section>
              </div>
            </>
          )}
        {confirmDialog}
      </aside>
    </div>,
    document.body,
  )
}

/** 업무 관계: 차단/관련/중복/원인 */
function LinksSection({ itemId, board, links, ops, toast, onChanged }) {
  const [adding, setAdding] = useState(false)
  const [type, setType] = useState('RELATES')
  const [q, setQ] = useState('')
  const [results, setResults] = useState([])
  useEffect(() => {
    if (!adding) return
    const t = setTimeout(() => api.get(`/api/search?q=${encodeURIComponent(q || ' ')}`).then((r) => setResults(r.filter((x) => x.id !== itemId).slice(0, 20))).catch(() => setResults([])), 200)
    return () => clearTimeout(t)
  }, [q, adding, itemId])
  const add = async (target) => {
    try { await api.post(`/api/items/${itemId}/links`, { toItemId: target.id, type }); setAdding(false); setQ(''); await onChanged() } catch (e) { toast(e.message, 'error') }
  }
  const remove = async (l) => { try { await api.del(`/api/links/${l.id}`); await onChanged() } catch (e) { toast(e.message, 'error') } }
  const groups = {}
  for (const l of links || []) (groups[linkLabel(l.type, l.outgoing)] ||= []).push(l)
  return (
    <section className="dsec">
      <div className="dsec-head"><h4>관계 {links?.length > 0 && <span className="count">{links.length}</span>}</h4>{!adding && <button className="link" onClick={() => setAdding(true)}>추가</button>}</div>
      {Object.entries(groups).map(([label, list]) => (
        <div key={label} className="link-group">
          <div className="link-label">{label}</div>
          {list.map((l) => (
            <div key={l.id} className={`link-row ${l.status?.done ? 'is-done' : ''}`}>
              {l.status && <span className="group-dot" style={{ background: l.status.color }} title={l.status.label} />}
              <a className="link-key" href={`#/board/${l.boardId}?item=${l.itemId}`} title={l.boardName}>{l.key || `#${l.itemId}`}</a>
              <a className="grow ellipsis" href={`#/board/${l.boardId}?item=${l.itemId}`}>{l.name}</a>
              {l.boardId !== board.id && <span className="muted small nowrap">{l.boardName}</span>}
              <button className="icon-btn icon-btn-sm" aria-label="관계 해제" onClick={() => remove(l)}><Icon name="x" size={13} /></button>
            </div>
          ))}
        </div>
      ))}
      {!links?.length && !adding && <div className="muted small">차단·관련·중복·원인 관계로 다른 업무(다른 워크스페이스 포함)와 묶을 수 있습니다.</div>}
      {adding && (
        <div className="link-add">
          <div className="row gap-sm">
            <select className="input input-sm" style={{ width: '7rem' }} value={type} onChange={(e) => setType(e.target.value)}>
              {LINK_TYPES.map(([k, out]) => <option key={k} value={k}>{out}</option>)}
            </select>
            <div className="filter-search grow"><Icon name="search" size={13} /><input autoFocus placeholder="업무명 또는 키(REQ-12)로 찾기" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: '100%' }} /></div>
            <button className="btn btn-sm" onClick={() => { setAdding(false); setQ('') }}>취소</button>
          </div>
          <div className="link-results">
            {results.map((r) => (
              <button key={r.id} className="menu-row" onClick={() => add(r)}>
                <span className="muted small nowrap">{r.key || `#${r.id}`}</span><span className="grow ellipsis">{r.name}</span><span className="menu-meta">{r.boardName}</span>
              </button>
            ))}
            {q && results.length === 0 && <div className="menu-empty">결과가 없습니다.</div>}
          </div>
        </div>
      )}
    </section>
  )
}

/** 시간 기록 */
function TimeSection({ itemId, entries, me, toast, confirm, onChanged }) {
  const [open, setOpen] = useState(false)
  const [f, setF] = useState({ hours: '', workDate: new Date().toISOString().slice(0, 10), comment: '' })
  const total = (entries || []).reduce((a, t) => a + Number(t.hours), 0)
  const submit = async (e) => {
    e.preventDefault()
    try { await api.post(`/api/items/${itemId}/time`, { hours: Number(f.hours), workDate: f.workDate, comment: f.comment }); setF({ ...f, hours: '', comment: '' }); setOpen(false); await onChanged() } catch (err) { toast(err.message, 'error') }
  }
  const remove = async (t) => {
    if (!(await confirm('이 시간 기록을 삭제할까요?', { okText: '삭제' }))) return
    try { await api.del(`/api/time/${t.id}`); await onChanged() } catch (err) { toast(err.message, 'error') }
  }
  return (
    <section className="dsec">
      <div className="dsec-head"><h4>시간 기록 {total > 0 && <span className="count">{fmtHours(total)}</span>}</h4>{!open && <button className="link" onClick={() => setOpen(true)}>기록</button>}</div>
      {open && (
        <form className="time-form" onSubmit={submit}>
          <input className="input input-sm" style={{ width: '5rem' }} inputMode="decimal" placeholder="시간" value={f.hours} autoFocus required onChange={(e) => setF({ ...f, hours: e.target.value })} />
          <input type="date" className="input input-sm" style={{ width: '9.5rem' }} value={f.workDate} onChange={(e) => setF({ ...f, workDate: e.target.value })} />
          <input className="input input-sm grow" placeholder="한 일 (선택)" maxLength={500} value={f.comment} onChange={(e) => setF({ ...f, comment: e.target.value })} />
          <button className="btn btn-sm btn-primary" disabled={!(Number(f.hours) > 0)}>저장</button>
          <button type="button" className="btn btn-sm" onClick={() => setOpen(false)}>취소</button>
        </form>
      )}
      {(entries || []).length > 0 ? (
        <div className="time-list">
          {entries.map((t) => (
            <div key={t.id} className="time-row">
              <Avatar user={t.user} size={18} /><span className="nowrap"><b>{t.user?.name}</b></span>
              <span className="muted small nowrap">{t.workDate}</span>
              <span className="num nowrap">{fmtHours(t.hours)}</span>
              <span className="grow ellipsis muted small">{t.comment}</span>
              {(t.user?.id === me.id || me.role === 'ADMIN') && <button className="icon-btn icon-btn-sm" aria-label="삭제" onClick={() => remove(t)}><Icon name="x" size={13} /></button>}
            </div>
          ))}
        </div>
      ) : !open && <div className="muted small">작업한 시간을 기록하면 업무·담당자·버전별 소요 시간이 집계됩니다.</div>}
    </section>
  )
}

function Prop({ label, children }) {
  return <div className="prop-row"><span className="prop-label">{label}</span><div className="prop-value">{children}</div></div>
}

function GroupChip({ groups, value, onChange }) {
  const pop = usePopover()
  const g = groups.find((x) => x.id === value)
  return (
    <>
      <button className="prop" onClick={pop.open}><span className="group-dot" style={{ background: g?.color }} /><span>{g?.name}</span></button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={220}>
          <div className="menu">
            {groups.map((x) => (
              <button key={x.id} className="menu-row" onClick={() => { pop.close(); if (x.id !== value) onChange(x.id) }}>
                <span className="group-dot" style={{ background: x.color }} /><span className="grow">{x.name}</span>
                {x.id === value && <Icon name="check" size={14} className="menu-check" />}
              </button>
            ))}
          </div>
        </Popover>
      )}
    </>
  )
}

/** 가져온 댓글은 원래 작성자 이름으로 표시 (계정과 이름이 다르면 원래 이름) */
function authorOf(u) {
  if (u.authorName && (!u.author || u.author.name !== u.authorName)) return nameUser(u.authorName)
  return u.author
}

function SubAdd({ onAdd, noun }) {
  const [v, setV] = useState('')
  return (
    <div className="sub-add">
      <Icon name="plus" size={13} className="muted" />
      <input placeholder={`하위 ${noun} 추가 후 Enter`} value={v} maxLength={300} onChange={(e) => setV(e.target.value)}
        onKeyDown={async (e) => { if (e.key === 'Enter' && v.trim()) { const n = v.trim(); setV(''); await onAdd(n) } }} />
    </div>
  )
}

function CommentBox({ itemId, parentId, users, mentionNames, toast, onPosted, startOpen, onCancel }) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [focused, setFocused] = useState(!!startOpen)
  const submit = async () => {
    if (!text.trim()) return
    setBusy(true)
    try { await api.post(`/api/items/${itemId}/updates`, { content: text, parentId: parentId || null }); setText(''); setFocused(false); await onPosted() }
    catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }
  if (!focused && !text) return <button className="comment-placeholder" onClick={() => setFocused(true)}>댓글 남기기… <span className="muted">@로 동료 언급</span></button>
  return (
    <div className="comment-box">
      <MarkdownEditor value={text} onChange={setText} rows={3} users={users} mentionNames={mentionNames} autoFocus
        placeholder="댓글을 입력하세요. @이름으로 언급할 수 있습니다."
        onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) submit() }} />
      <div className="form-actions">
        <span className="hint grow">Ctrl+Enter 로 등록</span>
        <button className="btn btn-sm" onClick={() => { setText(''); setFocused(false); onCancel?.() }}>취소</button>
        <button className="btn btn-sm btn-primary" onClick={submit} disabled={busy || !text.trim()}>등록</button>
      </div>
    </div>
  )
}

export function FileList({ files, me, downloadBase, onDelete }) {
  return (
    <ul className="file-list">
      {files.map((f) => (
        <li key={f.id}>
          <span className="file-ext">{(f.originalName.split('.').pop() || '').slice(0, 4).toUpperCase()}</span>
          <a className="grow file-name" href={`${downloadBase}/${f.id}/download`} title={`${f.originalName} 다운로드`}>
            <span className="ellipsis">{f.originalName}</span>
            <span className="file-meta">{fmtSize(f.fileSize)}</span>
          </a>
          <a className="icon-btn icon-btn-sm" href={`${downloadBase}/${f.id}/download`} aria-label="다운로드"><Icon name="download" size={14} /></a>
          {onDelete && (f.uploaderId === me.id || me.role === 'ADMIN') && (
            <button className="icon-btn icon-btn-sm" aria-label="삭제" onClick={() => onDelete(f)}><Icon name="trash" size={14} /></button>
          )}
        </li>
      ))}
    </ul>
  )
}

function Files({ itemId, me, files, onChanged, toast, confirm }) {
  const [busy, setBusy] = useState(false)
  const [over, setOver] = useState(false)
  const inputRef = useRef(null)
  const upload = async (list) => {
    if (!list || !list.length) return
    setBusy(true)
    for (const f of list) {
      if (f.size > 50 * 1024 * 1024) { toast(`${f.name}: 50MB 를 초과합니다.`, 'error'); continue }
      try { await api.upload(`/api/items/${itemId}/attachments`, f) } catch (e) { toast(`${f.name}: ${e.message}`, 'error') }
    }
    setBusy(false)
    if (inputRef.current) inputRef.current.value = ''
    await onChanged()
  }
  return (
    <section className={`dsec ${over ? 'drop-over' : ''}`} onDragOver={(e) => { if (e.dataTransfer.types.includes('Files')) { e.preventDefault(); setOver(true) } }}
      onDragLeave={(e) => { if (!e.currentTarget.contains(e.relatedTarget)) setOver(false) }}
      onDrop={(e) => { if (e.dataTransfer.files.length) { e.preventDefault(); setOver(false); upload([...e.dataTransfer.files]) } }}>
      <div className="dsec-head">
        <h4>첨부파일 {files.length > 0 && <span className="count">{files.length}</span>}</h4>
        <button className="link" onClick={() => inputRef.current?.click()} disabled={busy}>{busy ? '업로드 중…' : '파일 추가'}</button>
        <input ref={inputRef} type="file" multiple hidden onChange={(e) => upload([...e.target.files])} />
      </div>
      {files.length === 0 ? <div className="drop-hint">파일을 여기로 끌어다 놓으세요 (최대 50MB)</div> : (
        <FileList files={files} me={me} downloadBase="/api/attachments" onDelete={async (f) => {
          if (!(await confirm(`'${f.originalName}' 파일을 삭제할까요?`, { okText: '삭제' }))) return
          try { await api.del(`/api/attachments/${f.id}`); await onChanged() } catch (e) { toast(e.message, 'error') }
        }} />
      )}
    </section>
  )
}
