import React, { useRef, useState } from 'react'

// 의존성 없는 안전한 마크다운 렌더러 (innerHTML 미사용 → XSS 없음)
const SAFE_URL = /^(https?:\/\/|mailto:|#\/)/i

/** 언급 표기: 이름(아이디) — 아이디는 메일이면 @ 앞부분까지. 동명이인 구분용 */
export const mentionLabel = (u) => `${u.name}(${(u.username || '').split('@')[0]})`

function inline(text, keyBase, mentionNames) {
  const out = []
  const re = /(`[^`]+`)|(\*\*[^*]+\*\*)|(~~[^~]+~~)|(\*[^*\s][^*]*\*)|(\[[^\]]+\]\([^)\s]+\))|(https?:\/\/[^\s<>()]+)|(@(?:[가-힣A-Za-z]{1,20}\([A-Za-z0-9._%+@-]{2,64}\)|[A-Za-z0-9._-]{3,50}(?:@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)?|[가-힣]{2,20}))/g
  let last = 0, m, k = 0
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index))
    const t = m[0]
    const key = `${keyBase}-${k++}`
    if (m[1]) out.push(<code key={key}>{t.slice(1, -1)}</code>)
    else if (m[2]) out.push(<strong key={key}>{inline(t.slice(2, -2), key, mentionNames)}</strong>)
    else if (m[3]) out.push(<del key={key}>{t.slice(2, -2)}</del>)
    else if (m[4]) out.push(<em key={key}>{t.slice(1, -1)}</em>)
    else if (m[5]) {
      const [, label, url] = t.match(/^\[([^\]]+)\]\(([^)\s]+)\)$/)
      out.push(SAFE_URL.test(url) ? <a key={key} href={url} target={url.startsWith('#') ? undefined : '_blank'} rel="noopener noreferrer">{label}</a> : label)
    } else if (m[6]) out.push(<a key={key} href={t} target="_blank" rel="noopener noreferrer">{t}</a>)
    else if (m[7]) {
      // @이름 뒤에 조사가 붙은 경우(@홍길동님) 아는 이름까지만 강조
      const tok = t.slice(1)
      let hit = !mentionNames || mentionNames.has(tok) ? tok : null
      for (let n = tok.length - 1; !hit && n >= 2; n--) if (mentionNames.has(tok.slice(0, n))) hit = tok.slice(0, n)
      if (hit) { out.push(<span key={key} className="mention">@{hit}</span>); if (hit.length < tok.length) out.push(tok.slice(hit.length)) }
      else out.push(t)
    }
    last = m.index + t.length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

export function Markdown({ text, mentionNames, className = '' }) {
  if (!text) return null
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  const blocks = []
  let i = 0, key = 0
  while (i < lines.length) {
    const line = lines[i]
    if (/^```/.test(line)) {
      const buf = []; i++
      while (i < lines.length && !/^```/.test(lines[i])) buf.push(lines[i++])
      i++
      blocks.push(<pre key={key++}><code>{buf.join('\n')}</code></pre>)
      continue
    }
    const h = line.match(/^(#{1,3})\s+(.*)$/)
    if (h) { const T = `h${h[1].length + 1}`; blocks.push(<T key={key++}>{inline(h[2], `h${key}`, mentionNames)}</T>); i++; continue }
    if (/^(-{3,}|\*{3,})\s*$/.test(line)) { blocks.push(<hr key={key++} />); i++; continue }
    if (/^>\s?/.test(line)) {
      const buf = []
      while (i < lines.length && /^>\s?/.test(lines[i])) buf.push(lines[i++].replace(/^>\s?/, ''))
      blocks.push(<blockquote key={key++}>{buf.map((b, j) => <React.Fragment key={j}>{j > 0 && <br />}{inline(b, `q${key}-${j}`, mentionNames)}</React.Fragment>)}</blockquote>)
      continue
    }
    if (/^\s*[-*]\s+/.test(line)) {
      const items = []
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) items.push(lines[i++].replace(/^\s*[-*]\s+/, ''))
      blocks.push(<ul key={key++}>{items.map((t, j) => {
        const cb = t.match(/^\[( |x|X)\]\s+(.*)$/)
        return cb ? <li key={j} className="task-li"><input type="checkbox" checked={cb[1] !== ' '} readOnly /> {inline(cb[2], `l${key}-${j}`, mentionNames)}</li>
          : <li key={j}>{inline(t, `l${key}-${j}`, mentionNames)}</li>
      })}</ul>)
      continue
    }
    if (/^\s*\d+[.)]\s+/.test(line)) {
      const items = []
      while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) items.push(lines[i++].replace(/^\s*\d+[.)]\s+/, ''))
      blocks.push(<ol key={key++}>{items.map((t, j) => <li key={j}>{inline(t, `o${key}-${j}`, mentionNames)}</li>)}</ol>)
      continue
    }
    if (!line.trim()) { i++; continue }
    const buf = []
    while (i < lines.length && lines[i].trim() && !/^(```|#{1,3}\s|>|\s*[-*]\s+|\s*\d+[.)]\s+|-{3,}\s*$)/.test(lines[i])) buf.push(lines[i++])
    blocks.push(<p key={key++}>{buf.map((b, j) => <React.Fragment key={j}>{j > 0 && <br />}{inline(b, `p${key}-${j}`, mentionNames)}</React.Fragment>)}</p>)
  }
  return <div className={`md ${className}`}>{blocks}</div>
}

/** 마크다운 에디터: 툴바 + 작성/미리보기 탭 */
/** 댓글 수정 상자: 기존 내용으로 시작, 저장/취소 (Ctrl+Enter 저장, Esc 취소) */
export function CommentEditBox({ initial, users, mentionNames, onSave, onCancel }) {
  const [text, setText] = useState(initial || '')
  const [busy, setBusy] = useState(false)
  const save = async () => {
    if (!text.trim() || busy) return
    if (text.trim() === (initial || '').trim()) { onCancel(); return }
    setBusy(true)
    try { await onSave(text) } finally { setBusy(false) }
  }
  return (
    <div className="comment-box comment-edit">
      <MarkdownEditor value={text} onChange={setText} rows={3} users={users} mentionNames={mentionNames} autoFocus
        onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) save(); if (e.key === 'Escape') onCancel() }} />
      <div className="form-actions">
        <span className="hint grow">Ctrl+Enter 로 저장</span>
        <button className="btn btn-sm" onClick={onCancel}>취소</button>
        <button className="btn btn-sm btn-primary" onClick={save} disabled={busy || !text.trim()}>{busy ? '저장 중…' : '저장'}</button>
      </div>
    </div>
  )
}

export function MarkdownEditor({ value, onChange, placeholder, rows = 12, mentionNames, onKeyDown, autoFocus, users }) {
  const [tab, setTab] = useState('write')
  const ref = useRef(null)
  const [mention, setMention] = useState(null)

  const wrap = (before, after = before, ph = '텍스트') => {
    const el = ref.current
    const s = el.selectionStart, e = el.selectionEnd
    const sel = value.slice(s, e) || ph
    const nv = value.slice(0, s) + before + sel + after + value.slice(e)
    onChange(nv)
    setTimeout(() => { el.focus(); el.setSelectionRange(s + before.length, s + before.length + sel.length) }, 0)
  }
  const prefix = (p) => {
    const el = ref.current
    const s = el.selectionStart, e = el.selectionEnd
    const ls = value.lastIndexOf('\n', s - 1) + 1
    const block = value.slice(ls, e)
    const nb = block.split('\n').map((l) => p + l).join('\n')
    onChange(value.slice(0, ls) + nb + value.slice(e))
    setTimeout(() => el.focus(), 0)
  }
  const handleChange = (e) => {
    onChange(e.target.value)
    if (!users) return
    const caret = e.target.selectionStart
    const m = e.target.value.slice(0, caret).match(/(^|\s)@([^\s@]*)$/)
    setMention(m ? { q: m[2], start: caret - m[2].length - 1 } : null)
  }
  const candidates = mention && users ? users.filter((u) => u.name.includes(mention.q) || u.username.toLowerCase().startsWith(mention.q.toLowerCase())).slice(0, 6) : []
  const pick = (u) => {
    const el = ref.current
    const caret = el.selectionStart
    // 언급은 이름(아이디)로 넣는다 (@홍길동(gd.hong)) — 동명이인 구분, 서버가 아이디로 찾아 알림
    const label = mentionLabel(u)
    const nv = value.slice(0, mention.start) + '@' + label + ' ' + value.slice(caret)
    onChange(nv)
    const p = mention.start + label.length + 2
    setMention(null)
    setTimeout(() => { el.focus(); el.setSelectionRange(p, p) }, 0)
  }

  return (
    <div className="md-editor">
      <div className="md-toolbar">
        <div className="md-tabs">
          <button type="button" className={tab === 'write' ? 'active' : ''} onClick={() => setTab('write')}>작성</button>
          <button type="button" className={tab === 'preview' ? 'active' : ''} onClick={() => setTab('preview')}>미리보기</button>
        </div>
        {tab === 'write' && (
          <div className="md-tools">
            <button type="button" title="제목" onClick={() => prefix('## ')}>H</button>
            <button type="button" title="굵게" onClick={() => wrap('**')}><b>B</b></button>
            <button type="button" title="기울임" onClick={() => wrap('*')}><i>I</i></button>
            <button type="button" title="취소선" onClick={() => wrap('~~')}><s>S</s></button>
            <span className="md-sep" />
            <button type="button" title="글머리 목록" onClick={() => prefix('- ')}>•</button>
            <button type="button" title="번호 목록" onClick={() => prefix('1. ')}>1.</button>
            <button type="button" title="체크리스트" onClick={() => prefix('- [ ] ')}>☑</button>
            <button type="button" title="인용" onClick={() => prefix('> ')}>❝</button>
            <span className="md-sep" />
            <button type="button" title="코드" onClick={() => wrap('`')}>{'</>'}</button>
            <button type="button" title="링크" onClick={() => wrap('[', '](https://)', '링크 텍스트')}>🔗</button>
          </div>
        )}
      </div>
      {tab === 'write' ? (
        <div style={{ position: 'relative' }}>
          <textarea ref={ref} className="md-textarea" rows={rows} value={value} placeholder={placeholder} autoFocus={autoFocus}
            onChange={handleChange}
            onKeyDown={(e) => {
              if (mention && candidates.length && (e.key === 'Enter' || e.key === 'Tab')) { e.preventDefault(); pick(candidates[0]); return }
              if (e.key === 'Escape' && mention) { setMention(null); return }
              if ((e.ctrlKey || e.metaKey) && e.key === 'b') { e.preventDefault(); wrap('**') }
              if ((e.ctrlKey || e.metaKey) && e.key === 'i') { e.preventDefault(); wrap('*') }
              onKeyDown && onKeyDown(e)
            }} />
          {mention && candidates.length > 0 && (
            <div className="mention-list">
              {candidates.map((u) => (
                <button type="button" key={u.id} className="menu-row" onMouseDown={(e) => { e.preventDefault(); pick(u) }}>
                  <span>{u.name} <span className="muted">({(u.username || '').split('@')[0]})</span></span>{u.department && <span className="muted small">{u.department}</span>}
                </button>
              ))}
            </div>
          )}
        </div>
      ) : (
        <div className="md-preview">{value.trim() ? <Markdown text={value} mentionNames={mentionNames} /> : <p className="muted">미리볼 내용이 없습니다.</p>}</div>
      )}
    </div>
  )
}
