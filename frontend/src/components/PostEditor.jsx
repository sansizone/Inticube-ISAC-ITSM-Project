import React, { useEffect, useRef, useState } from 'react'
import { api } from '../api.js'
import { useApp, navigate, PageHeader } from '../App.jsx'
import { Spinner, useToast, useConfirm } from './common.jsx'
import { Icon } from './icons.jsx'
import { MarkdownEditor } from './markdown.jsx'
import { FileList } from './ItemDrawer.jsx'
import { fmtSize } from '../util.js'

export default function PostEditor({ postId, params }) {
  const { me, users, mentionNames, categories, refreshCategories } = useApp()
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const writable = categories.filter((c) => !c.adminOnlyWrite || me.role === 'ADMIN')
  const [loaded, setLoaded] = useState(!postId)
  const [f, setF] = useState({ categoryId: params.cat ? Number(params.cat) : null, title: '', content: '', pinned: false })
  const [existing, setExisting] = useState([])
  const [pending, setPending] = useState([])
  const [busy, setBusy] = useState(false)
  const [dirty, setDirty] = useState(false)
  const fileRef = useRef(null)

  useEffect(() => {
    if (!postId) return
    api.get(`/api/posts/${postId}`).then((p) => {
      setF({ categoryId: p.categoryId, title: p.title, content: p.content, pinned: p.pinned })
      setExisting(p.attachments); setLoaded(true)
    }).catch((e) => { toast(e.message, 'error'); navigate('/posts') })
  }, [postId]) // eslint-disable-line
  useEffect(() => {
    if (!postId && !f.categoryId && writable.length) {
      const preferred = writable.find((c) => c.id === Number(params.cat)) || writable.find((c) => !c.adminOnlyWrite) || writable[0]
      setF((x) => ({ ...x, categoryId: preferred.id }))
    }
  }, [writable.length]) // eslint-disable-line
  useEffect(() => {
    const warn = (e) => { if (dirty) { e.preventDefault(); e.returnValue = '' } }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const set = (patch) => { setF((x) => ({ ...x, ...patch })); setDirty(true) }
  const addFiles = (list) => {
    const ok = [...list].filter((file) => { if (file.size > 50 * 1024 * 1024) { toast(`${file.name}: 50MB 를 초과합니다.`, 'error'); return false } return true })
    setPending((p) => [...p, ...ok]); setDirty(true)
    if (fileRef.current) fileRef.current.value = ''
  }

  const save = async () => {
    if (!f.title.trim()) { toast('제목을 입력하세요.', 'error'); return }
    if (!f.content.trim()) { toast('내용을 입력하세요.', 'error'); return }
    if (!f.categoryId) { toast('게시판을 선택하세요.', 'error'); return }
    setBusy(true)
    try {
      const saved = postId ? await api.put(`/api/posts/${postId}`, f) : await api.post('/api/posts', f)
      for (const file of pending) {
        try { await api.upload(`/api/posts/${saved.id}/attachments`, file) } catch (e) { toast(`${file.name}: ${e.message}`, 'error') }
      }
      setDirty(false)
      refreshCategories()
      toast(postId ? '수정했습니다.' : '등록했습니다.', 'success')
      navigate(`/posts/${saved.id}`)
    } catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }
  const cancel = async () => {
    if (dirty && !(await confirm('작성 중인 내용이 사라집니다. 나갈까요?', { okText: '나가기' }))) return
    setDirty(false)
    if (postId) navigate(`/posts/${postId}`); else window.history.back()
  }

  if (!loaded) return <div className="center-screen"><Spinner /></div>
  if (!writable.length) return <div className="page"><p>글을 쓸 수 있는 게시판이 없습니다.</p></div>
  const cat = categories.find((c) => c.id === f.categoryId)

  return (
    <>
      <PageHeader crumbs={[{ label: '게시판', href: '#/posts' }, ...(cat ? [{ label: cat.name, href: `#/posts?cat=${cat.id}` }] : [])]} title={postId ? '글 수정' : '새 글'} icon="edit" subtitle="마크다운으로 작성하고 파일을 첨부할 수 있습니다. Ctrl+Enter 로 저장"
 />
      <div className="page page-editor"><div className="card"><div className="card-body">
        <div className="editor-meta">
          <select className="input input-sm" value={f.categoryId || ''} onChange={(e) => set({ categoryId: Number(e.target.value) })} aria-label="게시판">
            {writable.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          {me.role === 'ADMIN' && <label className="check small"><input type="checkbox" checked={f.pinned} onChange={(e) => set({ pinned: e.target.checked })} /> 상단 고정</label>}
        </div>
        <input className="title-input" placeholder="제목" maxLength={200} value={f.title} autoFocus={!postId} onChange={(e) => set({ title: e.target.value })} aria-label="제목" />
        <MarkdownEditor value={f.content} onChange={(v) => set({ content: v })} rows={18} users={users} mentionNames={mentionNames}
          placeholder={'내용을 입력하세요.\n\n## 제목, **굵게**, - 목록, - [ ] 체크리스트, > 인용, `코드`, [링크](https://) 를 지원합니다.\n@아이디 로 동료를 언급하면 알림이 갑니다.'}
          onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) save() }} />

        <section className="dsec" onDragOver={(e) => e.preventDefault()} onDrop={(e) => { e.preventDefault(); addFiles(e.dataTransfer.files) }}>
          <div className="dsec-head">
            <h4>첨부파일</h4>
            <button className="link" onClick={() => fileRef.current?.click()}>파일 추가</button>
            <input ref={fileRef} type="file" multiple hidden onChange={(e) => addFiles(e.target.files)} />
          </div>
          {existing.length > 0 && <FileList files={existing} me={me} downloadBase="/api/post-attachments" onDelete={async (a) => {
            if (!(await confirm(`'${a.originalName}' 파일을 삭제할까요?`, { okText: '삭제' }))) return
            try { await api.del(`/api/post-attachments/${a.id}`); setExisting((l) => l.filter((x) => x.id !== a.id)) } catch (e) { toast(e.message, 'error') }
          }} />}
          {pending.length > 0 && (
            <ul className="file-list">
              {pending.map((file, i) => (
                <li key={i}>
                  <span className="file-ext">{(file.name.split('.').pop() || '').slice(0, 4).toUpperCase()}</span>
                  <span className="grow file-name"><span className="ellipsis">{file.name}</span><span className="file-meta">{fmtSize(file.size)} · 저장 시 업로드</span></span>
                  <button className="icon-btn icon-btn-sm" aria-label="제외" onClick={() => setPending((p) => p.filter((_, j) => j !== i))}><Icon name="x" size={14} /></button>
                </li>
              ))}
            </ul>
          )}
          {existing.length + pending.length === 0 && <div className="drop-hint">파일을 여기로 끌어다 놓으세요 (파일당 최대 50MB)</div>}
        </section>
        {/* 저장·취소는 상단바가 아니라 작성 영역 아래에 */}
        <div className="form-actions editor-actions">
          <button className="btn" onClick={cancel}>취소</button>
          <button className="btn btn-primary" onClick={save} disabled={busy}>{busy ? '저장 중…' : postId ? '수정 완료' : '등록'}</button>
        </div>
      </div></div></div>
      {confirmDialog}
    </>
  )
}
