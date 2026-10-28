import React, { useState } from 'react'
import { api } from '../api.js'
import { Modal, useToast, useConfirm, Empty } from './common.jsx'
import { Icon, StatusIcon } from './icons.jsx'
import { StatusChip, AssigneeChip, TypeChip } from './cells.jsx'
import { itemKey, fmtHours } from '../sla.js'
import { friendlyDate, diffDays, todayStr } from '../util.js'

/** 로드맵: 버전·마일스톤별 진행률과 업무 (Redmine 로드맵 / OpenProject 마일스톤) */
export default function RoadmapView({ board, items, statusById, ops, users, setBoard, canManage, filtering }) {
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [editing, setEditing] = useState(null)
  const [showClosed, setShowClosed] = useState(false)
  const [open, setOpen] = useState({})
  const versions = (board.versions || []).filter((v) => showClosed || v.status !== 'CLOSED')
  const byVersion = {}
  for (const i of items) (byVersion[i.versionId || 'none'] ||= []).push(i)
  const setVersions = (fn) => setBoard((b) => ({ ...b, versions: fn(b.versions) }))

  const save = async (f) => {
    try {
      if (f.id) { const v = await api.put(`/api/versions/${f.id}`, f); setVersions((vs) => vs.map((x) => (x.id === v.id ? v : x))) }
      else { const v = await api.post(`/api/boards/${board.id}/versions`, f); setVersions((vs) => [...vs, v]) }
      setEditing(null)
    } catch (e) { toast(e.message, 'error') }
  }
  const remove = async (v) => {
    const n = (byVersion[v.id] || []).length
    if (!(await confirm(`'${v.name}' 버전을 삭제할까요?${n ? `\n업무 ${n}개의 버전이 비워집니다(업무는 남습니다).` : ''}`, { okText: '삭제' }))) return
    try { await api.del(`/api/versions/${v.id}`); setVersions((vs) => vs.filter((x) => x.id !== v.id)); ops.reload() } catch (e) { toast(e.message, 'error') }
  }
  const toggleClose = async (v) => {
    try { const s = await api.put(`/api/versions/${v.id}`, { ...v, status: v.status === 'CLOSED' ? 'OPEN' : 'CLOSED' }); setVersions((vs) => vs.map((x) => (x.id === v.id ? s : x))) } catch (e) { toast(e.message, 'error') }
  }
  const card = (v, list) => {
    const done = list.filter((i) => statusById[i.statusId]?.done).length
    const pct = list.length ? Math.floor((done / list.length) * 100) : 0
    const est = list.reduce((a, i) => a + Number(i.estimateHours || 0), 0)
    const spent = list.reduce((a, i) => a + Number(i.spentHours || 0), 0)
    const late = v?.dueDate && v.status !== 'CLOSED' && v.dueDate < todayStr() && done < list.length
    const key = v ? v.id : 'none'
    const expanded = open[key] ?? (v ? v.status !== 'CLOSED' : false)
    return (
      <section key={key} className={`rm-card ${v?.status === 'CLOSED' ? 'closed' : ''} ${late ? 'late' : ''}`}>
        <div className="rm-head">
          <button className="icon-btn icon-btn-sm" onClick={() => setOpen({ ...open, [key]: !expanded })} aria-label="펼치기"><Icon name={expanded ? 'chevronDown' : 'chevronRight'} size={14} /></button>
          <Icon name="flag" size={16} className={v ? '' : 'muted'} />
          <div className="rm-title">
            <b>{v ? v.name : '버전 미지정'}</b>
            {v?.description && <span className="muted small ellipsis">{v.description}</span>}
          </div>
          <span className="rm-meta">
            {v?.startDate && <span>{friendlyDate(v.startDate)} ~ </span>}
            {v?.dueDate && <span className={late ? 'danger' : ''}>{friendlyDate(v.dueDate)}{late ? ` · ${diffDays(v.dueDate, todayStr())}일 지남` : v.status !== 'CLOSED' && v.dueDate >= todayStr() ? ` · D-${diffDays(todayStr(), v.dueDate)}` : ''}</span>}
            {v?.status === 'CLOSED' && <span className="tag">닫힘</span>}
          </span>
          {canManage && v && (
            <span className="row gap-sm">
              <button className="btn btn-sm" onClick={() => setEditing(v)}>편집</button>
              <button className="btn btn-sm" onClick={() => toggleClose(v)}>{v.status === 'CLOSED' ? '다시 열기' : '닫기'}</button>
              <button className="icon-btn icon-btn-sm" aria-label="삭제" onClick={() => remove(v)}><Icon name="trash" size={14} /></button>
            </span>
          )}
        </div>
        <div className="rm-progress">
          <span className="progress-bar big"><span style={{ width: `${pct}%` }} /></span>
          <span className="small nowrap">{done}/{list.length} 완료 · {pct}%{est ? ` · 예상 ${fmtHours(est)}` : ''}{spent ? ` · 소요 ${fmtHours(spent)}` : ''}</span>
        </div>
        {expanded && (
          <div className="rm-items">
            {list.length === 0 && <div className="muted small" style={{ padding: '6px 2px' }}>이 버전에 배정된 업무가 없습니다. 업무 상세에서 버전을 지정하세요.</div>}
            {list.map((it) => (
              <div key={it.id} className={`rm-row ${statusById[it.statusId]?.done ? 'is-done' : ''}`}>
                <StatusChip item={it} statusById={statusById} statuses={board.statuses} onChange={(p) => ops.updateItem(it.id, p).catch(() => {})} variant="icon" />
                <span className="lrow-id">{itemKey(board, it)}</span>
                <TypeChip item={it} types={board.types} onChange={(p) => ops.updateItem(it.id, p).catch(() => {})} variant="icon" />
                <button className="lrow-name" onClick={() => ops.openItem(it.id)}>{it.name}</button>
                <span className="lrow-meta">
                  {it.progress != null && <span className="small muted">{it.progress}%</span>}
                  {it.dueDate && <span className="small muted">{friendlyDate(it.dueDate)}</span>}
                  <AssigneeChip item={it} users={users} onChange={(p) => ops.updateItem(it.id, p).catch(() => {})} />
                </span>
              </div>
            ))}
          </div>
        )}
      </section>
    )
  }
  return (
    <div className="roadmap">
      <div className="section-toolbar">
        <label className="check small"><input type="checkbox" checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} /> 닫힌 버전 보기</label>
        <span className="grow" />
        {canManage && <button className="btn btn-primary btn-sm" onClick={() => setEditing({})}><Icon name="plus" size={14} /> 버전·마일스톤</button>}
      </div>
      {versions.map((v) => card(v, byVersion[v.id] || []))}
      {(byVersion.none || []).length > 0 && card(null, byVersion.none)}
      {versions.length === 0 && !(byVersion.none || []).length && <Empty icon="map" title={filtering ? '조건에 맞는 업무가 없습니다' : '버전·마일스톤이 없습니다'}>{!filtering && '예: 2026.11 정기 패치, 1차 오픈. 버전을 만들고 업무에 배정하면 진행률이 보입니다.'}</Empty>}
      {editing && <VersionModal v={editing.id ? editing : null} onClose={() => setEditing(null)} onSave={save} />}
      {confirmDialog}
    </div>
  )
}

function VersionModal({ v, onClose, onSave }) {
  const [f, setF] = useState({ id: v?.id, name: v?.name || '', description: v?.description || '', startDate: v?.startDate || '', dueDate: v?.dueDate || '', status: v?.status || 'OPEN' })
  return (
    <Modal title={v ? '버전 편집' : '새 버전·마일스톤'} onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>취소</button><button className="btn btn-primary" disabled={!f.name.trim()} onClick={() => onSave({ ...f, startDate: f.startDate || null, dueDate: f.dueDate || null })}>저장</button></>}>
      <label className="field"><span>이름</span><input className="input" autoFocus maxLength={100} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="예: 2026.11 정기 패치" /></label>
      <label className="field"><span>설명 <em>선택</em></span><input className="input" maxLength={1000} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></label>
      <div className="row gap">
        <label className="field grow"><span>시작일</span><input type="date" className="input" value={f.startDate} onChange={(e) => setF({ ...f, startDate: e.target.value })} /></label>
        <label className="field grow"><span>마감일</span><input type="date" className="input" value={f.dueDate} onChange={(e) => setF({ ...f, dueDate: e.target.value })} /></label>
      </div>
    </Modal>
  )
}
