import React, { useEffect, useMemo, useState } from 'react'
import { api } from '../api.js'
import { useApp, navigate, PageHeader } from '../App.jsx'
import { Spinner, Avatar } from './common.jsx'
import { Icon } from './icons.jsx'
import { BoardMark } from './Sidebar.jsx'
import { friendlyDate, todayStr, relTime, diffDays } from '../util.js'

/**
 * 대시보드(홈): 요약 카드 3장 + 내 업무 + 댓글·멘션 + 즐겨찾는 워크스페이스
 * 데이터: /api/dashboard/me (내 업무·완료 통계), /api/notifications/talk (댓글·멘션 알림)
 */
const WD = ['일', '월', '화', '수', '목', '금', '토']

export default function Home() {
  const { me, boards, users, openItem, refreshUnread } = useApp()
  const [dash, setDash] = useState(null)
  const [talk, setTalk] = useState(null)
  const [tab, setTab] = useState('all')
  const [talkTab, setTalkTab] = useState('all')

  useEffect(() => { api.get('/api/dashboard/me').then(setDash).catch(() => setDash(false)) }, [boards])
  useEffect(() => { api.get('/api/notifications/talk').then(setTalk).catch(() => setTalk([])) }, [])

  const today = todayStr()
  const now = new Date()
  const dateLabel = `${now.getMonth() + 1}월 ${now.getDate()}일 ${WD[now.getDay()]}요일`
  const boardOf = useMemo(() => new Map(boards.map((b) => [b.id, b])), [boards])

  if (dash === null) return <><PageHeader icon="chart" title="업무 현황" subtitle={dateLabel} /><div className="page"><Spinner /></div></>
  if (dash === false) return <><PageHeader icon="chart" title="업무 현황" subtitle={dateLabel} /><div className="page"><p className="muted">대시보드를 불러오지 못했습니다.</p></div></>

  const dueToday = dash.open.filter((i) => i.dueDate === today)
  const lists = {
    all: dash.open,
    today: dueToday,
    overdue: dash.overdue,
    week: dash.dueSoon,
  }
  const tabs = [['all', '전체', dash.open.length], ['today', '오늘', dueToday.length], ['overdue', '마감 초과', dash.overdue.length], ['week', '7일 내', dash.dueSoon.length]]
  const rows = lists[tab]

  const diff = dash.doneThisWeek - dash.doneLastWeek
  const maxDay = Math.max(1, ...dash.doneByDay)
  const todayIdx = (now.getDay() + 6) % 7

  const favs = boards.filter((b) => b.favorite)

  return (
    <>
      <PageHeader icon="chart" title="업무 현황" subtitle={dateLabel} />
      <div className="page dash">
        <div className="dash-summary">
          <button className={`dash-card ${dash.overdueCount ? 'is-danger' : ''}`} onClick={() => setTab('overdue')}>
            <span className="dash-card-label">마감 초과</span>
            <b className="dash-card-num">{dash.overdueCount}건</b>
            <span className="dash-card-sub ellipsis">{dash.overdue[0] ? (dash.overdue.length > 1 ? `${dash.overdue[0].name} 외 ${dash.overdue.length - 1}건` : dash.overdue[0].name) : '지연된 업무 없음'}</span>
            {dash.overdueCount > 0 && <span className="dash-card-btn danger">바로 보기</span>}
          </button>
          <button className="dash-card" onClick={() => setTab('today')}>
            <span className="dash-card-label">오늘 마감</span>
            <b className="dash-card-num">{dueToday.length}건</b>
            <span className="dash-card-sub ellipsis">{dueToday[0] ? (dueToday.length > 1 ? `${dueToday[0].name} 외 ${dueToday.length - 1}건` : dueToday[0].name) : '오늘 마감 업무 없음'}</span>
            {dueToday.length > 0 && <span className="dash-card-btn">목록 보기</span>}
          </button>
          <div className="dash-card">
            <span className="dash-card-label">이번 주 완료</span>
            <b className="dash-card-num">{dash.doneThisWeek}건</b>
            <span className={`dash-card-sub ${diff > 0 ? 'up' : ''}`}>전주 대비 {diff > 0 ? `+${diff}` : diff}건</span>
            <div className="dash-bars" aria-hidden="true">
              {dash.doneByDay.map((n, i) => (
                <span key={i} className={i === todayIdx ? 'today' : i > todayIdx ? 'future' : ''} title={`${WD[(i + 1) % 7]} ${n}건`}>
                  <i style={{ height: `${Math.max(8, (n / maxDay) * 100)}%` }} />
                </span>
              ))}
            </div>
          </div>
        </div>

        <div className="dash-main">
          <section className="dash-panel dash-tasks">
            <div className="dash-panel-head">
              <h2>내 업무</h2>
              <div className="dash-pills" role="tablist">
                {tabs.map(([k, label, n]) => (
                  <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{label} {n}</button>
                ))}
              </div>
            </div>
            {rows.length === 0 ? <p className="dash-empty">해당하는 업무가 없습니다.</p> : (
              <div className="dash-list">
                {rows.map((i) => {
                  const b = boardOf.get(i.boardId) || { id: i.boardId, name: i.boardName }
                  const late = i.dueDate && i.dueDate < today
                  return (
                    <button key={i.id} className="dash-row" onClick={() => openItem(i.boardId, i.id)}>
                      <BoardMark board={b} size={30} />
                      <span className="dash-row-text">
                        <b className="ellipsis">{i.name}</b>
                        <span className="ellipsis">{i.boardName}{i.key ? ` · ${i.key}` : ''}{i.status?.label ? ` · ${i.status.label}` : ''}</span>
                      </span>
                      <span className={`dash-due ${late ? 'late' : i.dueDate === today ? 'today' : ''}`}>
                        {!i.dueDate ? '' : late ? `${diffDays(i.dueDate, today)}일 지남` : friendlyDate(i.dueDate)}
                      </span>
                    </button>
                  )
                })}
              </div>
            )}
          </section>

          <div className="dash-side">
            <TalkPanel talk={talk} tab={talkTab} setTab={setTalkTab} users={users} boardOf={boardOf}
              onOpen={(n) => {
                if (!n.read) {
                  api.post(`/api/notifications/${n.id}/read`).then(refreshUnread).catch(() => {})
                  setTalk((l) => l.map((x) => (x.id === n.id ? { ...x, read: true } : x)))
                }
                if (n.itemId && n.boardId) openItem(n.boardId, n.itemId)
                else if (n.postId) navigate(`/posts/${n.postId}`)
              }} />

            <section className="dash-panel">
              <div className="dash-panel-head"><h2>즐겨찾는 워크스페이스</h2></div>
              {favs.length === 0 ? <p className="dash-empty">왼쪽 목록에서 ☆를 눌러 즐겨찾기를 추가하세요.</p> : (
                <div className="dash-favs">
                  {favs.slice(0, 6).map((b) => (
                    <a key={b.id} className="dash-fav" href={`#/board/${b.id}`} title={b.name}>
                      <BoardMark board={b} size={26} />
                      <b className="ellipsis">{b.name}</b>
                      <span>{b.showProgress !== false && b.itemCount ? `진행 ${b.itemCount - b.doneCount}건 · ${Math.floor((b.doneCount / b.itemCount) * 100)}%` : `${b.itemCount}건`}</span>
                    </a>
                  ))}
                </div>
              )}
            </section>
          </div>
        </div>
      </div>
    </>
  )
}

/** 알림 문구 "홍길동님이 [보드] '업무'에서 회원님을 언급했습니다: 내용" 을 나눔 */
function parseTalk(n) {
  const m = n.message || ''
  const at = m.indexOf('님이 ')
  const name = at > 0 ? m.slice(0, at) : ''
  const q = m.match(/'([^']+)'/)
  const colon = m.indexOf(': ')
  const text = colon > 0 ? m.slice(colon + 2) : ''
  const mention = /MENTION/.test(n.type)
  const reply = m.includes('답글')
  return { name, title: q ? q[1] : '', text, kind: mention ? '멘션' : reply ? '답글' : '댓글', mention }
}

function TalkPanel({ talk, tab, setTab, users, boardOf, onOpen }) {
  const parsed = (talk || []).map((n) => ({ n, p: parseTalk(n) }))
  const list = parsed.filter(({ p }) => tab === 'all' || (tab === 'mention' ? p.mention : !p.mention)).slice(0, 5)
  const unread = (talk || []).filter((n) => !n.read).length
  return (
    <section className="dash-panel">
      <div className="dash-panel-head">
        <h2>댓글 · 멘션 {unread > 0 && <span className="dash-count">{unread}</span>}</h2>
        <div className="dash-pills" role="tablist">
          {[['all', '전체'], ['mention', '멘션'], ['comment', '댓글']].map(([k, l]) => (
            <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{l}</button>
          ))}
        </div>
      </div>
      {talk == null ? <Spinner /> : list.length === 0 ? <p className="dash-empty">{tab === 'mention' ? '나를 언급한 글이 없습니다.' : tab === 'comment' ? '내 업무·글에 달린 댓글이 없습니다.' : '새 댓글이나 멘션이 없습니다.'}</p> : (
        <div className="dash-talk">
          {list.map(({ n, p }) => {
            const u = users.find((x) => x.name === p.name)
            const where = n.boardId ? boardOf.get(n.boardId)?.name : '게시판'
            return (
              <button key={n.id} className={`dash-talk-row ${n.read ? '' : 'unread'}`} onClick={() => onOpen(n)}>
                <Avatar user={u || (p.name ? { id: 0, name: p.name } : null)} size={32} />
                <span className="dash-talk-text">
                  <span><b>{p.name || '알림'}</b> <em>· {p.kind}</em></span>
                  {p.title && <span className="dash-talk-where ellipsis">{where ? `${where} · ` : ''}{p.title}</span>}
                  {p.text && <span className="dash-talk-body">{p.text}</span>}
                  <span className="dash-talk-time">{relTime(n.createdAt)}</span>
                </span>
                {!n.read && <span className="dash-dot" aria-label="안 읽음" />}
              </button>
            )
          })}
        </div>
      )}
      <a className="dash-more" href="#/inbox">알림함에서 전체 보기</a>
    </section>
  )
}

export function KpiCard({ tone = 'primary', icon, label, value, sub }) {
  return (
    <div className={`card kpi-card border-start-${tone}`}>
      <div className="card-body">
        <div className="kpi-text">
          <div className={`kpi-label text-${tone}`}>{label}</div>
          <div className="kpi-value">{value}</div>
          {sub && <div className="kpi-sub">{sub}</div>}
        </div>
        <Icon name={icon} size={30} className="kpi-icon" />
      </div>
    </div>
  )
}
