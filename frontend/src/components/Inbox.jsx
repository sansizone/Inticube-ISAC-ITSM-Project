import React, { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'
import { useApp, navigate, PageHeader } from '../App.jsx'
import { Empty, Spinner } from './common.jsx'
import { Icon } from './icons.jsx'
import { relTime, todayStr } from '../util.js'

const TYPE = {
  ASSIGNED: { icon: 'user', label: '담당 지정' },
  COMMENT: { icon: 'chat', label: '업무 댓글' },
  MENTION: { icon: 'sparkle', label: '멘션' },
  DUE_SOON: { icon: 'calendar', label: '마감 임박' },
  STATUS: { icon: 'check', label: '상태 변경' },
  POST_COMMENT: { icon: 'chat', label: '게시글 댓글' },
  POST_MENTION: { icon: 'sparkle', label: '멘션' },
  NOTICE: { icon: 'megaphone', label: '공지' },
}

export default function Inbox() {
  const { openItem, refreshUnread } = useApp()
  const [data, setData] = useState(null)
  const [filter, setFilter] = useState('all')
  const load = useCallback(() => api.get('/api/notifications').then(setData).catch(() => {}), [])
  useEffect(() => { load() }, [load])

  const open = async (n) => {
    if (!n.read) { await api.post(`/api/notifications/${n.id}/read`).catch(() => {}); refreshUnread() }
    if (n.postId) navigate(`/posts/${n.postId}`)
    else if (n.boardId && n.itemId) openItem(n.boardId, n.itemId)
    else load()
  }
  const readAll = async () => { await api.post('/api/notifications/read-all').catch(() => {}); load(); refreshUnread() }

  const list = data ? data.items.filter((n) => filter === 'all' || !n.read) : []
  const today = todayStr()
  const groups = [
    ['오늘', list.filter((n) => n.createdAt.slice(0, 10) === today)],
    ['이전', list.filter((n) => n.createdAt.slice(0, 10) !== today)],
  ].filter(([, l]) => l.length)

  return (
    <>
      <PageHeader icon="bell" title="알림함" subtitle="담당 지정, 멘션, 댓글, 공지 알림을 한곳에서 확인합니다."
        actions={data?.unreadCount > 0 && <button className="btn btn-white btn-sm" onClick={readAll}><Icon name="check" size={14} /> 모두 읽음</button>} />
      <div className="page page-narrow"><div className="card"><div className="card-body">
        <div className="seg" style={{ marginBottom: 16 }}>
          <button className={filter === 'all' ? 'active' : ''} onClick={() => setFilter('all')}>전체</button>
          <button className={filter === 'unread' ? 'active' : ''} onClick={() => setFilter('unread')}>읽지 않음 {data?.unreadCount || ''}</button>
        </div>
        {!data ? <Spinner /> : list.length === 0 ? <Empty icon="inbox" title={filter === 'unread' ? '읽지 않은 알림이 없습니다' : '알림이 없습니다'}>담당 지정, 멘션, 댓글, 공지가 여기에 모입니다.</Empty> : groups.map(([label, l]) => (
          <section key={label} className="inbox-group">
            <h4 className="section-label">{label}</h4>
            <div className="inbox-list">
              {l.map((n) => {
                const t = TYPE[n.type] || { icon: 'bell', label: '' }
                return (
                  <button key={n.id} className={`inbox-row ${n.read ? '' : 'unread'}`} onClick={() => open(n)}>
                    <span className="inbox-icon"><Icon name={t.icon} size={14} /></span>
                    <span className="grow" style={{ minWidth: 0 }}>
                      <span className="inbox-msg">{n.message}</span>
                      <span className="inbox-meta">{t.label} · {relTime(n.createdAt)}</span>
                    </span>
                    {!n.read && <span className="unread-dot" aria-label="읽지 않음" />}
                  </button>
                )
              })}
            </div>
          </section>
        ))}
      </div></div></div>
    </>
  )
}
