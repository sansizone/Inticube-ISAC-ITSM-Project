import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useApp } from '../App.jsx'
import { Modal, useToast, useConfirm, Popover, usePopover } from './common.jsx'
import { PALETTE } from '../util.js'
import { Icon, StatusIcon } from './icons.jsx'
import { COLUMN_TYPES, columnsFor, boardColumns, columnConfig, TYPE_LABEL, builtinLabel } from './Fields.jsx'
import { MembersEditor } from './Workspace.jsx'
import { fmtHours } from '../sla.js'

const TYPE_ICONS = ['inbox', 'alert', 'edit', 'chat', 'repeat', 'wrench', 'bolt', 'shield', 'bell', 'tag', 'doc', 'settings', 'check']

export default function BoardSettings({ board, onClose, onSaved, onDeleted, onColumnsChanged, initialTab }) {
  const can = board.canManage
  const [tab, setTab] = useState(!can ? 'members' : ['columns', 'members', 'types'].includes(initialTab) ? initialTab : 'general')
  const [keyPrefix, setKeyPrefix] = useState(board.keyPrefix || '')
  const [slaBusiness, setSlaBusiness] = useState(!!board.slaBusinessHours)
  const [showProgress, setShowProgress] = useState(columnConfig(board).showProgress)
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [name, setName] = useState(board.name)
  const [description, setDescription] = useState(board.description || '')
  const [statuses, setStatuses] = useState(board.statuses.map((s) => ({ ...s })))
  const [busy, setBusy] = useState(false)

  const set = (idx, patch) => setStatuses((l) => l.map((s, i) => (i === idx ? { ...s, ...patch } : s)))
  const move = (idx, dir) => setStatuses((l) => {
    const n = [...l]; const to = idx + dir
    if (to < 0 || to >= n.length) return l
    ;[n[idx], n[to]] = [n[to], n[idx]]; return n
  })

  const save = async () => {
    if (statuses.some((s) => !s.label.trim())) { toast('상태 이름을 입력하세요.', 'error'); return }
    const removed = board.statuses.filter((s) => !statuses.some((x) => x.id === s.id))
    if (removed.length) {
      const affected = board.items.filter((i) => removed.some((r) => r.id === i.statusId)).length
      if (affected && !(await confirm(`삭제되는 상태(${removed.map((r) => r.label).join(', ')})를 사용하는 업무 ${affected}개는 '상태 없음'이 됩니다. 계속할까요?`))) return
    }
    setBusy(true)
    try {
      await api.put(`/api/boards/${board.id}`, { name, description })
      if (keyPrefix !== (board.keyPrefix || '') || slaBusiness !== !!board.slaBusinessHours) await api.put(`/api/boards/${board.id}/key-settings`, { keyPrefix, slaBusinessHours: slaBusiness })
      await api.put(`/api/boards/${board.id}/statuses`, statuses.map((s) => ({ id: s.id, label: s.label.trim(), color: s.color, done: s.done })))
      toast('워크스페이스 설정을 저장했습니다.', 'success')
      onSaved(); onClose()
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }

  const remove = async () => {
    if (!(await confirm(`'${board.name}' 워크스페이스를 삭제할까요?\n모든 그룹, 업무, 댓글, 첨부파일이 영구 삭제되며 되돌릴 수 없습니다.`, { okText: '영구 삭제' }))) return
    try { await api.del(`/api/boards/${board.id}`); toast('워크스페이스를 삭제했습니다.'); onClose(); onDeleted() } catch (e) { toast(e.message, 'error') }
  }

  return (
    <Modal title="워크스페이스 설정" onClose={onClose} width={tab === 'general' ? 560 : 820}
      footer={tab !== 'general' ? <button className="btn" onClick={onClose}>닫기</button> : <>
        <button className="btn btn-danger-ghost" onClick={remove} style={{ marginRight: 'auto' }}>워크스페이스 삭제</button>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn-primary" onClick={save} disabled={busy}>저장</button>
      </>}>
      <div className="seg" style={{ marginBottom: 14 }}>
        {can && <button className={tab === 'general' ? 'active' : ''} onClick={() => setTab('general')}>기본·상태</button>}
        {can && <button className={tab === 'types' ? 'active' : ''} onClick={() => setTab('types')}>유형</button>}
        {can && <button className={tab === 'columns' ? 'active' : ''} onClick={() => setTab('columns')}>컬럼</button>}
        <button className={tab === 'members' ? 'active' : ''} onClick={() => setTab('members')}>멤버·공개</button>
      </div>
      {tab === 'columns' && <ColumnSettings board={board} onChanged={onColumnsChanged || onSaved} confirm={confirm} toast={toast} />}
      {tab === 'members' && <MembersEditor board={board} onSaved={onSaved} />}
      {tab === 'types' && <TypeSettings board={board} toast={toast} confirm={confirm} onSaved={onSaved} />}
      {tab === 'general' && <>
      <label className="field"><span>워크스페이스 이름</span><input className="input" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} /></label>
      <label className="field"><span>설명</span><textarea className="input" rows={2} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
      <div className="row gap">
        <label className="field" style={{ width: '11rem' }}><span>업무 키 접두어</span>
          <input className="input" value={keyPrefix} maxLength={10} onChange={(e) => setKeyPrefix(e.target.value.toUpperCase())} pattern="[A-Z][A-Z0-9]{1,9}" placeholder="REQ" />
          <span className="hint" style={{ margin: '4px 0 0' }}>업무 키 예: <b>{keyPrefix || 'TB'}-12</b></span></label>
      </div>
      <div className="field"><span>진행률</span>
        <label className="check" style={{ fontWeight: 400 }}>
          <input type="checkbox" checked={showProgress} onChange={async (e) => {
            const on = e.target.checked; setShowProgress(on)
            try { await api.put(`/api/boards/${board.id}/column-config`, { config: JSON.stringify({ ...columnConfig(board), showProgress: on }) }); onSaved() }
            catch (x) { setShowProgress(!on); toast(x.message, 'error') }
          }} /> 진행률(%) 표시
        </label>
        <p className="hint" style={{ margin: '4px 0 0' }}>업무를 처리하는 워크스페이스는 켜고, 고객사·솔루션처럼 목록만 관리하는 곳은 끄세요. 끄면 사이드바·대시보드·상단에 %가 나오지 않습니다. (바로 저장됨)</p>
      </div>
      <div className="field"><span>상태 라벨</span>
        <p className="hint" style={{ margin: '0 0 8px' }}>'완료' 체크된 상태는 진행률·지연 계산에서 완료로 처리됩니다.</p>
        <div className="status-editor">
          {statuses.map((s, idx) => (
            <div key={s.id ?? `n${idx}`} className="status-edit-row">
              <StatusIcon status={s} statuses={statuses} />
              <ColorButton color={s.color} onPick={(c) => set(idx, { color: c })} />
              <input className="input input-sm grow" value={s.label} maxLength={50} onChange={(e) => set(idx, { label: e.target.value })} aria-label="상태 이름" />
              <label className="check small"><input type="checkbox" checked={s.done} onChange={(e) => set(idx, { done: e.target.checked })} /> 완료</label>
              <button className="icon-btn" onClick={() => move(idx, -1)} disabled={idx === 0} aria-label="위로">↑</button>
              <button className="icon-btn" onClick={() => move(idx, 1)} disabled={idx === statuses.length - 1} aria-label="아래로">↓</button>
              <button className="icon-btn" onClick={() => setStatuses((l) => l.filter((_, i) => i !== idx))} disabled={statuses.length <= 1} aria-label="삭제"><Icon name="x" size={14} /></button>
            </div>
          ))}
        </div>
        <button className="chip-btn" style={{ marginTop: 8 }} onClick={() => setStatuses((l) => [...l, { id: null, label: '새 상태', color: PALETTE[(l.length * 3) % PALETTE.length], done: false }])}>＋ 상태 추가</button>
      </div>
      </>}
      {confirmDialog}
    </Modal>
  )
}

function ColorButton({ color, onPick }) {
  const pop = usePopover()
  return (
    <>
      <button className="swatch" style={{ background: color }} onClick={pop.open} aria-label="색상 선택" />
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={200}>
          <div className="swatches" style={{ padding: 6 }}>
            {PALETTE.map((c) => <button key={c} className={`swatch ${c === color ? 'active' : ''}`} style={{ background: c }} aria-label={c} onClick={() => { onPick(c); pop.close() }} />)}
          </div>
        </Popover>
      )}
    </>
  )
}

/** 연결 컬럼: 대상 워크스페이스·표시 컬럼 선택 */
export function RelationTarget({ board, refBoardId, refColumnId, onChange }) {
  const { boards } = useApp()
  const [target, setTarget] = useState(null)
  useEffect(() => {
    setTarget(null)
    if (refBoardId) api.get(`/api/boards/${refBoardId}`).then(setTarget).catch(() => {})
  }, [refBoardId])
  const tcols = target ? columnsFor(target, 'ITEM').filter((c) => !['LONG_TEXT', 'RELATION'].includes(c.type)) : []
  return (
    <div className="rel-target">
      <Icon name="link" size={13} className="muted" />
      <select className="input input-sm" value={refBoardId || ''} onChange={(e) => onChange(Number(e.target.value) || null, null)} aria-label="연결할 워크스페이스">
        <option value="">연결할 워크스페이스…</option>
        {boards.filter((b) => b.id !== board.id).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
      </select>
      <span className="muted small nowrap">표시</span>
      <select className="input input-sm" value={refColumnId || ''} disabled={!target} onChange={(e) => onChange(refBoardId, Number(e.target.value) || null)} aria-label="표시할 컬럼">
        <option value="">{target ? builtinLabel(target, 'name') : '업무명'}</option>
        {tcols.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>
    </div>
  )
}

/**
 * 컬럼 관리 — 바뀔 때마다 바로 저장.
 * 업무 컬럼: 기본 컬럼(상태·담당자·마감일…)과 사용자 정의 컬럼을 한 목록에서 이름·순서·표시 여부 편집.
 */
function ColumnSettings({ board, onChanged, confirm, toast }) {
  const [scope, setScope] = useState('ITEM')
  const [newName, setNewName] = useState('')
  const [newType, setNewType] = useState('TEXT')
  const [newRef, setNewRef] = useState({ refBoardId: null, refColumnId: null })
  const [converting, setConverting] = useState(null)
  const run = async (fn) => { try { await fn(); await onChanged() } catch (e) { toast(e.message, 'error') } }
  const saveCol = (c, patch) => run(() => api.put(`/api/columns/${c.id}`, { name: c.name, type: c.type, scope: c.scope, visible: c.visible, refBoardId: c.refBoardId, refColumnId: c.refColumnId, ...patch }))
  const cfg = columnConfig(board)
  const saveCfg = (patch) => run(() => api.put(`/api/boards/${board.id}/column-config`, { config: JSON.stringify({ ...cfg, ...patch }) }))

  const all = boardColumns(board)
  const moveAll = (idx, dir) => {
    const list = [...all]; const to = idx + dir
    if (to < 0 || to >= list.length) return
    ;[list[idx], list[to]] = [list[to], list[idx]]
    saveCfg({ order: list.map((c) => c.key) })
  }
  const subCols = columnsFor(board, 'SUBITEM')
  const moveSub = (idx, dir) => {
    const list = [...subCols]; const to = idx + dir
    if (to < 0 || to >= list.length) return
    ;[list[idx], list[to]] = [list[to], list[idx]]
    run(() => api.post(`/api/boards/${board.id}/columns/reorder`, { ids: [...columnsFor(board, 'ITEM'), ...list].map((c) => c.id) }))
  }
  const del = async (c) => {
    if (!(await confirm(`'${c.name}' 컬럼을 삭제할까요?\n모든 업무에 입력된 이 컬럼의 값도 함께 삭제됩니다.`, { okText: '삭제' }))) return
    run(() => api.del(`/api/columns/${c.id}`))
  }
  const typeSelect = (c) => (
    <select className="input input-sm" value={c.type} onChange={(e) => {
      // 연결로 바꾸면: 대상 워크스페이스를 고르고 기존 값을 이름으로 맞춰 연결하는 변환 창을 연다
      if (e.target.value === 'RELATION') { setConverting(c); return }
      saveCol(c, { type: e.target.value })
    }} aria-label="유형">
      {COLUMN_TYPES.filter(([k]) => k !== 'RELATION' || c.type === 'RELATION' || c.scope === 'ITEM')
        .map(([k, l]) => <option key={k} value={k}>{k === 'RELATION' && c.type !== 'RELATION' ? '연결 (다른 워크스페이스에서 선택)…' : l}</option>)}
    </select>
  )

  return (
    <div>
      <div className="seg" style={{ marginBottom: 10 }}>
        <button className={scope === 'ITEM' ? 'active' : ''} onClick={() => setScope('ITEM')}>업무 컬럼 ({all.length})</button>
        <button className={scope === 'SUBITEM' ? 'active' : ''} onClick={() => setScope('SUBITEM')}>하위 업무 컬럼 ({subCols.length})</button>
      </div>
      {scope === 'ITEM' ? <>
        <p className="hint" style={{ marginTop: 0 }}>순서·이름·표시 여부는 이 워크스페이스의 모든 보기(표·목록)에 적용됩니다. 보기마다 더 숨기려면 보기의 <b>컬럼</b> 버튼을 쓰세요. 업무명은 항상 첫 컬럼입니다.</p>
        <p className="hint" style={{ marginTop: 0 }}>값을 <b>다른 워크스페이스의 항목</b>에서 골라야 하는 컬럼(예: 서비스요청자 → 고객관리의 고객)은 유형을 <b>연결</b>로 바꾸거나 <Icon name="link" size={12} /> 버튼을 누르세요. 이미 입력된 값은 이름으로 자동 연결되고, 못 찾은 값은 지우지 않고 따로 보관합니다.</p>
        <div className="col-editor">
          {/* 고정 컬럼: 항상 맨 앞에 보이고 숨기거나 옮길 수 없지만 이름은 바꿀 수 있음 */}
          {['key', 'name'].map((k) => (
            <div key={k} className="col-edit-row">
              <span className="col-kind">고정</span>
              <input className="input input-sm grow" defaultValue={builtinLabel(board, k)} key={`${k}:${builtinLabel(board, k)}`} maxLength={30}
                aria-label="컬럼 이름" placeholder={k === 'name' ? '업무명' : '키'}
                onBlur={(e) => {
                  const v = e.target.value.trim(); const def = k === 'name' ? '업무명' : '키'
                  if (v === builtinLabel(board, k)) return
                  const labels = { ...cfg.labels }
                  if (!v || v === def) delete labels[k]; else labels[k] = v
                  saveCfg({ labels })
                }} />
              <span className="muted small nowrap">{k === 'name' ? '예: 솔루션명, 고객명' : '업무 번호'}</span>
              <span /><span /><span /><span /><span />
            </div>
          ))}
          {all.map((c, idx) => (
            <div key={c.key} className={`col-edit-row ${c.hidden ? 'is-hidden' : ''}`}>
              <span className="col-kind">{c.builtin ? '기본' : TYPE_LABEL[c.col.type]?.split(' ')[0]}</span>
              <input className="input input-sm grow" defaultValue={c.label} key={`${c.key}:${c.label}`} maxLength={c.builtin ? 30 : 100} aria-label="컬럼 이름"
                onBlur={(e) => {
                  const v = e.target.value.trim()
                  if (!v || v === c.label) { e.target.value = c.label; return }
                  if (c.builtin) saveCfg({ labels: { ...cfg.labels, [c.key]: v } }); else saveCol(c.col, { name: v })
                }} />
              {/* 격자 칸 고정: 종류 | 이름 | 유형 | 표시 | ↑ | ↓ | 동작1 | 동작2 (+ 연결 대상은 다음 줄) */}
              {c.builtin ? <span /> : typeSelect(c.col)}
              <label className="check small nowrap"><input type="checkbox" checked={!c.hidden}
                onChange={(e) => {
                  if (c.builtin) saveCfg({ hidden: e.target.checked ? cfg.hidden.filter((k) => k !== c.key) : [...cfg.hidden, c.key] })
                  else saveCol(c.col, { visible: e.target.checked })
                }} /> 표시</label>
              <button className="icon-btn" onClick={() => moveAll(idx, -1)} disabled={idx === 0} aria-label="위로">↑</button>
              <button className="icon-btn" onClick={() => moveAll(idx, 1)} disabled={idx === all.length - 1} aria-label="아래로">↓</button>
              {c.builtin
                ? <button className="icon-btn" aria-label="기본 이름으로" title="기본 이름으로" disabled={!cfg.labels[c.key]}
                    onClick={() => { const l = { ...cfg.labels }; delete l[c.key]; saveCfg({ labels: l }) }}><Icon name="reply" size={14} /></button>
                : <>
                  {c.col.type !== 'RELATION'
                    ? <button className="icon-btn" aria-label="연결 컬럼으로 변환" title="연결 컬럼으로 변환 (다른 워크스페이스 항목에서 고르도록)" onClick={() => setConverting(c.col)}><Icon name="link" size={14} /></button>
                    : <span />}
                  <button className="icon-btn" aria-label="삭제" onClick={() => del(c.col)}><Icon name="trash" size={14} /></button>
                </>}
              {c.builtin && <span />}
              {!c.builtin && c.col.type === 'RELATION' && (
                <div className="col-edit-ref">
                  <RelationTarget board={board} refBoardId={c.col.refBoardId} refColumnId={c.col.refColumnId}
                    onChange={async (rb, rc) => {
                      if (rb !== c.col.refBoardId && !(await confirm('연결 대상을 바꾸면 이 컬럼에 입력된 연결 값이 모두 지워집니다. 계속할까요?', { okText: '변경' }))) return
                      saveCol(c.col, { refBoardId: rb, refColumnId: rc })
                    }} />
                </div>
              )}
            </div>
          ))}
        </div>
        {converting && <ConvertModal board={board} col={converting} onClose={() => setConverting(null)} onDone={async () => { setConverting(null); await onChanged() }} />}
      </> : <>
        <p className="hint" style={{ marginTop: 0 }}>하위 업무에만 쓰는 컬럼입니다. 업무 컬럼과 이름이 같으면 표 보기에서 같은 열에 표시됩니다.</p>
        <div className="col-editor">
          {subCols.map((c, idx) => (
            <div key={c.id} className="col-edit-row">
              <input className="input input-sm grow" defaultValue={c.name} maxLength={100} aria-label="컬럼 이름"
                onBlur={(e) => { const v = e.target.value.trim(); if (v && v !== c.name) saveCol(c, { name: v }); else e.target.value = c.name }} />
              {typeSelect(c)}
              <button className="icon-btn" onClick={() => moveSub(idx, -1)} disabled={idx === 0} aria-label="위로">↑</button>
              <button className="icon-btn" onClick={() => moveSub(idx, 1)} disabled={idx === subCols.length - 1} aria-label="아래로">↓</button>
              <button className="icon-btn" aria-label="삭제" onClick={() => del(c)}><Icon name="trash" size={14} /></button>
            </div>
          ))}
          {subCols.length === 0 && <div className="muted small" style={{ padding: '8px 2px' }}>아직 컬럼이 없습니다.</div>}
        </div>
      </>}
      <form className="col-add" onSubmit={(e) => {
        e.preventDefault()
        if (!newName.trim()) return
        if (newType === 'RELATION' && !newRef.refBoardId) { toast('연결할 워크스페이스를 선택하세요.', 'error'); return }
        run(async () => {
          await api.post(`/api/boards/${board.id}/columns`, { name: newName.trim(), type: newType, scope, visible: newType !== 'LONG_TEXT', ...(newType === 'RELATION' ? newRef : {}) })
          setNewName(''); setNewRef({ refBoardId: null, refColumnId: null })
        })
      }}>
        <div className="col-edit-row">
          <input className="input input-sm grow" placeholder={newType === 'RELATION' ? '새 연결 컬럼 이름 (예: 고객사)' : '새 컬럼 이름 (예: 고객사코드)'} value={newName} maxLength={100} onChange={(e) => setNewName(e.target.value)} />
          <select className="input input-sm" value={newType} onChange={(e) => setNewType(e.target.value)}>
            {COLUMN_TYPES.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
          </select>
          <button className="btn btn-sm btn-primary" disabled={!newName.trim()}>추가</button>
        </div>
        {newType === 'RELATION' && (
          <div className="col-edit-row">
            <RelationTarget board={board} refBoardId={newRef.refBoardId} refColumnId={newRef.refColumnId} onChange={(rb, rc) => setNewRef({ refBoardId: rb, refColumnId: rc })} />
          </div>
        )}
        {newType === 'RELATION' && <p className="hint" style={{ margin: '4px 0 0' }}>예) 요청사항의 '고객사' → 고객사관리 워크스페이스, 표시 '고객사명'. 업무에서 고객사관리의 행을 검색해 고르고, 누르면 그 행이 열립니다.</p>}
      </form>
    </div>
  )
}

/** 텍스트·드롭다운 컬럼 → 연결 컬럼 변환 (이름으로 자동 매칭, 못 찾은 값은 새로 추가하거나 숨긴 컬럼에 보존) */
function ConvertModal({ board, col, onClose, onDone }) {
  const toast = useToast()
  const { boards } = useApp()
  const [ref, setRef] = useState({ refBoardId: null, refColumnId: null })
  const [pv, setPv] = useState(null)
  const [loading, setLoading] = useState(false)
  const [create, setCreate] = useState(true)
  const [busy, setBusy] = useState(false)
  const targetName = boards.find((b) => b.id === ref.refBoardId)?.name
  useEffect(() => {
    setPv(null)
    if (!ref.refBoardId) return
    setLoading(true)
    const q = `refBoardId=${ref.refBoardId}${ref.refColumnId ? `&refColumnId=${ref.refColumnId}` : ''}`
    api.get(`/api/columns/${col.id}/convert-preview?${q}`).then(setPv).catch((e) => toast(e.message, 'error')).finally(() => setLoading(false))
  }, [ref.refBoardId, ref.refColumnId]) // eslint-disable-line
  const submit = async () => {
    setBusy(true)
    try {
      const r = await api.post(`/api/columns/${col.id}/convert`, { ...ref, createMissing: create })
      toast(`${r.linkedRows}건을 연결했습니다.${r.created ? ` '${targetName}'에 ${r.created}개 새로 추가.` : ''}${r.keptColumnName ? ` 연결 못 한 값은 '${r.keptColumnName}' 컬럼(숨김)에 보존했습니다.` : ''}`, 'success')
      onDone()
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title={`'${col.name}' 컬럼을 연결 컬럼으로 변환`} width={640} onClose={onClose}
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn-primary" disabled={!pv || busy} onClick={submit}>{busy ? '변환 중…' : '변환'}</button>
      </>}>
      <p className="hint" style={{ marginTop: 0 }}>각 업무의 '{col.name}' 값을 대상 워크스페이스의 표시값(또는 업무명)과 이름으로 맞춰 연결합니다. 대소문자·공백은 무시하고, 쉼표로 여러 개가 적힌 값은 나눠서 맞춥니다.</p>
      <div className="field"><span>연결할 워크스페이스 · 표시 컬럼</span>
        <RelationTarget board={board} refBoardId={ref.refBoardId} refColumnId={ref.refColumnId} onChange={(rb, rc) => setRef({ refBoardId: rb, refColumnId: rc })} />
      </div>
      {loading && <div className="muted">확인 중…</div>}
      {pv && (
        <div className="convert-pv">
          <div className="convert-stats">
            <span>값 있는 업무 <b>{pv.rows}</b></span>
            <span className="ok">모두 찾음 <b>{pv.matchedRows}</b></span>
            {pv.partialRows > 0 && <span className="warn">일부만 찾음 <b>{pv.partialRows}</b></span>}
            <span className={pv.unmatchedRows ? 'warn' : ''}>못 찾음 <b>{pv.unmatchedRows}</b></span>
          </div>
          {pv.unmatchedDistinct > 0 && <>
            <div className="convert-miss-head">대상에 없는 값 {pv.unmatchedDistinct}개</div>
            <div className="convert-miss">{pv.unmatched.map((u) => <span key={u.value} className="tag">{u.value} · {u.count}</span>)}{pv.unmatchedDistinct > pv.unmatched.length && <span className="muted small">외 {pv.unmatchedDistinct - pv.unmatched.length}개</span>}</div>
            <label className="check" style={{ marginTop: 10 }}><input type="checkbox" checked={create} onChange={(e) => setCreate(e.target.checked)} /> 없는 값 {pv.unmatchedDistinct}개를 '{targetName}'에 새 항목으로 추가하고 연결</label>
            {!create && <p className="hint" style={{ margin: '4px 0 0' }}>연결하지 못한 값은 '{col.name} (연결 안 된 값)' 숨김 컬럼에 그대로 보존됩니다.</p>}
          </>}
          <p className="hint" style={{ margin: '10px 0 0' }}>변환 후 이 컬럼은 연결 컬럼이 되어, 칩을 누르면 '{targetName}'의 해당 항목이 열립니다. 되돌리기는 지원하지 않으니 먼저 엑셀 내보내기로 백업해 두세요.</p>
        </div>
      )}
    </Modal>
  )
}

/** 업무 유형 편집 (이름·색·아이콘·기본 유형) */
function TypeSettings({ board, toast, confirm, onSaved }) {
  const [list, setList] = useState((board.types || []).map((t) => ({ ...t })))
  const [busy, setBusy] = useState(false)
  const set = (idx, patch) => setList((l) => l.map((t, i) => (i === idx ? { ...t, ...patch } : t)))
  const move = (idx, dir) => setList((l) => { const n = [...l]; const to = idx + dir; if (to < 0 || to >= n.length) return l; [n[idx], n[to]] = [n[to], n[idx]]; return n })
  const save = async () => {
    if (list.some((t) => !t.name.trim())) { toast('유형 이름을 입력하세요.', 'error'); return }
    const removed = (board.types || []).filter((t) => !list.some((x) => x.id === t.id))
    if (removed.length) {
      const n = board.items.filter((i) => removed.some((r) => r.id === i.typeId)).length
      if (n && !(await confirm(`삭제되는 유형(${removed.map((r) => r.name).join(', ')})을 쓰는 업무 ${n}개는 '유형 없음'이 됩니다. 계속할까요?`))) return
    }
    setBusy(true)
    try {
      await api.put(`/api/boards/${board.id}/types`, list.map((t) => ({ id: t.id, name: t.name.trim(), color: t.color, icon: t.icon, responseHours: t.responseHours || null, resolveHours: t.resolveHours || null, isDefault: !!t.isDefault })))
      toast('유형을 저장했습니다.', 'success'); onSaved()
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }
  return (
    <div>
      <p className="hint" style={{ marginTop: 0 }}>업무를 분류할 유형을 정합니다. <b>기본</b>으로 고른 유형이 새 업무에 붙습니다.</p>
      <div className="type-editor">
        <div className="type-row type-head"><span /><span>이름</span><span>아이콘</span><span>기본</span><span /></div>
        {list.map((t, idx) => (
          <div key={t.id ?? `n${idx}`} className="type-row">
            <ColorButton color={t.color} onPick={(c) => set(idx, { color: c })} />
            <input className="input input-sm" value={t.name} maxLength={50} onChange={(e) => set(idx, { name: e.target.value })} aria-label="유형 이름" />
            <select className="input input-sm" value={t.icon || 'tag'} onChange={(e) => set(idx, { icon: e.target.value })} aria-label="아이콘">{TYPE_ICONS.map((k) => <option key={k} value={k}>{k}</option>)}</select>
            <input type="radio" name="deftype" checked={!!t.isDefault} onChange={() => setList((l) => l.map((x, i) => ({ ...x, isDefault: i === idx })))} aria-label="기본 유형" />
            <span className="row gap-xs">
              <button className="icon-btn" onClick={() => move(idx, -1)} disabled={idx === 0} aria-label="위로">↑</button>
              <button className="icon-btn" onClick={() => move(idx, 1)} disabled={idx === list.length - 1} aria-label="아래로">↓</button>
              <button className="icon-btn" onClick={() => setList((l) => l.filter((_, i) => i !== idx))} aria-label="삭제"><Icon name="x" size={14} /></button>
            </span>
          </div>
        ))}
      </div>
      <div className="form-actions">
        <button className="chip-btn" onClick={() => setList((l) => [...l, { id: null, name: '새 유형', color: PALETTE[(l.length * 3) % PALETTE.length], icon: 'tag', responseHours: null, resolveHours: null, isDefault: l.length === 0 }])}>＋ 유형 추가</button>
        <span className="grow" />
        <button className="btn btn-primary btn-sm" disabled={busy} onClick={save}>유형 저장</button>
      </div>
    </div>
  )
}
