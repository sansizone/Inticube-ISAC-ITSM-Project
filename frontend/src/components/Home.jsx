import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useApp, navigate, PageHeader } from '../App.jsx'
import { Spinner, Empty } from './common.jsx'
import { Icon, StatusIcon, PriorityIcon } from './icons.jsx'
import { BoardMark } from './Sidebar.jsx'
import { friendlyDate, todayStr, relTime } from '../util.js'

export default function Home() {
  const { me, openItem, boards, categories } = useApp()
  const [data, setData] = useState(null)
  const [notices, setNotices] = useState(null)
  const [recent, setRecent] = useState(null)
  const noticeCat = categories.find((c) => c.adminOnlyWrite)

  useEffect(() => { api.get('/api/dashboard/me').then(setData).catch(() => {}) }, [boards])
  useEffect(() => {
    api.get('/api/posts?size=6').then((r) => setRecent(r.content)).catch(() => setRecent([]))
    if (noticeCat) api.get(`/api/posts?categoryId=${noticeCat.id}&size=5`).then((r) => setNotices([...r.pinned, ...r.content].slice(0, 5))).catch(() => setNotices([]))
    else setNotices([])
  }, [noticeCat?.id]) // eslint-disable-line

  const now = new Date()
  const hour = now.getHours()
  const greet = hour < 12 ? '좋은 아침이에요' : hour < 18 ? '좋은 오후예요' : '오늘도 수고하셨어요'

  return (
    <>
      <PageHeader icon="chart" title="대시보드" subtitle={`${greet}, ${me.name}님. 오늘 할 일을 확인하세요.`} />
      <div className="page">
        {!data ? <div className="card"><div className="card-body"><Spinner /></div></div> : (
          <>
            <div className="kpi-row">
              <KpiCard tone="primary" icon="list" label="진행 중인 내 업무" value={data.openCount} />
              <KpiCard tone="danger" icon="calendar" label="마감 지남" value={data.overdueCount} />
              <KpiCard tone="warning" icon="bell" label="7일 내 마감" value={data.dueThisWeekCount} />
              <KpiCard tone="success" icon="check" label="완료한 업무" value={data.doneCount} />
            </div>
            <div className="home-grid">
              <div className="card">
                <div className="card-header">내 업무 <span className="badge-soft">{data.openCount}</span></div>
                <div className="card-body tight">
                  {data.open.length === 0 ? <Empty icon="check" title="진행 중인 업무가 없어요">새 업무가 배정되면 여기에 표시됩니다.</Empty> : (
                    <MyTaskGroups data={data} openItem={openItem} />
                  )}
                </div>
              </div>
              <div className="home-side">
                <div className="card">
                  <div className="card-header"><span><Icon name="megaphone" size={15} /> 공지사항</span>
                    {noticeCat && <a className="link" href={`#/posts?cat=${noticeCat.id}`}>전체 보기</a>}</div>
                  <div className="card-body tight">
                    {notices === null ? <Spinner /> : notices.length === 0 ? <p className="muted small pad">등록된 공지가 없습니다.</p> : (
                      <div className="post-mini-list">
                        {notices.map((p) => (
                          <a key={p.id} className="post-mini" href={`#/posts/${p.id}`}>
                            {p.pinned && <Icon name="pin" size={12} className="pin-icon" />}
                            <span className="grow ellipsis">{p.title}</span>
                            <span className="muted small">{relTime(p.createdAt)}</span>
                          </a>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
                <div className="card">
                  <div className="card-header"><span><Icon name="doc" size={15} /> 최근 게시글</span><a className="link" href="#/posts">전체 보기</a></div>
                  <div className="card-body tight">
                    {recent === null ? <Spinner /> : recent.length === 0 ? <p className="muted small pad">게시글이 없습니다.</p> : (
                      <div className="post-mini-list">
                        {recent.map((p) => (
                          <a key={p.id} className="post-mini" href={`#/posts/${p.id}`}>
                            <span className="tag">{p.categoryName}</span>
                            <span className="grow ellipsis">{p.title}</span>
                            {p.commentCount > 0 && <span className="muted small">💬 {p.commentCount}</span>}
                          </a>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
                <div className="card">
                  <div className="card-header">워크스페이스 진행률</div>
                  <div className="card-body">
                    {data.boards.length === 0 && <p className="muted small" style={{ margin: 0 }}>워크스페이스가 없습니다.</p>}
                    {data.boards.filter((b) => b.showProgress !== false).map((b) => {
                      const pct = b.itemCount ? Math.floor((b.doneCount / b.itemCount) * 100) : 0
                      return (
                        <a key={b.id} className="project-progress" href={`#/board/${b.id}`}>
                          <div className="row gap-sm"><BoardMark board={b} size={16} /><span className="grow ellipsis">{b.name}</span><span className="small num muted">{pct}%</span></div>
                          <div className="progress-sb"><span style={{ width: `${pct}%` }} /></div>
                        </a>
                      )
                    })}
                  </div>
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </>
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

function MyTaskGroups({ data, openItem }) {
  const today = todayStr()
  const overdueIds = new Set(data.overdue.map((i) => i.id))
  const soonIds = new Set(data.dueSoon.map((i) => i.id))
  const groups = [
    ['마감 지남', data.overdue, 'danger'],
    ['이번 주', data.dueSoon, ''],
    ['그 외', data.open.filter((i) => !overdueIds.has(i.id) && !soonIds.has(i.id)), ''],
  ].filter(([, l]) => l.length)
  return groups.map(([label, list, tone]) => (
    <div key={label} className="task-group">
      <div className={`task-group-label ${tone}`}>{label} <span>{list.length}</span></div>
      {list.map((i) => (
        <button key={i.id} className="task-row" onClick={() => openItem(i.boardId, i.id)}>
          <StatusIcon status={i.status} />
          <span className="task-id">{i.key || `#${i.id}`}</span>
          <span className="grow ellipsis task-name">{i.name}</span>
          <span className="tag">{i.boardName}</span>
          {i.priority && <PriorityIcon value={i.priority} />}
          <span className={`task-due ${i.dueDate && i.dueDate < today ? 'danger' : ''}`}>{i.dueDate ? friendlyDate(i.dueDate) : ''}</span>
        </button>
      ))}
    </div>
  ))
}
