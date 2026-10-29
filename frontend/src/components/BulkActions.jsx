import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useApp } from '../App.jsx'
import { Modal, useToast } from './common.jsx'
import { Icon } from './icons.jsx'
import { itemNoun, josa } from './Fields.jsx'

/** 선택 칸: 행·머리글·그룹 공용 체크박스 (일부만 선택이면 indeterminate) */
export function SelectBox({ checked, partial, onChange, label }) {
  return (
    <label className="sel-check" onClick={(e) => e.stopPropagation()}>
      <input type="checkbox" aria-label={label} checked={!!checked}
        ref={(el) => { if (el) el.indeterminate = !checked && !!partial }}
        onChange={(e) => onChange(e.target.checked)} />
    </label>
  )
}

/**
 * 여러 업무를 선택했을 때 화면 아래에 뜨는 작업 막대: 워크스페이스 이동 · 복제 · 엑셀 내보내기 · 삭제.
 * 하위 업무는 상위 업무와 함께 처리된다 (상위를 고르면 하위도 같이 이동·복제·삭제·내보내기).
 */
export function BulkBar({ board, ids, onClear, onDone, confirm, canDelete = () => true }) {
  const noun = itemNoun(board)
  const toast = useToast()
  const { refreshBoards } = useApp()
  const [busy, setBusy] = useState(false)
  const [moving, setMoving] = useState(false)
  const n = ids.length
  const subOnly = board.items.filter((i) => ids.includes(i.id) && i.parentId && !ids.includes(i.parentId)).length

  const run = async (fn, msg) => {
    setBusy(true)
    try {
      const r = await fn()
      toast(msg(r) + (r?.skipped ? ` (${r.skipped}개는 상위 ${noun}${josa(noun, '과/와')} 함께 처리되었거나 제외됨)` : ''), 'success')
      onClear(); await onDone(); refreshBoards()
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }
  const copy = () => run(() => api.post('/api/items/bulk-copy', { ids }), (r) => `${noun} ${r.done}개를 복제했습니다.`)
  const del = async () => {
    if (!(await confirm(`선택한 ${noun} ${n}개를 삭제할까요?\n하위 ${noun}·댓글·첨부파일도 함께 삭제되며 되돌릴 수 없습니다.`, { okText: `${n}개 삭제` }))) return
    run(() => api.post('/api/items/bulk-delete', { ids }), (r) => `${noun} ${r.done}개를 삭제했습니다.`)
  }
  const exportUrl = `/api/boards/${board.id}/excel?ids=${ids.join(',')}`

  return (
    <>
      <div className="bulk-bar" role="toolbar" aria-label={`선택한 ${noun} 작업`}>
        <span className="bulk-count"><b>{n}</b>개 선택</span>
        <span className="bulk-sep" />
        <button className="bulk-btn" disabled={busy || subOnly === n} onClick={() => setMoving(true)}
          title={subOnly ? `하위 ${noun}만 선택한 항목은 상위 ${noun}${josa(noun, '과/와')} 함께만 이동할 수 있어 제외됩니다` : '다른 워크스페이스로 이동'}>
          <Icon name="arrowRight" size={15} /> 워크스페이스 이동
        </button>
        <button className="bulk-btn" disabled={busy} onClick={copy} title="같은 그룹 맨 아래에 사본을 만듭니다 (댓글·첨부 제외)"><Icon name="copy" size={15} /> 복제</button>
        <a className="bulk-btn" href={exportUrl} aria-disabled={busy} onClick={() => toast(`선택한 ${noun} ${n}개를 엑셀로 내려받습니다.`)}><Icon name="download" size={15} /> 엑셀 내보내기</a>
        {(() => {
          const denied = ids.filter((id) => !canDelete(id)).length
          return <button className="bulk-btn danger" disabled={busy || denied > 0} onClick={del}
            title={denied ? `선택한 것 중 ${denied}개는 등록한 사람이나 관리자만 삭제할 수 있습니다` : undefined}><Icon name="trash" size={15} /> 삭제</button>
        })()}
        <span className="bulk-sep" />
        <button className="bulk-close" onClick={onClear} title="선택 해제 (Esc)" aria-label="선택 해제"><Icon name="x" size={16} /></button>
      </div>
      {moving && <BulkMoveModal board={board} count={n - subOnly} subOnly={subOnly} onClose={() => setMoving(false)}
        onSubmit={(boardId, groupId, name) => { setMoving(false); run(() => api.post('/api/items/bulk-move', { ids, boardId, groupId }), (r) => `${noun} ${r.done}개를 '${name}' 워크스페이스로 옮겼습니다.`) }} />}
    </>
  )
}

function BulkMoveModal({ board, count, subOnly, onClose, onSubmit }) {
  const noun = itemNoun(board)
  const { boards } = useApp()
  const toast = useToast()
  const targets = boards.filter((b) => b.id !== board.id)
  const [boardId, setBoardId] = useState(targets[0]?.id || null)
  const [target, setTarget] = useState(null)
  const [groupId, setGroupId] = useState('')
  useEffect(() => {
    setTarget(null); setGroupId('')
    if (boardId) api.get(`/api/boards/${boardId}`).then(setTarget).catch((e) => toast(e.message, 'error'))
  }, [boardId]) // eslint-disable-line
  return (
    <Modal title={`${noun} ${count}개 다른 워크스페이스로 이동`} onClose={onClose} width={480}
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn-primary" disabled={!target || count === 0} onClick={() => onSubmit(boardId, groupId ? Number(groupId) : null, target.name)}>이동</button>
      </>}>
      {targets.length === 0 ? <p className="muted">옮길 수 있는 다른 워크스페이스가 없습니다.</p> : <>
        <label className="field"><span>워크스페이스</span>
          <select className="input" value={boardId || ''} onChange={(e) => setBoardId(Number(e.target.value))}>
            {targets.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select></label>
        <label className="field"><span>그룹</span>
          <select className="input" value={groupId} onChange={(e) => setGroupId(e.target.value)} disabled={!target}>
            <option value="">같은 이름 그룹 (없으면 첫 그룹)</option>
            {target?.groups.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select></label>
        <div className="move-summary">
          <div><Icon name="check" size={13} /> 댓글·첨부파일·하위 {noun}{josa(noun, '은/는')} 함께 이동합니다.</div>
          <div><Icon name="check" size={13} /> 상태·컬럼 값은 대상에 같은 이름이 있으면 유지, 없으면 비워집니다.</div>
          {subOnly > 0 && <div className="warn"><Icon name="x" size={13} /> 하위 {noun}만 선택한 {subOnly}개는 제외됩니다 (상위 {noun}{josa(noun, '과/와')} 함께만 이동).</div>}
        </div>
      </>}
    </Modal>
  )
}
