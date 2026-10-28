import React, { useRef, useState } from 'react'
import { api } from '../api.js'
import { Modal, useToast, Popover, usePopover } from './common.jsx'
import { Icon } from './icons.jsx'
import { friendlyDate } from '../util.js'

/** 보드 헤더의 엑셀 메뉴 */
export function ExcelMenu({ board, onImported }) {
  const pop = usePopover()
  const [importing, setImporting] = useState(false)
  return (
    <>
      <button className="btn btn-white btn-sm" onClick={pop.open} title="엑셀 내보내기/가져오기"><Icon name="sheet" size={14} /> 엑셀</button>
      {pop.anchor && (
        <Popover anchor={pop.anchor} onClose={pop.close} width={250} align="right">
          <div className="menu">
            <a className="menu-row" href={`/api/boards/${board.id}/excel?template=true`} onClick={pop.close}>
              <Icon name="download" size={14} /><span className="grow">빈 양식 내려받기</span>
            </a>
            <a className="menu-row" href={`/api/boards/${board.id}/excel`} onClick={pop.close}>
              <Icon name="download" size={14} /><span className="grow">현재 업무 내보내기</span><span className="menu-meta">{board.items.length}건</span>
            </a>
            <div className="menu-sep" />
            <button className="menu-row" onClick={() => { pop.close(); setImporting(true) }}>
              <Icon name="upload" size={14} /><span className="grow">엑셀 가져오기…</span>
            </button>
          </div>
        </Popover>
      )}
      {importing && <ExcelImportModal board={board} onClose={() => setImporting(false)} onImported={onImported} />}
    </>
  )
}

function ExcelImportModal({ board, onClose, onImported }) {
  const toast = useToast()
  const [file, setFile] = useState(null)
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const [over, setOver] = useState(false)
  const inputRef = useRef(null)

  const check = async (f) => {
    if (!f) return
    if (!/\.xlsx$/i.test(f.name)) { toast('엑셀(.xlsx) 파일만 가져올 수 있습니다.', 'error'); return }
    setFile(f); setResult(null); setBusy(true)
    try { setResult(await api.upload(`/api/boards/${board.id}/excel/import?dryRun=true`, f)) }
    catch (e) { toast(e.message, 'error'); setFile(null) }
    finally { setBusy(false); if (inputRef.current) inputRef.current.value = '' }
  }
  const apply = async () => {
    setBusy(true)
    try {
      const r = await api.upload(`/api/boards/${board.id}/excel/import?dryRun=false`, file)
      if (r.errors.length) { setResult(r); toast('오류가 있어 저장하지 않았습니다.', 'error'); return }
      toast(`가져오기 완료 · 생성 ${r.created}건, 수정 ${r.updated}건${r.newGroups.length ? `, 새 그룹 ${r.newGroups.length}개` : ''}`, 'success')
      onImported(); onClose()
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }

  const hasErrors = result && result.errors.length > 0
  const nothing = result && !hasErrors && result.created + result.updated === 0

  return (
    <Modal title="엑셀로 업무 가져오기" onClose={onClose} width={result ? 860 : 520}
      footer={<>
        {result && <button className="btn" style={{ marginRight: 'auto' }} onClick={() => { setResult(null); setFile(null) }}>다른 파일 선택</button>}
        <button className="btn" onClick={onClose}>취소</button>
        {result && <button className="btn btn-primary" onClick={apply} disabled={busy || hasErrors || nothing}>
          {busy ? '저장 중…' : hasErrors ? '오류를 고친 뒤 다시 올려 주세요' : nothing ? '바뀐 내용 없음' : `가져오기 (${result.created + result.updated}건 저장)`}
        </button>}
      </>}>
      {!result && (
        <>
          <ol className="steps">
            <li><b>양식 내려받기</b> — <a className="link" href={`/api/boards/${board.id}/excel?template=true`}>빈 양식</a> 또는 <a className="link" href={`/api/boards/${board.id}/excel`}>현재 업무</a>를 받습니다.</li>
            <li><b>작성</b> — 한 줄에 업무 하나. 번호가 비어 있으면 새 업무, 번호가 있으면 그 업무를 수정합니다.</li>
            <li><b>올리기</b> — 저장 전에 미리보기로 확인합니다. 오류가 하나라도 있으면 저장되지 않습니다.</li>
          </ol>
          <div className={`dropzone ${over ? 'over' : ''}`} role="button" tabIndex={0}
            onClick={() => inputRef.current?.click()}
            onKeyDown={(e) => { if (e.key === 'Enter') inputRef.current?.click() }}
            onDragOver={(e) => { e.preventDefault(); setOver(true) }} onDragLeave={() => setOver(false)}
            onDrop={(e) => { e.preventDefault(); setOver(false); check(e.dataTransfer.files[0]) }}>
            <Icon name="sheet" size={22} />
            <div>{busy ? '확인 중…' : <>작성한 엑셀 파일을 끌어다 놓거나 <u>클릭해서 선택</u></>}</div>
            <div className="hint" style={{ margin: 0 }}>.xlsx · 최대 10MB · 3,000행</div>
            <input ref={inputRef} type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" hidden onChange={(e) => check(e.target.files[0])} />
          </div>
        </>
      )}

      {result && (
        <div className="import-result">
          <div className="import-file"><Icon name="sheet" size={14} /> {file?.name}</div>
          <div className="import-summary">
            <span className="tag tag-success">생성 {result.created}</span>
            <span className="tag tag-accent">수정 {result.updated}</span>
            <span className="tag">변경 없음 {result.unchanged}</span>
            {result.newGroups.length > 0 && <span className="tag">새 그룹: {result.newGroups.join(', ')}</span>}
            {hasErrors && <span className="tag tag-danger">오류 {result.errors.length}</span>}
          </div>
          {result.warnings.map((w, i) => <div key={i} className="form-warn">{w}</div>)}
          {hasErrors && (
            <>
              <div className="form-error" style={{ marginTop: 8 }}>아래 오류를 엑셀에서 고친 뒤 다시 올려 주세요. 지금 상태로는 아무것도 저장되지 않습니다.</div>
              <div className="table-wrap import-table">
                <table className="table">
                  <thead><tr><th>행</th><th>열</th><th>내용</th></tr></thead>
                  <tbody>{result.errors.map((e, i) => <tr key={i}><td className="num">{e.row}</td><td>{e.column}</td><td style={{ whiteSpace: 'normal' }}>{e.message}</td></tr>)}</tbody>
                </table>
              </div>
            </>
          )}
          {!hasErrors && result.preview.length > 0 && (
            <>
              <h4 className="section-label" style={{ marginTop: 12 }}>미리보기 {result.preview.length < result.totalRows ? `(앞 ${result.preview.length}행)` : ''}</h4>
              <div className="table-wrap import-table">
                <table className="table">
                  <thead><tr><th>행</th><th>작업</th><th>번호</th><th>그룹</th><th>담당업무</th><th>업무명</th><th>상태</th><th>담당자</th><th>우선순위</th><th>마감일</th></tr></thead>
                  <tbody>{result.preview.map((p) => (
                    <tr key={p.row} className={p.action === '변경 없음' ? 'inactive' : ''}>
                      <td className="num">{p.row}</td>
                      <td><span className={`tag ${p.action === '생성' ? 'tag-success' : p.action === '수정' ? 'tag-accent' : ''}`}>{p.action}</span></td>
                      <td className="num muted">{p.id ? `#${p.id}` : ''}</td>
                      <td>{p.group}</td>
                      <td>{p.duty ? <span className="ellipsis" style={{ maxWidth: '11rem' }} title={p.duty}>{p.duty}</span> : <span className="muted">-</span>}</td>
                      <td><span className="ellipsis" style={{ maxWidth: '16rem' }} title={p.name}>{p.name}</span></td>
                      <td>{p.status || <span className="muted">-</span>}</td>
                      <td>{p.assignee || <span className="muted">-</span>}</td>
                      <td>{p.priority || <span className="muted">-</span>}</td>
                      <td>{p.dueDate ? friendlyDate(p.dueDate) : <span className="muted">-</span>}</td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>
            </>
          )}
        </div>
      )}
    </Modal>
  )
}
