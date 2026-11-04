import React, { useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api.js'
import { PageHeader, useApp, navigate } from '../App.jsx'
import { useToast, useConfirm } from './common.jsx'
import { Icon } from './icons.jsx'
import { VisibilityPicker } from './Workspace.jsx'
import { VIEW_TYPES } from './Views.jsx'

/** 열 매핑 선택지: 기본 필드 / 사용자 정의 컬럼 유형 */
const CORE = [
  ['STATUS', '상태'], ['ASSIGNEE', '담당자 (+사람 컬럼으로 원본 보존)'], ['DUE', '마감일'], ['START', '시작일'], ['TIMELINE', '기간 (시작~마감)'],
  ['CREATED', '등록일 (업무 생성일시)'], ['PRIORITY', '우선순위'], ['DESCRIPTION', '설명'],
]
const CUSTOM = [
  ['TEXT', '텍스트'], ['LONG_TEXT', '긴 텍스트'], ['NUMBER', '숫자'], ['DATE', '날짜'], ['DATETIME', '날짜+시간'],
  ['PEOPLE', '사람'], ['DROPDOWN', '드롭다운'], ['LINK', '링크'], ['PHONE', '전화번호'],
]
const LABEL = Object.fromEntries([...CORE, ...CUSTOM, ['IGNORE', '가져오지 않음'], ['ITEM_ID', '원본 아이템 ID (댓글 연결·원본 ID 보관)'], ['NAME', '업무명']])
const isCore = (f) => CORE.some(([k]) => k === f)
const isRel = (f) => typeof f === 'string' && f.startsWith('REL:')
/** 'PPL:워크스페이스ID' = 사람 컬럼 + 후보 워크스페이스 (이름 그대로 보존) */
const isPpl = (f) => typeof f === 'string' && f.startsWith('PPL:')

/** 이름 비교용 줄기: 워크스페이스 '고객사관리' ↔ 열 '고객사명' 처럼 흔한 접미어를 떼고 비교 */
const stem = (s, re) => { const t = (s || '').replace(/\s+/g, '').toLowerCase(); const u = t.replace(re, ''); return u.length >= 2 ? u : t }
const BOARD_SUFFIX = /(관리|목록|리스트|현황|정보|대장)$/
const HEADER_SUFFIX = /(명칭|명|이름|코드|구분)$/
/** 엑셀 열 이름과 같은 이름의 워크스페이스 (연결 추천) */
const suggestLink = (header, boards) => boards.find((b) => stem(b.name, BOARD_SUFFIX) === stem(header, HEADER_SUFFIX))

/** 엑셀 파일 → 새 워크스페이스 */
export default function MondayImport() {
  const { users, boards, refreshBoards } = useApp()
  const [mode, setMode] = useState('new') // new: 새 워크스페이스, update: 기존 워크스페이스 갱신(다시 이관)
  const [targetId, setTargetId] = useState(null)
  const [deleteMissing, setDeleteMissing] = useState(false)
  const [confirm, confirmDialog] = useConfirm()
  const [isPublic, setPublic] = useState(true)
  const [showProgress, setShowProgress] = useState(true) // 업무 관리용이면 켜고, 고객사·직원 목록이면 끔
  const [views, setViews] = useState(() => VIEW_TYPES.map((v) => v.type)) // 만들 보기 탭 (표는 필수)
  const toast = useToast()
  const [file, setFile] = useState(null)
  const [pv, setPv] = useState(null)
  const [busy, setBusy] = useState(false)
  const [over, setOver] = useState(false)
  const [boardName, setBoardName] = useState('')
  const [columns, setColumns] = useState({})
  const [subColumns, setSubColumns] = useState({})
  const [people, setPeople] = useState({})
  const [done, setDone] = useState(null)
  const inputRef = useRef(null)

  const check = async (f) => {
    if (!f) return
    if (!/\.xlsx$/i.test(f.name)) { toast('.xlsx 엑셀 파일을 선택하세요.', 'error'); return }
    setBusy(true); setDone(null)
    try {
      const r = await api.upload('/api/import/monday/preview', f)
      setFile(f); setPv(r); setBoardName(r.boardName || '')
      // 같은 이름의 워크스페이스가 이미 있으면 '기존 워크스페이스 갱신'을 먼저 제안
      const same = boards.find((b) => b.canManage !== false && b.name.trim() === (r.boardName || '').trim())
      setMode(same ? 'update' : 'new'); setTargetId(same?.id || null); setDeleteMissing(false)
      // 텍스트·드롭다운 열인데 이름이 같은 워크스페이스가 있으면 그 워크스페이스 연결을 미리 골라 둠
      setColumns(Object.fromEntries(r.columns.map((c) => {
        const b = ['TEXT', 'DROPDOWN'].includes(c.field) && suggestLink(c.header, boards.filter((x) => x.name.trim() !== (r.boardName || '').trim()))
        return [c.header, b ? `REL:${b.id}` : c.field]
      })))
      setSubColumns(Object.fromEntries(r.subColumns.map((c) => [c.header, c.field])))
      // 기존 계정이 있으면 연결, 없으면 비활성 계정 새로 만들기(0)
      setPeople(Object.fromEntries(r.people.map((p) => [p.name, p.userId ?? 0])))
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false); if (inputRef.current) inputRef.current.value = '' }
  }

  const target = boards.find((b) => b.id === targetId)
  // 갱신할 워크스페이스를 고르면, 이미 '사람 + 후보 워크스페이스'로 쓰는 컬럼은 같은 설정으로 미리 골라 둠
  useEffect(() => {
    if (mode !== 'update' || !targetId || !pv) return
    api.get(`/api/boards/${targetId}`).then((tb) => {
      let ps = {}
      try { ps = JSON.parse(tb.columnConfig || '{}').peopleSources || {} } catch { ps = {} }
      setColumns((cur) => {
        const next = { ...cur }
        for (const h of Object.keys(cur)) {
          const col = (tb.columns || []).find((c) => c.scope === 'ITEM' && c.name.trim() === h.trim())
          if (col && col.type === 'PEOPLE' && ps[col.id]) next[h] = `PPL:${ps[col.id]}`
        }
        return next
      })
    }).catch(() => {})
  }, [mode, targetId, pv])
  const apply = async () => {
    if (mode === 'update') {
      if (!target) { toast('갱신할 워크스페이스를 선택하세요.', 'error'); return }
      if (!(await confirm(`'${target.name}' 워크스페이스를 이 엑셀 내용으로 갱신할까요?\n같은 항목은 엑셀 값으로 덮어쓰고, 없는 항목은 새로 만듭니다.${deleteMissing ? '\n엑셀에 없는 항목은 삭제됩니다(되돌릴 수 없음).' : ''}`, { okText: '갱신', danger: deleteMissing }))) return
    }
    setBusy(true)
    try {
      const form = new FormData()
      form.append('file', file)
      form.append('options', (() => {
        // 'REL:워크스페이스ID' 는 텍스트 컬럼으로 넣고 links 로 연결 대상 전달
        // 'PPL:워크스페이스ID' 는 사람 컬럼으로 넣고 peopleSources 로 후보 워크스페이스 전달
        const cols = {}, links = {}, peopleSources = {}
        for (const [h, f] of Object.entries(columns)) {
          if (isRel(f)) { cols[h] = 'TEXT'; links[h] = Number(f.slice(4)) }
          else if (isPpl(f)) { cols[h] = 'PEOPLE'; peopleSources[h] = Number(f.slice(4)) }
          else cols[h] = f
        }
        return JSON.stringify(mode === 'update'
          ? { columns: cols, subColumns, people, targetBoardId: targetId, deleteMissing, links, peopleSources }
          : { boardName, columns: cols, subColumns, people, isPublic, views, showProgress, links, peopleSources })
      })())
      const r = await api.postForm('/api/import/monday/apply', form)
      setDone(r); refreshBoards()
      toast(r.replaced ? `'${r.boardName}' 워크스페이스를 갱신했습니다.` : `'${r.boardName}' 워크스페이스로 가져왔습니다.`, 'success')
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }

  const counts = useMemo(() => {
    const v = Object.values(people)
    return { link: v.filter((x) => x > 0).length, create: v.filter((x) => x === 0).length, none: v.filter((x) => x == null).length }
  }, [people])
  const customCount = pv ? [...Object.values(columns), ...Object.values(subColumns)].filter((f) => !isCore(f) && f !== 'IGNORE' && f !== 'ITEM_ID' && f !== 'NAME').length
    + Object.values(columns).filter((f) => f === 'ASSIGNEE').length + Object.values(subColumns).filter((f) => f === 'ASSIGNEE').length : 0
  const statusCol = pv && Object.entries(columns).find(([, f]) => f === 'STATUS')
  const setAllPeople = (v) => setPeople(Object.fromEntries(pv.people.map((p) => [p.name, v === 'match' ? (p.userId ?? 0) : v])))

  return (
    <div>
      <PageHeader title="엑셀로 워크스페이스 만들기" icon="upload"
        subtitle="업무 엑셀 파일을 올리면 원본 값을 그대로 새 워크스페이스로 옮겨 드립니다." />
      <div className="page page-narrow">
        {done ? (
          <div className="card"><div className="card-body">
            <h3 style={{ marginTop: 0 }}><Icon name="check" size={18} /> {done.replaced ? `'${done.boardName}' 갱신 완료` : '가져오기 완료'}</h3>
            <div className="import-summary">
              {done.replaced ? <>
                <span className="tag tag-accent">덮어쓴 항목 {done.updatedItems}</span>
                <span className="tag tag-success">새 항목 {done.items + done.subitems}</span>
                {done.deletedItems > 0 && <span className="tag tag-danger">삭제 {done.deletedItems}</span>}
              </> : <>
              <span className="tag tag-accent">그룹 {done.groups}</span>
              <span className="tag tag-success">업무 {done.items}</span></>}
              {done.subitems > 0 && <span className="tag">하위 업무 {done.subitems}</span>}
              {done.updates > 0 && <span className="tag">업데이트 {done.updates}</span>}
              {(!done.replaced || done.columns > 0) && <span className="tag">{done.replaced ? '새 컬럼' : '컬럼'} {done.columns}</span>}
              {done.newStatuses.length > 0 && <span className="tag">새 상태: {done.newStatuses.join(', ')}</span>}
              {done.createdUsers.length > 0 && <span className="tag">새 계정(비활성) {done.createdUsers.length}명</span>}
            </div>
            {done.warnings.map((w, i) => <div key={i} className="form-warn">{w}</div>)}
            {done.createdUsers.length > 0 && <p className="hint">새로 만든 계정은 로그인할 수 없는 비활성 상태입니다. 사용자 관리에서 아이디(m0001 형식)를 바꾸고 '계정 사용'을 켠 뒤 비밀번호를 초기화하면 바로 쓸 수 있습니다.</p>}
            {done.newStatuses.length > 0 && <p className="hint">새로 만든 상태 중 '완료'로 칠 것이 있으면 워크스페이스 설정에서 완료 표시를 켜 주세요(진행률 계산에 반영).</p>}
            <div className="form-actions">
              <button className="btn" onClick={() => { setDone(null); setPv(null); setFile(null) }}>다른 보드 가져오기</button>
              <button className="btn btn-primary" onClick={() => navigate(`/board/${done.boardId}/table`)}>워크스페이스 열기</button>
            </div>
          </div></div>
        ) : !pv ? (
          <div className="card"><div className="card-body">
            <ol className="steps">
              <li>옮길 보드를 엑셀(.xlsx)로 내보냅니다. 첫 시트는 업무, 두 번째 시트는 업데이트(댓글)입니다.</li>
              <li>댓글·하위 업무까지 옮기려면 내보낼 때 <b>업데이트 포함</b>, <b>하위 아이템 포함</b>을 켭니다.</li>
              <li>받은 .xlsx 파일을 아래에 올리면 저장 전에 열 매핑과 사람 연결을 확인할 수 있습니다. 파일 하나가 워크스페이스 하나가 됩니다.</li>
            </ol>
            <div className={`dropzone ${over ? 'over' : ''}`} role="button" tabIndex={0}
              onClick={() => inputRef.current?.click()}
              onKeyDown={(e) => { if (e.key === 'Enter') inputRef.current?.click() }}
              onDragOver={(e) => { e.preventDefault(); setOver(true) }} onDragLeave={() => setOver(false)}
              onDrop={(e) => { e.preventDefault(); setOver(false); check(e.dataTransfer.files[0]) }}>
              <Icon name="sheet" size={22} />
              <div>{busy ? '파일 분석 중… (큰 파일은 10초 정도 걸립니다)' : <>엑셀 파일을 끌어다 놓거나 <u>클릭해서 선택</u></>}</div>
              <div className="hint" style={{ margin: 0 }}>.xlsx · 최대 30MB · 20,000건</div>
              <input ref={inputRef} type="file" accept=".xlsx" hidden onChange={(e) => check(e.target.files[0])} />
            </div>
          </div></div>
        ) : (
          <>
            <div className="card"><div className="card-body">
              <div className="import-file"><Icon name="sheet" size={14} /> {file?.name}</div>
              <div className="import-summary">
                <span className="tag tag-accent">그룹 {pv.groups.length}</span>
                <span className="tag tag-success">업무 {pv.itemCount}</span>
                {pv.subitemCount > 0 && <span className="tag">하위 업무 {pv.subitemCount}</span>}
                <span className="tag">업데이트 {pv.updateCount}{pv.replyCount ? ` (답글 ${pv.replyCount})` : ''}</span>
                <span className="tag">새 컬럼 {customCount}</span>
              </div>
              {pv.warnings.map((w, i) => <div key={i} className="form-warn">{w}</div>)}
              {pv.updateFiles > 0 && <div className="form-warn">첨부파일이 붙어 있던 업데이트 {pv.updateFiles}건: 엑셀에는 파일 자체가 없어 본문만 옮겨집니다.</div>}
              <div className="field" style={{ marginTop: '0.75rem' }}><span>가져오는 곳</span>
                <div className="seg">
                  <button type="button" className={mode === 'new' ? 'active' : ''} onClick={() => setMode('new')}>새 워크스페이스 만들기</button>
                  <button type="button" className={mode === 'update' ? 'active' : ''} onClick={() => setMode('update')}>기존 워크스페이스 갱신 (다시 이관)</button>
                </div>
              </div>
              {mode === 'update' ? <>
                <label className="field"><span>갱신할 워크스페이스</span>
                  <select className="input" value={targetId || ''} onChange={(e) => setTargetId(Number(e.target.value) || null)}>
                    <option value="">선택하세요</option>
                    {boards.filter((b) => b.canManage !== false).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                  </select></label>
                <div className="import-note">
                  <div><Icon name="check" size={13} /> 워크스페이스 이름·설정·보기 탭·컬럼 설정·연결 컬럼·멤버는 그대로 둡니다.</div>
                  <div><Icon name="check" size={13} /> 같은 항목(원본 아이템 ID, 없으면 같은 이름)은 엑셀 값으로 덮어씁니다. 엑셀에 없는 컬럼 값·댓글·첨부는 유지됩니다.</div>
                  <div><Icon name="check" size={13} /> 항목 ID가 그대로라 다른 워크스페이스에서 이 항목을 가리키던 연결이 끊어지지 않습니다.</div>
                  <div><Icon name="check" size={13} /> 없는 항목·그룹·컬럼은 새로 만듭니다. 연결 컬럼은 이름으로 대상을 찾아 연결합니다.</div>
                </div>
                <label className="check-line">
                  <input type="checkbox" checked={deleteMissing} onChange={(e) => setDeleteMissing(e.target.checked)} />
                  <span>엑셀에 없는 항목 삭제 <small className="muted">끄면 엑셀에 없는 기존 항목은 그대로 남습니다. 켜면 하위 항목·댓글·첨부까지 삭제되며 되돌릴 수 없습니다.</small></span>
                </label>
              </> : <>
              <div className="field"><span>공개 범위</span><VisibilityPicker value={isPublic} onChange={setPublic} /></div>
              <label className="field"><span>새 워크스페이스 이름</span>
                <input className="input" maxLength={100} value={boardName} onChange={(e) => setBoardName(e.target.value)} /></label>
              <div className="field"><span>보기 탭 <small className="muted">표는 필수 · 나중에 탭 메뉴에서 추가·삭제 가능</small></span>
                <div className="view-picks">
                  {VIEW_TYPES.map((v) => {
                    const req = v.type === 'table', on = req || views.includes(v.type)
                    return (
                      <label key={v.type} className={`view-pick ${on ? 'on' : ''} ${req ? 'req' : ''}`} title={req ? '표 보기는 항상 만들어집니다' : undefined}>
                        <input type="checkbox" checked={on} disabled={req}
                          onChange={() => setViews(on ? views.filter((x) => x !== v.type) : [...views, v.type])} />
                        <Icon name={v.icon} size={14} /> {v.label}
                      </label>
                    )
                  })}
                  <button type="button" className="link small" onClick={() => setViews(['table'])}>표만</button>
                  <button type="button" className="link small" onClick={() => setViews(VIEW_TYPES.map((v) => v.type))}>전체</button>
                </div>
              </div>
              <label className="check-line">
                <input type="checkbox" checked={showProgress} onChange={(e) => setShowProgress(e.target.checked)} />
                <span>진행률(%) 표시 <small className="muted">업무 관리용이면 켜고, 고객사·직원 목록처럼 완료 개념이 없으면 끄세요. 나중에 워크스페이스 설정에서 바꿀 수 있습니다.</small></span>
              </label>
              </>}
              <h4 className="section-label">그룹</h4>
              <div className="import-summary">
                {pv.groups.map((g, i) => <span key={i} className="tag">{g.name} · {g.items}{g.subitems ? ` (+하위 ${g.subitems})` : ''}</span>)}
              </div>
            </div></div>

            <MappingCard title="업무 열" note="업무명은 'Name' 열에서 가져옵니다. 기본 필드가 아닌 열은 모두 워크스페이스 컬럼으로 만들어 원래 값을 그대로 보존합니다. 다른 워크스페이스 항목을 가리키는 열(고객사, 솔루션 등)은 '연결'을 고르면 이름으로 찾아 연결합니다."
              cols={pv.columns} value={columns} onChange={setColumns}
              linkBoards={boards.filter((b) => mode === 'update' ? b.id !== targetId : b.name.trim() !== boardName.trim())} />
            {pv.subColumns.length > 0 && (
              <MappingCard title="하위 업무 열" note="하위 아이템은 상위 업무 아래의 하위 업무로 만들어지고, 이 열들은 하위 업무 전용 컬럼이 됩니다."
                cols={pv.subColumns} value={subColumns} onChange={setSubColumns} />
            )}

            {pv.statuses.length > 0 && (
              <div className="card">
                <div className="card-header"><span><Icon name="filter" size={15} /> 상태 ({statusCol ? statusCol[0] : '상태 열'})</span></div>
                <div className="card-body">
                  <div className="import-summary">
                    {pv.statuses.map((s) => (
                      <span key={s.label} className={`tag ${s.target ? 'tag-success' : ''}`} title={s.label}>
                        {s.label.length > 30 ? `${s.label.slice(0, 30)}…` : s.label} {s.target ? (s.target === s.label ? '' : `→ ${s.target}`) : '→ 새 상태'} · {s.count}
                      </span>
                    ))}
                  </div>
                  <p className="hint" style={{ marginBottom: 0 }}>같은 의미의 기본 상태(완료·처리중·막힘·대기)는 그대로 쓰고, 나머지 라벨은 원래 이름 그대로 새 상태로 추가됩니다.</p>
                </div>
              </div>
            )}

            {pv.people.length > 0 && (
              <div className="card">
                <div className="card-header">
                  <span><Icon name="users" size={15} /> 담당자·작성자 연결</span>
                  <span className="muted" style={{ fontWeight: 400, fontSize: '0.8125rem' }}>연결 {counts.link} · 새 계정 {counts.create} · 이름만 {counts.none}</span>
                </div>
                <div className="card-body tight">
                  <div className="row gap-sm" style={{ padding: '6px 4px 10px', flexWrap: 'wrap' }}>
                    <span className="muted small">한꺼번에:</span>
                    <button className="btn btn-sm" onClick={() => setAllPeople('match')}>있으면 연결 · 없으면 새 계정</button>
                    <button className="btn btn-sm" onClick={() => setAllPeople(null)}>모두 이름만 보존</button>
                  </div>
                  <div className="table-wrap import-table" style={{ border: 0 }}>
                    <table className="table">
                      <thead><tr><th>엑셀 속 이름</th><th>담당 건수</th><th style={{ width: '17rem' }}>ISAC 계정</th></tr></thead>
                      <tbody>{pv.people.map((p) => (
                        <tr key={p.name}>
                          <td><b>{p.name}</b></td>
                          <td className="num">{p.count || <span className="muted">업데이트만</span>}</td>
                          <td>
                            <select className="input input-sm" style={{ width: '100%' }} value={people[p.name] ?? ''}
                              onChange={(e) => setPeople({ ...people, [p.name]: e.target.value === '' ? null : Number(e.target.value) })}>
                              <option value="0">＋ 새 계정 만들기 (비활성)</option>
                              <option value="">연결 안 함 (이름만 보존)</option>
                              {p.userId && !users.some((u) => u.id === p.userId) && <option value={p.userId}>{p.name} (기존 비활성 계정)</option>}
                              {users.map((u) => <option key={u.id} value={u.id}>{u.name} ({u.username})</option>)}
                            </select>
                          </td>
                        </tr>
                      ))}</tbody>
                    </table>
                  </div>
                  <p className="hint">새 계정은 로그인할 수 없는 비활성 계정(아이디 m0001 형식)으로 만들어집니다. 업데이트 작성자 이름은 연결 여부와 관계없이 원래 이름으로 보존됩니다.</p>
                </div>
              </div>
            )}

            <div className="form-actions" style={{ marginBottom: '2rem' }}>
              <button className="btn" style={{ marginRight: 'auto' }} onClick={() => { setPv(null); setFile(null) }} disabled={busy}>다른 파일 선택</button>
              <button className="btn btn-primary" onClick={apply} disabled={busy || (mode === 'new' ? !boardName.trim() : !target) || pv.itemCount === 0}>
                {busy ? '가져오는 중… (수천 건이면 1분 정도 걸립니다)' : mode === 'update' ? `'${target?.name || '…'}' 갱신 (${pv.itemCount + pv.subitemCount}건)` : `새 워크스페이스로 가져오기 (${pv.itemCount + pv.subitemCount}건)`}
              </button>
            </div>
          </>
        )}
      </div>
      {confirmDialog}
    </div>
  )
}

function MappingCard({ title, note, cols, value, onChange, linkBoards }) {
  return (
    <div className="card">
      <div className="card-header"><span><Icon name="list" size={15} /> {title}</span></div>
      <div className="card-body tight">
        <p className="hint" style={{ margin: '4px 4px 8px' }}>{note}</p>
        <div className="table-wrap import-table" style={{ border: 0, maxHeight: 'none' }}>
          <table className="table">
            <thead><tr><th>엑셀 열</th><th>예시 값</th><th style={{ width: '17rem' }}>ISAC 에서</th></tr></thead>
            <tbody>{cols.map((c) => (
              <tr key={c.header} className={`${value[c.header] === 'IGNORE' ? 'inactive' : ''} ${isRel(value[c.header]) || isPpl(value[c.header]) ? 'is-link' : ''}`}>
                <td><b>{c.header}</b><div className="muted" style={{ fontSize: '0.75rem' }}>{c.filled}건 입력됨</div></td>
                <td style={{ whiteSpace: 'normal' }}>{c.samples.length ? c.samples.join(' · ') : <span className="muted">비어 있음</span>}</td>
                <td>
                  {c.field === 'ITEM_ID' ? <span className="muted small">{LABEL.ITEM_ID}</span> : (
                    <select className="input input-sm" style={{ width: '100%' }} value={value[c.header]}
                      onChange={(e) => onChange({ ...value, [c.header]: e.target.value })}>
                      <optgroup label="기본 필드">{CORE.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</optgroup>
                      <optgroup label="워크스페이스 컬럼으로 (원본 보존)">{CUSTOM.map(([k, l]) => <option key={k} value={k}>컬럼 · {l}</option>)}</optgroup>
                      {linkBoards?.length > 0 && (
                        <optgroup label="사람 (후보를 다른 워크스페이스에서, 이름 보존)">
                          {linkBoards.map((b) => <option key={b.id} value={`PPL:${b.id}`}>사람 · 후보 {b.name}</option>)}
                          {isPpl(value[c.header]) && !linkBoards.some((b) => `PPL:${b.id}` === value[c.header]) && <option value={value[c.header]}>사람 · 후보 (선택한 워크스페이스)</option>}
                        </optgroup>
                      )}
                      {linkBoards?.length > 0 && (
                        <optgroup label="다른 워크스페이스 항목과 연결">
                          {linkBoards.map((b) => <option key={b.id} value={`REL:${b.id}`}>연결 → {b.name}</option>)}
                          {isRel(value[c.header]) && !linkBoards.some((b) => `REL:${b.id}` === value[c.header]) && <option value={value[c.header]}>연결 → (선택한 워크스페이스)</option>}
                        </optgroup>
                      )}
                      <option value="IGNORE">가져오지 않음</option>
                    </select>
                  )}
                </td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
