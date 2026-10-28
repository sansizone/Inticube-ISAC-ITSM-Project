import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useApp, navigate, PageHeader } from '../App.jsx'
import { Avatar, Spinner, useToast, useConfirm, Empty } from './common.jsx'
import { Icon } from './icons.jsx'
import { Markdown, MarkdownEditor, CommentEditBox, mentionLabel } from './markdown.jsx'
import { FileList } from './ItemDrawer.jsx'
import { relTime, fmtDateTime } from '../util.js'

export default function PostView({ postId }) {
  const { me, users, mentionNames, refreshCategories } = useApp()
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [post, setPost] = useState(null)
  const [error, setError] = useState(null)

  const load = () => api.get(`/api/posts/${postId}`).then(setPost).catch((e) => setError(e.message))
  useEffect(() => { load() }, [postId]) // eslint-disable-line

  if (error) return <><PageHeader crumbs={[{ label: '게시판', href: '#/posts' }]} title="게시글" icon="doc" /><div className="page"><div className="card"><div className="card-body"><Empty icon="doc" title={error}><a className="link" href="#/posts">목록으로</a></Empty></div></div></div></>
  if (!post) return <div className="center-screen"><Spinner /></div>

  const canEdit = me.role === 'ADMIN' || post.author.id === me.id
  const remove = async () => {
    if (!(await confirm('이 글을 삭제할까요?\n댓글과 첨부파일도 함께 삭제됩니다.', { okText: '삭제' }))) return
    try { await api.del(`/api/posts/${post.id}`); refreshCategories(); toast('삭제했습니다.'); navigate(`/posts?cat=${post.categoryId}`) } catch (e) { toast(e.message, 'error') }
  }
  const togglePin = async () => {
    try { await api.put(`/api/posts/${post.id}/pinned`, { pinned: !post.pinned }); load(); toast(post.pinned ? '고정을 해제했습니다.' : '상단에 고정했습니다.') } catch (e) { toast(e.message, 'error') }
  }
  const edited = post.updatedAt.slice(0, 16) !== post.createdAt.slice(0, 16)
  const roots = post.comments.filter((c) => !c.parentId)
  const replies = (id) => post.comments.filter((c) => c.parentId === id)
  const commentCount = post.comments.filter((c) => !c.deleted).length

  return (
    <>
      <PageHeader crumbs={[{ label: '게시판', href: '#/posts' }, { label: post.categoryName, href: `#/posts?cat=${post.categoryId}` }]} title={post.title} icon={post.pinned ? 'pin' : 'doc'}
        subtitle={`${post.categoryName} · ${post.author.name} · ${fmtDateTime(post.createdAt)} · 조회 ${post.viewCount}`} />
      <div className="page page-read">
        <article className="post card"><div className="card-body post-card-body">
          {/* 글 관련 버튼은 상단바가 아니라 본문 위에 */}
          <div className="post-topline">
            <a className="tag" href={`#/posts?cat=${post.categoryId}`}>{post.pinned && <Icon name="pin" size={11} />} {post.categoryName}</a>
            <span className="grow" />
            <button className="btn btn-sm" onClick={() => navigate(post.categoryId ? `/posts?cat=${post.categoryId}` : '/posts')}><Icon name="list" size={13} /> 목록</button>
            {me.role === 'ADMIN' && <button className={`btn btn-sm ${post.pinned ? 'btn-strong' : ''}`} onClick={togglePin}><Icon name="pin" size={13} /> {post.pinned ? '고정됨' : '상단 고정'}</button>}
            {canEdit && <button className="btn btn-sm" onClick={() => navigate(`/posts/${post.id}/edit`)}><Icon name="edit" size={13} /> 수정</button>}
            {canEdit && <button className="btn btn-sm btn-danger-ghost" onClick={remove}><Icon name="trash" size={13} /> 삭제</button>}
          </div>
          <h1 className="post-h1">{post.title}</h1>
          <div className="post-byline">
            <Avatar user={post.author} size={28} />
            <div>
              <div className="byline-name">{post.author.name}{post.author.department && <span className="muted"> · {post.author.department}</span>}</div>
              <div className="byline-meta" title={fmtDateTime(post.createdAt)}>{fmtDateTime(post.createdAt)}{edited && ` · 수정됨 ${relTime(post.updatedAt)}`} · 조회 {post.viewCount}</div>
            </div>
          </div>
          <Markdown text={post.content} mentionNames={mentionNames} className="post-body" />
          {post.attachments.length > 0 && (
            <section className="post-files">
              <h4 className="section-label">첨부파일 {post.attachments.length}</h4>
              <FileList files={post.attachments} me={me} downloadBase="/api/post-attachments" />
            </section>
          )}
        </div></article>

        <section className="comments card"><div className="card-body">
          <h3 className="comments-title">댓글 <span className="count">{commentCount}</span></h3>
          <CommentForm postId={post.id} users={users} mentionNames={mentionNames} onDone={load} toast={toast} />
          {roots.map((c) => (
            <div key={c.id} className="cthread">
              <Comment c={c} me={me} mentionNames={mentionNames} postId={post.id} users={users} onChange={load} toast={toast} confirm={confirm} canReply />
              {replies(c.id).length > 0 && (
                <div className="creplies">
                  {replies(c.id).map((r) => <Comment key={r.id} c={r} me={me} mentionNames={mentionNames} postId={post.id} users={users} onChange={load} toast={toast} confirm={confirm} />)}
                </div>
              )}
            </div>
          ))}

        </div></section>
        <nav className="prevnext">
          {post.prevId ? <a href={`#/posts/${post.prevId}`}><span className="muted small">이전 글</span><span className="ellipsis">{post.prevTitle}</span></a> : <span />}
          {post.nextId ? <a href={`#/posts/${post.nextId}`} className="next"><span className="muted small">다음 글</span><span className="ellipsis">{post.nextTitle}</span></a> : <span />}
        </nav>
      </div>
      {confirmDialog}
    </>
  )
}

function Comment({ c, me, mentionNames, postId, users, onChange, toast, confirm, canReply }) {
  const [replying, setReplying] = useState(false)
  const [editing, setEditing] = useState(false)
  if (c.deleted) return <div className="comment deleted">삭제된 댓글입니다.</div>
  const canDelete = me.role === 'ADMIN' || c.author.id === me.id
  return (
    <div className="comment">
      <Avatar user={c.author} size={26} />
      <div className="grow" style={{ minWidth: 0 }}>
        <div className="comment-head">
          <b>{c.author.name}</b>
          <span className="muted small" title={fmtDateTime(c.createdAt)}>{relTime(c.createdAt)}</span>
          {c.updatedAt && <span className="edited-mark" title={`수정: ${fmtDateTime(c.updatedAt)}`}>(수정됨)</span>}
        </div>
        {editing
          ? <CommentEditBox initial={c.content} users={users} mentionNames={mentionNames} onCancel={() => setEditing(false)}
              onSave={async (text) => {
                try { await api.put(`/api/post-comments/${c.id}`, { content: text }); setEditing(false); onChange() } catch (e) { toast(e.message, 'error') }
              }} />
          : <Markdown text={c.content} mentionNames={mentionNames} className="comment-body" />}
        <div className="comment-actions">
          {canReply && <button className="link" onClick={() => setReplying((v) => !v)}><Icon name="reply" size={12} /> 답글</button>}
          {c.author.id === me.id && !editing && <button className="link link-muted" onClick={() => setEditing(true)}>수정</button>}
          {canDelete && <button className="link link-muted" onClick={async () => {
            if (!(await confirm('댓글을 삭제할까요?', { okText: '삭제' }))) return
            try { await api.del(`/api/post-comments/${c.id}`); onChange() } catch (e) { toast(e.message, 'error') }
          }}>삭제</button>}
        </div>
        {replying && <CommentForm postId={postId} parentId={c.id} users={users} mentionNames={mentionNames} toast={toast} autoFocus
          initial={`@${mentionLabel(c.author)} `} onDone={() => { setReplying(false); onChange() }} onCancel={() => setReplying(false)} />}
      </div>
    </div>
  )
}

function CommentForm({ postId, parentId, users, mentionNames, onDone, onCancel, toast, autoFocus, initial = '' }) {
  const [text, setText] = useState(initial)
  const [busy, setBusy] = useState(false)
  const submit = async () => {
    if (!text.trim()) return
    setBusy(true)
    try { await api.post(`/api/posts/${postId}/comments`, { content: text, parentId: parentId || null }); setText(''); onDone() }
    catch (e) { toast(e.message, 'error') } finally { setBusy(false) }
  }
  return (
    <div className="comment-box">
      <MarkdownEditor value={text} onChange={setText} rows={3} users={users} mentionNames={mentionNames} autoFocus={autoFocus}
        placeholder={parentId ? '답글을 입력하세요' : '댓글을 입력하세요. @이름으로 언급할 수 있습니다.'}
        onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) submit() }} />
      <div className="form-actions">
        <span className="hint grow">Ctrl+Enter 로 등록</span>
        {onCancel && <button className="btn btn-sm" onClick={onCancel}>취소</button>}
        <button className="btn btn-sm btn-primary" onClick={submit} disabled={busy || !text.trim()}>{parentId ? '답글 등록' : '댓글 등록'}</button>
      </div>
    </div>
  )
}
