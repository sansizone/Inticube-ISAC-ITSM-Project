import React, { useMemo, useRef, useState } from 'react'
import { api } from '../api.js'
import { PageHeader, useApp, navigate } from '../App.jsx'
import { useToast } from './common.jsx'
import { Icon } from './icons.jsx'
import { VisibilityPicker } from './Workspace.jsx'

/** 열 매핑 선택지: 기본 필드 / 사용자 정의 컬럼 유형 */
const CORE = [
  ['STATUS', '상태'], ['ASSIGNEE', '담당자 (+사람 컬럼으로 원본 보존)'], ['DUE', '마감일'], ['START', '시작일'], ['TIMELINE', '기간 (시작~마감)'],
  ['CREATED', '등록일 (업무 생성일시)'], ['PRIORITY', '우선순위'], ['DESCRIPTION', '설명'],
]
const CUSTOM = [
  ['TEXT', '텍스트'], ['LONG_TEXT', '긴 텍스트'], ['NUMBER', '숫자'], ['DATE', '날짜'], ['DATETIME', '날짜+시간'],
  ['PEOPLE', '사람'], ['DROPDOWN', '드롭다운'], ['LINK', '링크'],
]
const LABEL = Object.fromEntries([...CORE, ...CUSTOM, ['IGNORE', '가져오지 않음'], ['ITEM_ID', '원본 아이템 ID (댓글 연결·원본 ID 보관)'], ['NAME', '업무명']])
const isCore = (f) => CORE.some(([k]) => k === f)

/** 엑셀 파일 → 새 워크스페이스 */
export default function MondayImport() {
  const { users, refreshBoards } = useApp()
  const [isPublic, setPublic] = useState(true)
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
      setColumns(Object.fromEntries(r.columns.map((c) => [c.header, c.field])))
      setSubColumns(Object.fromEntries(r.subColumns.map((c) => [c.header, c.field])))
      // 기존 계정이 있으면 연결, 없으면 비활성 계정 새로 만들기(0)
      setPeople(Object.fromEntries(r.people.map((p) => [p.name, p.userId ?? 0])))
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false); if (inputRef.current) inputRef.current.value = '' }
  }

  const apply = async () => {
    setBusy(true)
    try {
      const form = new FormData()
      form.append('file', file)
      form.append('options', JSON.stringify({ boardName, columns, subColumns, people, isPublic }))
      const r = await api.postForm('/api/import/monday/apply', form)
      setDone(r); refreshBoards()
      toast(`'${r.boardName}' 워크스페이스로 가져왔습니다.`, 'success')
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
            <h3 style={{ marginTop: 0 }}><Icon name="check" size={18} /> 가져오기 완료</h3>
            <div className="import-summary">
              <span className="tag tag-accent">그룹 {done.groups}</span>
              <span className="tag tag-success">업무 {done.items}</span>
              {done.subitems > 0 && <span className="tag">하위 업무 {done.subitems}</span>}
              {done.updates > 0 && <span className="tag">업데이트 {done.updates}</span>}
              <span className="tag">컬럼 {done.columns}</span>
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
              <div className="field" style={{ marginTop: '0.75rem' }}><span>공개 범위</span><VisibilityPicker value={isPublic} onChange={setPublic} /></div>
              <label className="field"><span>새 워크스페이스 이름</span>
                <input className="input" maxLength={100} value={boardName} onChange={(e) => setBoardName(e.target.value)} /></label>
              <h4 className="section-label">그룹</h4>
              <div className="import-summary">
                {pv.groups.map((g, i) => <span key={i} className="tag">{g.name} · {g.items}{g.subitems ? ` (+하위 ${g.subitems})` : ''}</span>)}
              </div>
            </div></div>

            <MappingCard title="업무 열" note="업무명은 'Name' 열에서 가져옵니다. 기본 필드가 아닌 열은 모두 워크스페이스 컬럼으로 만들어 원래 값을 그대로 보존합니다."
              cols={pv.columns} value={columns} onChange={setColumns} />
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
                  <p className="hint" style={{ marginBottom: 0 }}>같은 의미의 기본 상태(완료·진행중·막힘·대기)는 그대로 쓰고, 나머지 라벨은 원래 이름 그대로 새 상태로 추가됩니다.</p>
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
              <button className="btn btn-primary" onClick={apply} disabled={busy || !boardName.trim() || pv.itemCount === 0}>
                {busy ? '가져오는 중… (수천 건이면 1분 정도 걸립니다)' : `새 워크스페이스로 가져오기 (${pv.itemCount + pv.subitemCount}건)`}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function MappingCard({ title, note, cols, value, onChange }) {
  return (
    <div className="card">
      <div className="card-header"><span><Icon name="list" size={15} /> {title}</span></div>
      <div className="card-body tight">
        <p className="hint" style={{ margin: '4px 4px 8px' }}>{note}</p>
        <div className="table-wrap import-table" style={{ border: 0, maxHeight: 'none' }}>
          <table className="table">
            <thead><tr><th>엑셀 열</th><th>예시 값</th><th style={{ width: '17rem' }}>ISAC 에서</th></tr></thead>
            <tbody>{cols.map((c) => (
              <tr key={c.header} className={value[c.header] === 'IGNORE' ? 'inactive' : ''}>
                <td><b>{c.header}</b><div className="muted" style={{ fontSize: '0.75rem' }}>{c.filled}건 입력됨</div></td>
                <td style={{ whiteSpace: 'normal' }}>{c.samples.length ? c.samples.join(' · ') : <span className="muted">비어 있음</span>}</td>
                <td>
                  {c.field === 'ITEM_ID' ? <span className="muted small">{LABEL.ITEM_ID}</span> : (
                    <select className="input input-sm" style={{ width: '100%' }} value={value[c.header]}
                      onChange={(e) => onChange({ ...value, [c.header]: e.target.value })}>
                      <optgroup label="기본 필드">{CORE.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</optgroup>
                      <optgroup label="워크스페이스 컬럼으로 (원본 보존)">{CUSTOM.map(([k, l]) => <option key={k} value={k}>컬럼 · {l}</option>)}</optgroup>
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
