import React, { useMemo, useState } from 'react'
import { api } from '../api.js'
import { useToast } from './common.jsx'
import { Icon } from './icons.jsx'
import { columnsFor, builtinLabel, distinctValues, TYPE_LABEL } from './Fields.jsx'

/**
 * 양식 보기: 로그인한 사용자가 양식을 채워 제출하면 지정한 그룹에 지정한 상태로 업무가 만들어진다.
 * 보기 설정(config): { groupId, statusId, intro, fields: [{ key: 'description' | 컬럼ID, required }] }
 * 제목(업무명)은 항상 첫 칸·필수.
 */
const FORM_TYPES = ['TEXT', 'LONG_TEXT', 'NUMBER', 'DATE', 'DATETIME', 'DROPDOWN', 'LINK', 'PHONE', 'PEOPLE']

function readConfig(view, board) {
  let c = null
  try { c = view?.config ? JSON.parse(view.config) : null } catch { c = null }
  const accept = board.statuses.find((s) => s.label.replace(/\s/g, '') === '접수') || board.statuses[0]
  return {
    groupId: c?.groupId || board.groups[0]?.id || null,
    statusId: c?.statusId || accept?.id || null,
    intro: c?.intro || '',
    fields: Array.isArray(c?.fields) ? c.fields : [],
  }
}

export default function FormView({ board, view, canManage, items, onSubmitted }) {
  const toast = useToast()
  const cfg = useMemo(() => readConfig(view, board), [view, board])
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState('')
  const [vals, setVals] = useState({})
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(null)   // 방금 접수한 업무

  const cols = columnsFor(board, 'ITEM').filter((c) => FORM_TYPES.includes(c.type))
  const fieldDef = (key) => key === 'description'
    ? { key, label: '내용 (설명)', type: 'LONG_TEXT' }
    : (() => { const c = cols.find((x) => String(x.id) === String(key)); return c ? { key: String(c.id), label: c.name, type: c.type, col: c } : null })()
  const fields = cfg.fields.map((f) => ({ ...fieldDef(f.key), required: !!f.required })).filter((f) => f.label)
  const nameLabel = builtinLabel(board, 'name')
  const group = board.groups.find((g) => g.id === cfg.groupId)
  const status = board.statuses.find((s) => s.id === cfg.statusId)

  const submit = async (e) => {
    e.preventDefault()
    if (!name.trim()) { toast(`${nameLabel}을(를) 입력하세요.`, 'error'); return }
    const miss = fields.find((f) => f.required && !String(vals[f.key] || '').trim())
    if (miss) { toast(`'${miss.label}'을(를) 입력하세요.`, 'error'); return }
    setBusy(true)
    try {
      const it = await api.post(`/api/views/${view.id}/submit`, { name: name.trim(), values: vals })
      setDone(it); setName(''); setVals({})
      onSubmitted && onSubmitted()
    } catch (err) { toast(err.message, 'error') } finally { setBusy(false) }
  }

  if (editing) return <FormSettings board={board} view={view} cfg={cfg} cols={cols} fieldDef={fieldDef}
    onClose={() => setEditing(false)} onSaved={() => { setEditing(false); onSubmitted && onSubmitted() }} />

  return (
    <div className="form-view">
      <div className="form-card">
        <div className="form-head">
          <div>
            <h2>{view.name}</h2>
            {cfg.intro ? <p className="form-intro">{cfg.intro}</p> : null}
            <p className="form-meta">제출하면 <b>{group?.name || '첫 그룹'}</b> 그룹에 <b>{status?.label || '기본 상태'}</b> 상태로 등록됩니다.</p>
          </div>
          {canManage && <button className="btn btn-white btn-sm" onClick={() => setEditing(true)}><Icon name="settings" size={13} /> 양식 설정</button>}
        </div>

        {done ? (
          <div className="form-done">
            <Icon name="check" size={28} />
            <b>접수되었습니다.</b>
            <span>{done.keyNum && board.keyPrefix ? `${board.keyPrefix}-${done.keyNum} · ` : ''}{done.name}</span>
            <button className="btn btn-primary btn-sm" onClick={() => setDone(null)}>새로 작성</button>
          </div>
        ) : (
          <form onSubmit={submit} className="form-body">
            <label className="field"><span>{nameLabel} <em className="req">*</em></span>
              <input className="input" value={name} maxLength={300} onChange={(e) => setName(e.target.value)} autoFocus /></label>
            {fields.map((f) => (
              <label key={f.key} className="field"><span>{f.label} {f.required && <em className="req">*</em>}</span>
                <FormInput f={f} items={items} value={vals[f.key] || ''} onChange={(v) => setVals((x) => ({ ...x, [f.key]: v }))} />
              </label>
            ))}
            {fields.length === 0 && canManage && <p className="hint">'양식 설정'에서 입력받을 항목을 추가하세요.</p>}
            <div className="form-actions"><button className="btn btn-primary" disabled={busy}>제출</button></div>
          </form>
        )}
      </div>
    </div>
  )
}

function FormInput({ f, items, value, onChange }) {
  const set = (e) => onChange(e.target.value)
  switch (f.type) {
    case 'LONG_TEXT': return <textarea className="input" rows={6} value={value} maxLength={10000} onChange={set} />
    case 'NUMBER': return <input className="input" type="number" value={value} onChange={set} />
    case 'DATE': return <input className="input" type="date" value={value} onChange={set} />
    case 'DATETIME': return <input className="input" type="datetime-local" value={value.replace(' ', 'T')} onChange={(e) => onChange(e.target.value.replace('T', ' '))} />
    case 'PHONE': return <input className="input" type="tel" value={value} onChange={set} />
    case 'DROPDOWN': {
      const opts = distinctValues(items, f.col).map(([v]) => v)
      return (
        <select className="input" value={value} onChange={set}>
          <option value="">선택하세요</option>
          {opts.map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      )
    }
    default: return <input className="input" value={value} maxLength={2000} onChange={set} />
  }
}

function FormSettings({ board, view, cfg, cols, fieldDef, onClose, onSaved }) {
  const toast = useToast()
  const [c, setC] = useState({ ...cfg, fields: cfg.fields.filter((f) => fieldDef(f.key)) })
  const [busy, setBusy] = useState(false)
  const used = new Set(c.fields.map((f) => String(f.key)))
  const candidates = [{ key: 'description', label: '내용 (설명)', type: 'LONG_TEXT' }, ...cols.map((x) => ({ key: String(x.id), label: x.name, type: x.type }))].filter((x) => !used.has(x.key))
  const setField = (i, p) => setC((x) => ({ ...x, fields: x.fields.map((f, k) => (k === i ? { ...f, ...p } : f)) }))
  const move = (i, d) => setC((x) => { const f = [...x.fields]; const j = i + d; if (j < 0 || j >= f.length) return x; [f[i], f[j]] = [f[j], f[i]]; return { ...x, fields: f } })
  const save = async () => {
    setBusy(true)
    try {
      await api.put(`/api/views/${view.id}`, { config: JSON.stringify({ groupId: c.groupId, statusId: c.statusId, intro: c.intro.trim(), fields: c.fields }) })
      toast('양식을 저장했습니다.', 'success'); onSaved()
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }
  return (
    <div className="form-view">
      <div className="form-card">
        <div className="form-head"><h2>양식 설정 · {view.name}</h2></div>
        <div className="form-body">
          <div className="form-grid">
            <label className="field"><span>제출하면 들어갈 그룹</span>
              <select className="input" value={c.groupId || ''} onChange={(e) => setC({ ...c, groupId: Number(e.target.value) })}>
                {board.groups.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
              </select></label>
            <label className="field"><span>처음 상태</span>
              <select className="input" value={c.statusId || ''} onChange={(e) => setC({ ...c, statusId: Number(e.target.value) })}>
                {board.statuses.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
              </select></label>
          </div>
          <label className="field"><span>안내 문구 <small className="muted">(선택)</small></span>
            <input className="input" value={c.intro} maxLength={300} onChange={(e) => setC({ ...c, intro: e.target.value })} placeholder="예: 장애·작업 요청을 접수합니다. 긴급 건은 전화로도 알려 주세요." /></label>

          <div className="field"><span>입력 항목</span>
            <div className="form-fields">
              <div className="form-field-row fixed"><span className="grow">{builtinLabel(board, 'name')} <small className="muted">제목 · 항상 필수</small></span></div>
              {c.fields.map((f, i) => {
                const d = fieldDef(f.key)
                return (
                  <div key={f.key} className="form-field-row">
                    <span className="grow">{d.label} <small className="muted">{d.type === 'LONG_TEXT' && f.key === 'description' ? '설명' : TYPE_LABEL[d.type]}</small></span>
                    <label className="check-line"><input type="checkbox" checked={!!f.required} onChange={(e) => setField(i, { required: e.target.checked })} /><span>필수</span></label>
                    <button type="button" className="icon-btn" aria-label="위로" disabled={i === 0} onClick={() => move(i, -1)}><Icon name="arrowUp" size={13} /></button>
                    <button type="button" className="icon-btn" aria-label="아래로" disabled={i === c.fields.length - 1} onClick={() => move(i, 1)}><Icon name="arrowDown" size={13} /></button>
                    <button type="button" className="icon-btn" aria-label="빼기" onClick={() => setC({ ...c, fields: c.fields.filter((_, k) => k !== i) })}><Icon name="x" size={13} /></button>
                  </div>
                )
              })}
            </div>
            {candidates.length > 0 && (
              <select className="input input-sm form-add" value="" onChange={(e) => { if (e.target.value) setC({ ...c, fields: [...c.fields, { key: e.target.value, required: false }] }) }}>
                <option value="">+ 항목 추가</option>
                {candidates.map((x) => <option key={x.key} value={x.key}>{x.label}</option>)}
              </select>
            )}
            <p className="hint">작업상태는 위의 '처음 상태'로 고정됩니다. 연결(다른 워크스페이스) 컬럼은 양식에 넣을 수 없습니다.</p>
          </div>
          <div className="form-actions">
            <button type="button" className="btn" onClick={onClose}>취소</button>
            <button type="button" className="btn btn-primary" onClick={save} disabled={busy}>저장</button>
          </div>
        </div>
      </div>
    </div>
  )
}
