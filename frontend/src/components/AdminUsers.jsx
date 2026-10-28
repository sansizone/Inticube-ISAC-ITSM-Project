import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useApp } from '../App.jsx'
import { Avatar, Modal, useToast, useConfirm } from './common.jsx'
import { Icon } from './icons.jsx'

export function AdminUsers() {
  const { me, refreshUsers } = useApp()
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [list, setList] = useState([])
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState(null)
  const [temp, setTemp] = useState(null)
  const [q, setQ] = useState('')
  const [state, setState] = useState('all')
  const [sel, setSel] = useState(new Set())
  const [bulk, setBulk] = useState(null)
  const [bulkDone, setBulkDone] = useState(null)

  const load = () => api.get('/api/admin/users').then(setList).catch((e) => toast(e.message, 'error'))
  useEffect(() => { load() }, []) // eslint-disable-line

  const reset = async (u) => {
    if (!(await confirm(`${u.name}(${u.username})의 비밀번호를 초기화할까요?\n임시 비밀번호가 발급되며 다음 로그인 시 변경해야 합니다.`, { danger: false, okText: '초기화' }))) return
    try { setTemp(await api.post(`/api/admin/users/${u.id}/reset-password`)); load() } catch (e) { toast(e.message, 'error') }
  }

  const shown = list.filter((u) => (!q || u.name.includes(q) || u.username.includes(q) || (u.department || '').includes(q))
    && (state === 'all' || (state === 'active' ? u.active : !u.active)))
  const inactiveShown = shown.filter((u) => !u.active)
  const toggle = (id) => setSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })
  const allOn = inactiveShown.length > 0 && inactiveShown.every((u) => sel.has(u.id))
  const inactiveCount = list.filter((u) => !u.active).length

  return (
    <div>
      <div className="section-toolbar">
        <div className="filter-search"><Icon name="search" size={13} /><input placeholder="이름·아이디·부서 검색" value={q} onChange={(e) => setQ(e.target.value)} /></div>
        <div className="seg" role="group" aria-label="상태">
          <button className={state === 'all' ? 'active' : ''} onClick={() => setState('all')}>전체 {list.length}</button>
          <button className={state === 'active' ? 'active' : ''} onClick={() => setState('active')}>사용 {list.length - inactiveCount}</button>
          <button className={state === 'inactive' ? 'active' : ''} onClick={() => setState('inactive')}>비활성 {inactiveCount}</button>
        </div>
        <span className="grow" />
        {sel.size > 0 && <button className="btn btn-sm" onClick={() => setBulk(list.filter((u) => sel.has(u.id)))}><Icon name="check" size={14} /> 선택한 {sel.size}명 활성화</button>}
        <button className="btn btn-primary btn-sm" onClick={() => setCreating(true)}><Icon name="plus" size={14} /> 사용자 추가</button>
      </div>
      <div className="table-wrap">
        <table className="table">
          <thead><tr><th className="col-check">{inactiveShown.length > 0 && <input type="checkbox" aria-label="비활성 계정 모두 선택" title="보이는 비활성 계정 모두 선택" checked={allOn}
            onChange={() => setSel((s) => { const n = new Set(s); inactiveShown.forEach((u) => (allOn ? n.delete(u.id) : n.add(u.id))); return n })} />}</th><th>이름</th><th>아이디</th><th>부서</th><th>권한</th><th>상태</th><th>2단계</th><th>등록일</th><th /></tr></thead>
          <tbody>
            {shown.map((u) => (
              <tr key={u.id} className={u.active ? '' : 'inactive'}>
                <td className="col-check">{!u.active && <input type="checkbox" aria-label={`${u.name} 선택`} checked={sel.has(u.id)} onChange={() => toggle(u.id)} />}</td>
                <td><span className="row gap-sm"><Avatar user={u} size={22} /> {u.name}</span></td>
                <td>{u.username}</td>
                <td>{u.department || <span className="muted">-</span>}</td>
                <td>{u.role === 'ADMIN' ? <span className="tag tag-accent">관리자</span> : <span className="muted">일반</span>}</td>
                <td>{u.active ? (u.mustChangePassword ? <span className="tag">변경 대기</span> : <span className="tag tag-success">사용</span>) : <span className="tag tag-danger">비활성</span>}</td>
                <td>{u.mfaEnabled ? <span className="tag tag-success" title="2단계 인증 켜짐">켜짐</span> : <span className="muted small">-</span>}</td>
                <td className="muted small">{u.createdAt.slice(0, 10)}</td>
                <td style={{ whiteSpace: 'nowrap', textAlign: 'right' }}>
                  {!u.active && <><button className="btn btn-sm btn-activate" title="아이디·부서를 확인하고 활성화 + 임시 비밀번호 발급" onClick={() => setBulk([u])}><Icon name="check" size={13} /> 활성화</button>{' '}</>}
                  <button className="btn btn-sm" onClick={() => setEditing(u)}>수정</button>{' '}
                  <button className="btn btn-sm" onClick={() => reset(u)}>비밀번호 초기화</button>
                  {u.mfaEnabled && <>{' '}<button className="btn btn-sm" title="기기 분실 시 2단계 인증 초기화" onClick={async () => {
                    if (!(await confirm(`${u.name}(${u.username})의 2단계 인증을 초기화할까요?\n다음 로그인부터 인증 앱 없이 로그인되며, 본인이 다시 설정할 수 있습니다.`, { okText: '초기화' }))) return
                    try { await api.post(`/api/admin/users/${u.id}/mfa-reset`); toast('2단계 인증을 초기화했습니다.'); load() } catch (e) { toast(e.message, 'error') }
                  }}>2단계 초기화</button></>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {creating && <UserForm onClose={() => setCreating(false)} onDone={(r) => { setTemp(r); load(); refreshUsers() }} />}
      {editing && <UserForm user={editing} isSelf={editing.id === me.id} onClose={() => setEditing(null)} onDone={() => { load(); refreshUsers(); toast('저장했습니다.', 'success') }} />}
      {bulk && <BulkActivate users={bulk} onClose={() => setBulk(null)} onDone={(r) => { setBulk(null); setSel(new Set()); setBulkDone(r); load(); refreshUsers() }} />}
      {bulkDone && (
        <Modal title={`${bulkDone.length}명 활성화 완료`} width={640} onClose={() => setBulkDone(null)}
          footer={<>
            <button className="btn" style={{ marginRight: 'auto' }} onClick={() => navigator.clipboard?.writeText(bulkDone.map((r) => `${r.name}\t${r.username}\t${r.temporaryPassword}`).join('\n')).then(() => toast('이름·아이디·임시 비밀번호를 복사했습니다. (엑셀에 붙여넣기 가능)'))}>전체 복사</button>
            <button className="btn btn-primary" onClick={() => setBulkDone(null)}>확인</button>
          </>}>
          <p>각 사용자에게 임시 비밀번호를 안전한 경로로 전달하세요. <b>이 창을 닫으면 다시 볼 수 없습니다.</b> 첫 로그인 때 비밀번호를 바꿔야 합니다.</p>
          <div className="table-wrap" style={{ maxHeight: '50vh', overflowY: 'auto' }}>
            <table className="table">
              <thead><tr><th>이름</th><th>아이디</th><th>임시 비밀번호</th></tr></thead>
              <tbody>{bulkDone.map((r) => <tr key={r.id}><td>{r.name}</td><td>{r.username}</td><td><code>{r.temporaryPassword}</code></td></tr>)}</tbody>
            </table>
          </div>
        </Modal>
      )}
      {temp && (
        <Modal title="임시 비밀번호 발급" onClose={() => setTemp(null)} footer={<button className="btn btn-primary" onClick={() => setTemp(null)}>확인</button>}>
          <p>아래 임시 비밀번호를 <b>{temp.username}</b> 사용자에게 안전한 경로로 전달하세요. 이 창을 닫으면 다시 볼 수 없습니다.</p>
          <div className="temp-pw">
            <code>{temp.temporaryPassword}</code>
            <button className="btn btn-sm" onClick={() => navigator.clipboard?.writeText(temp.temporaryPassword).then(() => toast('복사했습니다.'))}>복사</button>
          </div>
          <p className="hint">사용자는 첫 로그인 시 비밀번호를 변경해야 합니다.</p>
        </Modal>
      )}
      {confirmDialog}
    </div>
  )
}

function UserForm({ user, isSelf, onClose, onDone }) {
  const toast = useToast()
  const [f, setF] = useState(user ? { ...user } : { username: '', name: '', department: '', role: 'MEMBER', active: true })
  const [busy, setBusy] = useState(false)
  const submit = async (e) => {
    e.preventDefault(); setBusy(true)
    try {
      const r = user
        ? await api.put(`/api/admin/users/${user.id}`, { name: f.name, department: f.department, role: f.role, active: f.active, username: f.username })
        : await api.post('/api/admin/users', { username: f.username, name: f.name, department: f.department, role: f.role })
      onDone(r); onClose()
    } catch (err) { toast(err.message, 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title={user ? '사용자 수정' : '사용자 추가'} onClose={onClose}>
      <form onSubmit={submit}>
        <label className="field"><span>아이디</span>
          <input className="input" value={f.username} onChange={(e) => setF({ ...f, username: e.target.value })} required pattern="[A-Za-z0-9._\-]{3,50}|[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(\.[A-Za-z0-9\-]+)+" maxLength={50} title="영문/숫자/._- 3~50자 또는 이메일 주소" autoFocus={!user} /></label>
        <label className="field"><span>이름</span><input className="input" value={f.name} maxLength={50} onChange={(e) => setF({ ...f, name: e.target.value })} required /></label>
        <label className="field"><span>부서 (선택)</span><input className="input" value={f.department || ''} maxLength={100} onChange={(e) => setF({ ...f, department: e.target.value })} /></label>
        <label className="field"><span>권한</span>
          <select className="input" value={f.role} onChange={(e) => setF({ ...f, role: e.target.value })} disabled={isSelf}>
            <option value="MEMBER">일반 사용자</option><option value="ADMIN">관리자</option>
          </select></label>
        {user && <label className="check"><input type="checkbox" checked={f.active} disabled={isSelf} onChange={(e) => setF({ ...f, active: e.target.checked })} /> 계정 사용</label>}
        {!user && <p className="hint">저장하면 임시 비밀번호가 발급됩니다.</p>}
        <div className="form-actions">
          <button type="button" className="btn" onClick={onClose}>취소</button>
          <button className="btn btn-primary" disabled={busy}>저장</button>
        </div>
      </form>
    </Modal>
  )
}

/** 비활성 계정 일괄 활성화: 아이디·부서를 정하고 한 번에 활성화 + 임시 비밀번호 발급 */
function BulkActivate({ users, onClose, onDone }) {
  const toast = useToast()
  const [rows, setRows] = useState(users.map((u) => ({ id: u.id, name: u.name, username: u.username, department: u.department || '' })))
  const [busy, setBusy] = useState(false)
  const set = (id, patch) => setRows((rs) => rs.map((r) => (r.id === id ? { ...r, ...patch } : r)))
  const placeholder = rows.filter((r) => /^m\d{4}$/.test(r.username)).length
  const bad = rows.filter((r) => !/^(?:[A-Za-z0-9._-]{3,50}|(?=.{3,50}$)[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)$/.test(r.username.trim()))
  const submit = async () => {
    setBusy(true)
    try { onDone(await api.post('/api/admin/users/bulk-activate', { users: rows.map((r) => ({ id: r.id, username: r.username.trim(), department: r.department })) })) }
    catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title={`${rows.length}명 활성화`} width={720} onClose={onClose}
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn-primary" disabled={busy || bad.length > 0} onClick={submit}>{busy ? '처리 중…' : '활성화하고 임시 비밀번호 발급'}</button>
      </>}>
      <p className="hint" style={{ marginTop: 0 }}>로그인에 쓸 아이디(사내 계정명 등)와 부서를 정하세요. 업무·댓글에 연결된 이름은 그대로 유지됩니다.
        {placeholder > 0 && <> 아직 자동 아이디(m0001 형식)인 사람이 <b>{placeholder}명</b> 있습니다. 그대로 두면 그 아이디로 로그인합니다.</>}</p>
      <div className="table-wrap" style={{ maxHeight: '55vh', overflowY: 'auto' }}>
        <table className="table bulk-table">
          <thead><tr><th>이름</th><th>아이디</th><th>부서 (선택)</th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td className="nowrap">{r.name}</td>
                <td><input className={`input input-sm ${bad.includes(r) ? 'input-error' : ''}`} value={r.username} maxLength={50} onChange={(e) => set(r.id, { username: e.target.value })} aria-label={`${r.name} 아이디`} /></td>
                <td><input className="input input-sm" value={r.department} maxLength={100} onChange={(e) => set(r.id, { department: e.target.value })} aria-label={`${r.name} 부서`} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {bad.length > 0 && <div className="form-error" style={{ marginTop: 8 }}>아이디는 영문/숫자/._- 3~50자 또는 이메일 주소만 가능합니다: {bad.map((r) => r.name).join(', ')}</div>}
    </Modal>
  )
}
