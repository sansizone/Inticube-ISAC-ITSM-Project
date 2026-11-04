import React, { useCallback, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react'
import { useApp, navigate, PageHeader } from '../App.jsx'
import { api } from '../api.js'
import { Avatar, Spinner, useToast, useConfirm } from './common.jsx'
import { EMPTY_FILTERS, isFiltering, applyFilters, SearchToggle, GroupFilter, PriorityFilter, DueFilter, SortMenu, DutyFilter, StatusFilter, PersonFilter, BoardSwitcher, FieldFilter, TypeFilter, VersionFilter, SlaFilter, MoreFilters, hasValue } from './BoardFilters.jsx'
import RoadmapView from './RoadmapView.jsx'
import WikiView from './WikiView.jsx'
import FormView from './FormView.jsx'
import DutyView from './DutyView.jsx'
import { buildDutyIndex } from '../dutyTree.js'
import ListView from './ListView.jsx'
import TableView from './TableView.jsx'
import KanbanView from './KanbanView.jsx'
import TimelineView from './TimelineView.jsx'
import BoardDashboard from './BoardDashboard.jsx'
import ItemDrawer from './ItemDrawer.jsx'
import BoardSettings from './BoardSettings.jsx'
import { ExcelMenu } from './ExcelImport.jsx'
import { Icon } from './icons.jsx'
import { useFavorite } from './Sidebar.jsx'
import { ViewTabs, ColumnPicker, parseViewConfig } from './Views.jsx'
import { MoveItemModal } from './Workspace.jsx'
import { BulkBar } from './BulkActions.jsx'
import { rememberRefs, columnsFor, columnConfig, itemNoun, itemTitle, josa, withPeopleSources, srPolicy } from './Fields.jsx'

export default function BoardView({ boardId, view, itemParam }) {
  const { me, users, refreshBoards } = useApp()
  const toast = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [board, setBoard] = useState(null)
  const [error, setError] = useState(null)
  const [settings, setSettings] = useState(null)
  const [moving, setMoving] = useState(null)
  const [selected, setSelected] = useState(() => new Set()) // 일괄 작업용으로 체크한 업무
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const [extra, setExtra] = useState([]) // '＋ 필터'로 꺼낸 조건
  const [sort, setSort] = useState({ key: 'manual', dir: 'asc' })
  const [hidden, setHidden] = useState([])
  const [watching, setWatching] = useState(null) // 내가 감시 중인 업무 ID (필터 '감시 중'에서 로드)
  const setF = (patch) => setFilters((f) => ({ ...f, ...patch }))
  const boardRef = useRef(null)
  boardRef.current = board
  const pendingFilters = useRef(null)

  const lastLoad = useRef(0)
  const load = useCallback(() => { lastLoad.current = Date.now(); return api.get(`/api/boards/${boardId}`).then((b) => { rememberRefs(b); setBoard(withPeopleSources({ ...b, views: (b.views || []).filter((v) => v.type !== 'duties') })); setError(null) })
    .catch((e) => setError(e.message)) }, [boardId])

  useEffect(() => { load(); try { localStorage.setItem('tb.lastBoard', String(boardId)) } catch { /* 무시 */ } }, [load]) // eslint-disable-line

  // 현재 보기 탭: 주소의 보기 ID → 같은 유형(예전 주소 /list 등) → 첫 탭
  const views = board?.views || []
  const active = views.find((v) => String(v.id) === String(view)) || views.find((v) => v.type === view) || views[0] || null
  const activeId = active?.id
  // 탭을 바꾸면 그 탭에 저장된 필터·정렬·컬럼을 불러옴
  useEffect(() => {
    if (!board) return
    const c = parseViewConfig(active, EMPTY_FILTERS)
    setFilters(pendingFilters.current ? { ...EMPTY_FILTERS, ...pendingFilters.current } : c.filters)
    pendingFilters.current = null
    setSort(c.sort); setHidden(c.hidden)
  }, [activeId, !!board]) // eslint-disable-line
  useEffect(() => {
    // 업무가 많은 보드는 전체를 다시 받는 비용이 크므로 1분 간격, 창으로 돌아올 때도 1분 지난 경우만
    const t = setInterval(() => { if (document.visibilityState === 'visible' && Date.now() - lastLoad.current > 55000) load() }, 60000)
    const onFocus = () => { if (Date.now() - lastLoad.current > 60000) load() }
    window.addEventListener('focus', onFocus)
    return () => { clearInterval(t); window.removeEventListener('focus', onFocus) }
  }, [load])

  const setItems = (fn) => setBoard((b) => ({ ...b, items: fn(b.items) }))

  const ops = useMemo(() => ({
    async updateItem(id, patch) {
      const prev = boardRef.current.items.find((i) => i.id === id)
      // SR 통제: 완료 SR 재개·합의완료일 변경은 사유를 받아 함께 보냄
      const sp = srPolicy(boardRef.current)
      if (sp && prev) {
        const sts = boardRef.current.statuses
        const was = sts.find((s) => s.id === prev.statusId), next = 'statusId' in patch ? sts.find((s) => s.id === patch.statusId) : null
        if (sp.lockDone && was?.done && next && !next.done && !patch.reopenReason) {
          const r = await confirm(`완료된 ${itemNoun(boardRef.current)}${josa(itemNoun(boardRef.current), '을/를')} '${next.label}'(으)로 재개할까요?\n처리완료일은 비워지고, 재개 사유가 기록됩니다.`,
            { danger: false, okText: '재개', reason: true, reasonLabel: '재개 사유', reasonPlaceholder: '예: 고객 재요청, 처리 누락 발견' })
          if (!r) { const e = new Error('취소'); e.cancelled = true; throw e }
          patch = { ...patch, reopenReason: r }
        }
        const ac = sp.agreedCol
        if (ac && patch.values && (ac in patch.values) && !patch.changeReason) {
          const before = prev.values?.[ac] || '', nv = patch.values[ac] || ''
          if (before && before !== nv) {
            const col = boardRef.current.columns.find((c) => c.id === ac)
            const r = await confirm(`'${col?.name || '합의완료일'}'을(를) ${before} → ${nv || '없음'}(으)로 바꿉니다.\n최초 합의일은 따로 보존되며, 변경 사유가 기록됩니다.`,
              { danger: false, okText: '변경', reason: true, reasonLabel: '변경 사유', reasonPlaceholder: '예: 고객 요청으로 일정 조정' })
            if (!r) { const e = new Error('취소'); e.cancelled = true; throw e }
            patch = { ...patch, changeReason: r }
          }
        }
      }
      // 동시 수정 충돌 검사용: 편집을 시작할 때 화면에 있던 값 (서버가 그 사이 바뀌었으면 409)
      if (prev && !patch.base && ('name' in patch || patch.values)) {
        const base = {}
        if ('name' in patch) base.name = prev.name
        if (patch.values) base.values = Object.fromEntries(Object.keys(patch.values).map((k) => [k, prev.values?.[k] ?? '']))
        patch = { ...patch, base }
      }
      const local = { ...patch }
      delete local.base
      if ('assigneeId' in patch) { local.assignee = users.find((u) => u.id === patch.assigneeId) || null; delete local.assigneeId }
      delete local.reopenReason; delete local.changeReason
      if ('values' in patch) local.values = { ...(prev.values || {}), ...patch.values }
      setItems((items) => items.map((i) => (i.id === id ? { ...i, ...local } : i)))
      try {
        const saved = await api.patch(`/api/items/${id}`, patch)
        setItems((items) => items.map((i) => (i.id === id ? { ...i, ...saved, values: { ...(i.values || {}), ...(saved.values || {}) }, commentCount: i.commentCount, fileCount: i.fileCount } : i)))
        // 서버가 연결 값을 자동으로 채웠으면(예: 직원 → 고객사) 표시 이름을 받으려고 다시 불러옴
        if (patch.values && saved?.values && Object.keys(saved.values).some((k) => !(k in patch.values))) load()
        // 상태 변경으로 자동화 규칙이 실행됐으면(그룹·워크스페이스 이동) 알려 주고 다시 불러옴
        if ('statusId' in patch && !('groupId' in patch) && saved && prev && saved.groupId !== prev.groupId) {
          const g = boardRef.current.groups.find((x) => x.id === saved.groupId)
          toast(g ? `자동화: '${g.name}' 그룹으로 옮겼습니다.` : '자동화: 다른 워크스페이스로 옮겼습니다.', 'success')
          load()
        }
        return saved
      } catch (e) {
        setItems((items) => items.map((i) => (i.id === id ? prev : i)))
        toast(e.message, 'error')
        if (e.status === 409) load()  // 다른 사람이 바꾼 최신 값으로 화면 갱신
        throw e
      }
    },
    async addItem(groupId, name, extra, parentId) {
      try {
        let item = await api.post(`/api/groups/${groupId}/items`, { name, parentId: parentId || null })
        if (extra) item = { ...item, ...(await api.patch(`/api/items/${item.id}`, extra)) }
        setItems((items) => [...items, item])
        refreshBoards()
        return item
      } catch (e) { toast(e.message, 'error') }
    },
    /** 삭제 권한: 시스템 관리자, 워크스페이스 관리자, 등록한 사람 (서버도 같은 규칙으로 막음) */
    canDelete(id) {
      const b = boardRef.current, it = b?.items.find((i) => i.id === id)
      return !!it && (me.role === 'ADMIN' || b.canManage || it.createdById === me.id)
    },
    async deleteItem(id) {
      const it = boardRef.current.items.find((i) => i.id === id)
      const b = boardRef.current, noun = (it.parentId ? '하위 ' : '') + itemNoun(b)
      const reason = await confirm(`'${itemTitle(b, it)}' ${noun}${josa(noun, '을/를')} 삭제할까요?\n${it.parentId ? '' : `하위 ${itemNoun(b)}·`}댓글·첨부파일과 함께 휴지통으로 옮겨지며, 시스템 관리자가 복원할 수 있습니다.`,
        { okText: '삭제', reason: true, reasonLabel: '삭제 사유', reasonPlaceholder: '예: 중복 등록, 잘못 등록함' })
      if (!reason) return false
      try {
        await api.del(`/api/items/${id}?reason=${encodeURIComponent(reason)}`)
        setItems((items) => items.filter((i) => i.id !== id))
        refreshBoards()
        return true
      } catch (e) { toast(e.message, 'error'); return false }
    },
    async reorder(groupId, ids) {
      setItems((items) => items.map((i) => {
        const idx = ids.indexOf(i.id)
        return idx >= 0 ? { ...i, groupId, sortOrder: idx + 1 } : i
      }))
      try { await api.post(`/api/groups/${groupId}/items/reorder`, { ids }) } catch (e) { toast(e.message, 'error'); load() }
    },
    async addGroup(name) {
      try {
        const colors = ['#0061F2', '#6900C7', '#00AC69', '#F4A100', '#E81500', '#00CFD5', '#F76400']
        const g = await api.post(`/api/boards/${boardId}/groups`, { name, color: colors[boardRef.current.groups.length % colors.length] })
        setBoard((b) => ({ ...b, groups: [...b.groups, g] }))
      } catch (e) { toast(e.message, 'error') }
    },
    async updateGroup(id, patch) {
      const g = boardRef.current.groups.find((x) => x.id === id)
      try {
        const saved = await api.put(`/api/groups/${id}`, { name: g.name, color: g.color, ...patch })
        setBoard((b) => ({ ...b, groups: b.groups.map((x) => (x.id === id ? saved : x)) }))
      } catch (e) { toast(e.message, 'error') }
    },
    async deleteGroup(id) {
      const g = boardRef.current.groups.find((x) => x.id === id)
      const count = boardRef.current.items.filter((i) => i.groupId === id).length
      const ans = await confirm(`'${g.name}' 그룹을 삭제할까요?${count ? `\n포함된 ${itemNoun(boardRef.current)} ${count}개는 휴지통으로 옮겨지며, 시스템 관리자가 복원할 수 있습니다.` : ''}`,
        count ? { okText: '삭제', reason: true, reasonLabel: '삭제 사유', reasonPlaceholder: '예: 업무 구분 변경으로 그룹 정리' } : { okText: '삭제' })
      if (!ans) return
      try {
        await api.del(`/api/groups/${id}${count ? `?reason=${encodeURIComponent(ans)}` : ''}`)
        setBoard((b) => ({ ...b, groups: b.groups.filter((x) => x.id !== id), items: b.items.filter((i) => i.groupId !== id) }))
        refreshBoards()
      } catch (e) { toast(e.message, 'error') }
    },
    async moveGroup(id, dir) {
      const gs = [...boardRef.current.groups]
      const idx = gs.findIndex((g) => g.id === id)
      const to = idx + dir
      if (to < 0 || to >= gs.length) return
      ;[gs[idx], gs[to]] = [gs[to], gs[idx]]
      setBoard((b) => ({ ...b, groups: gs }))
      try { await api.post(`/api/boards/${boardId}/groups/reorder`, { ids: gs.map((g) => g.id) }) } catch (e) { toast(e.message, 'error'); load() }
    },
    openItem: (id) => navigate(`/board/${boardId}/${activeId || view || ""}?item=${id}`),
    moveItem: (id) => setMoving(boardRef.current.items.find((i) => i.id === id)),
    patchLocal: (id, patch) => setItems((items) => items.map((i) => (i.id === id ? { ...i, ...patch } : i))),
    reload: load,
  }), [boardId, view, activeId, users, load, refreshBoards, toast]) // eslint-disable-line

  const dutyIndex = useMemo(() => buildDutyIndex(board ? board.duties || [] : []), [board?.duties]) // eslint-disable-line
  // 검색어 입력 중에도 화면이 버벅이지 않도록 필터 계산은 지연값으로, 결과는 메모
  const deferredFilters = useDeferredValue(filters)
  const derived = useMemo(() => {
    if (!board) return null
    const statusById = Object.fromEntries(board.statuses.map((s) => [s.id, s]))
    const topItems = board.items.filter((i) => !i.parentId)
    const subsByParent = {}
    for (const i of board.items) if (i.parentId) (subsByParent[i.parentId] ||= []).push(i)
    for (const k in subsByParent) subsByParent[k].sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)
    const filtered = applyFilters(board, deferredFilters, sort, { me, statusById, dutyIndex, watching })
    return { statusById, topItems, subsByParent, filtered }
  }, [board, deferredFilters, sort, me, dutyIndex, watching])
  useEffect(() => {
    if (filters.watch === 'me' && watching == null) api.get('/api/me/watching').then((ids) => setWatching(new Set(ids))).catch(() => setWatching(new Set()))
  }, [filters.watch]) // eslint-disable-line
  // 탭을 바꾸면 선택 해제, Esc 로도 해제 (열린 창·입력 중이 아닐 때)
  useEffect(() => { setSelected(new Set()) }, [activeId])
  useEffect(() => {
    if (!selected.size) return
    const onKey = (e) => {
      if (e.key !== 'Escape' || itemParam || document.querySelector('.modal, .popover')) return
      const el = document.activeElement
      if (el && (/^(TEXTAREA|SELECT)$/.test(el.tagName) || (el.tagName === 'INPUT' && el.type !== 'checkbox'))) return
      setSelected(new Set())
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [selected.size, itemParam])
  if (error) return <div className="page"><div className="form-error">{error}</div></div>
  if (!board) return <div className="center-screen"><Spinner /></div>

  const { statusById, topItems, subsByParent, filtered } = derived
  const noun = itemNoun(board) // 이 워크스페이스에서 한 건을 부르는 말 (업무명 컬럼 이름 기준)
  // 선택 목록: 삭제·이동으로 사라진 업무는 자동으로 빠짐
  const liveIds = new Set(board.items.map((i) => i.id))
  const selIds = new Set([...selected].filter((id) => liveIds.has(id)))
  const setSel = (ids, on) => setSelected((cur) => { const n = new Set(cur); for (const id of ids) { if (on) n.add(id); else n.delete(id) } return n })
  const canManage = board.canManage
  const assignees = [...new Map(topItems.filter((i) => i.assignee).map((i) => [i.assignee.id, i.assignee])).values()]
  const filtering = isFiltering(deferredFilters)
  const viewKey = active?.type || 'list'
  const props = { board, items: filtered, statusById, ops, users, filtering, dutyIndex, subsByParent, hidden, sorted: sort.key !== 'manual', onClearFilter: () => setFilters(EMPTY_FILTERS),
    header: { sort, onSort: setSort, filters, onFilter: setF, onHide: (key) => setHidden([...hidden, key]), me },
    canManage, onReload: () => load().then(refreshBoards), onColumnSettings: canManage ? () => setSettings('columns') : null,
    selection: { has: (id) => selIds.has(id), set: setSel, size: selIds.size } }
  // 저장된 보기 설정과 지금 화면이 다르면 '보기 저장' 표시
  const currentConfig = { filters, sort, hidden }
  const saved = parseViewConfig(active, EMPTY_FILTERS)
  const dirty = !!active && JSON.stringify(currentConfig) !== JSON.stringify(saved)
  const saveView = async () => {
    try {
      const v = await api.put(`/api/views/${active.id}`, { config: JSON.stringify(currentConfig) })
      setBoard((b) => ({ ...b, views: b.views.map((x) => (x.id === v.id ? v : x)) }))
      toast(`'${v.name}' 보기에 저장했습니다.`, 'success')
    } catch (e) { toast(e.message, 'error') }
  }
  const resetView = () => { setFilters(saved.filters); setSort(saved.sort); setHidden(saved.hidden) }
  const done = topItems.filter((i) => statusById[i.statusId]?.done).length
  const extraAvail = [
    'priority',
    ...(board.versions?.length ? ['version'] : []),
    ...(board.groups.length > 1 ? ['groups'] : []), ...(columnsFor(board, 'ITEM').some((c) => c.type !== 'LONG_TEXT') ? ['fields'] : []),
    'watch', ...(board.links?.some((l) => l.type === 'BLOCKS') ? ['blocked'] : []),
  ]
  const extraShown = extraAvail.filter((k) => extra.includes(k) || hasValue(filters, k))

  return (
    <div className="board">
      <PageHeader title={<BoardSwitcher board={board} view={viewKey} />} icon="kanban"
        subtitle={<>
          {board.description && <span>{board.description}</span>}
          <span className="phs-stat">{columnConfig(board).showProgress
            ? <><span className="ring ring-light" style={{ '--p': topItems.length ? Math.floor(done / topItems.length * 100) : 0 }} /> 진행률 {topItems.length ? Math.floor(done / topItems.length * 100) : 0}% · {noun} {topItems.length}건</>
            : <>{topItems.length}건</>}{board.items.length > topItems.length ? ` (하위 ${board.items.length - topItems.length}건)` : ''} · {board.isPublic ? <><Icon name="globe" size={12} /> 모든 사용자 공개</> : <><Icon name="lock" size={12} /> 멤버 전용</>}</span>
        </>}
        actions={<>
          <div className="avatar-stack" title="담당자">
            {assignees.slice(0, 4).map((u) => <Avatar key={u.id} user={u} size={26} />)}
            {assignees.length > 4 && <span className="avatar avatar-more" style={{ width: '1.8rem', height: '1.8rem' }}>+{assignees.length - 4}</span>}
          </div>
          <FavoriteButton boardId={board.id} />
          <ExcelMenu board={board} onImported={() => { load(); refreshBoards() }} />
          <button className="btn btn-white btn-sm" title="워크스페이스 설정·멤버" onClick={() => setSettings('basic')}><Icon name={canManage ? 'settings' : 'users'} size={14} /> {canManage ? '설정' : '멤버'}</button>
        </>}
        primary={<button className="btn btn-primary btn-sm topbar-new" title={`새 ${noun}`} onClick={async () => {
          const it = await ops.addItem(board.groups[0].id, `새 ${noun}`)
          if (it) ops.openItem(it.id)
        }}><Icon name="plus" size={14} /><span>새 {noun}</span></button>} />

      <div className="page page-wide">
        <div className="card board-card">
          <div className="card-header board-toolbar">
            <ViewTabs board={board} active={active} setBoard={setBoard} currentConfig={currentConfig} />
            {!['duties', 'wiki', 'dashboard', 'form'].includes(viewKey) && (
              <div className="filters">
                <SearchToggle board={board} value={filters.q} onChange={(q) => setF({ q })} />
                <button className={`chip-btn ${filters.assignee === 'me' ? 'on' : ''}`} onClick={() => setF({ assignee: filters.assignee === 'me' ? '' : 'me' })} title={`내가 담당자인 ${noun}만`}><Icon name="user" size={13} /> 내 {noun}</button>
                <span className="filter-sep" />
                <StatusFilter value={filters.status} onChange={(v) => setF({ status: v })} statuses={board.statuses} />
                <PersonFilter noun={noun} value={filters.assignee} onChange={(v) => setF({ assignee: v })} users={users} me={me} />
                <DueFilter value={filters.due} onChange={(v) => setF({ due: v })} />
                {extraShown.includes('priority') && <PriorityFilter value={filters.priority} onChange={(v) => setF({ priority: v })} />}
                {extraShown.includes('version') && <VersionFilter value={filters.version} onChange={(v) => setF({ version: v })} versions={board.versions || []} />}
                {extraShown.includes('duty') && <DutyFilter value={filters.duty} onChange={(v) => setF({ duty: v })} index={dutyIndex} />}
                {extraShown.includes('groups') && <GroupFilter value={filters.groups} onChange={(groups) => setF({ groups })} groups={board.groups} />}
                {extraShown.includes('fields') && <FieldFilter board={board} value={filters.fields} onChange={(fields) => setF({ fields })} />}
                {extraShown.includes('watch') && <button className={`chip-btn ${filters.watch === 'me' ? 'on' : ''}`} onClick={() => setF({ watch: filters.watch === 'me' ? '' : 'me' })} title="내가 감시 중인 업무만"><Icon name="eye" size={13} /> 감시 중</button>}
                {extraShown.includes('blocked') && <button className={`chip-btn ${filters.watch === 'blocked' ? 'on' : ''}`} onClick={() => setF({ watch: filters.watch === 'blocked' ? '' : 'blocked' })} title="미완료 업무에 막혀 있는 업무만"><Icon name="alert" size={13} /> 차단됨</button>}
                <MoreFilters available={extraAvail} shown={extraShown} onPick={(k) => setExtra((e) => [...e, k])} />
                {(filtering || extra.length > 0) && <button className="link" onClick={() => { setFilters(EMPTY_FILTERS); setExtra([]) }}>모두 지우기</button>}
                <span className="grow" />
                {viewKey !== 'kanban' && <SortMenu sort={sort} onChange={setSort} board={board} />}
                {(viewKey === 'table' || viewKey === 'list') && <ColumnPicker board={board} hidden={hidden} keys={viewKey === 'list' ? ['priority', 'duty', 'status', 'start', 'due', 'assignee'] : null} onChange={setHidden} onManage={canManage ? () => setSettings('columns') : null} />}
              </div>
            )}
            {dirty && viewKey !== 'form' && (
              <div className="view-dirty">
                <span>이 보기의 필터·정렬·컬럼이 바뀌었습니다.</span>
                <button className="link" onClick={resetView}>되돌리기</button>
                <button className="btn btn-primary btn-sm" onClick={saveView}><Icon name="save" size={13} /> 보기에 저장</button>
              </div>
            )}
          </div>
          <div className="card-body board-body">
            {viewKey === 'list' && <ListView {...props} />}
            {viewKey === 'table' && <TableView {...props} />}
            {viewKey === 'kanban' && <KanbanView {...props} />}
            {viewKey === 'timeline' && <TimelineView {...props} />}
            {viewKey === 'dashboard' && <BoardDashboard {...props} />}
            {viewKey === 'roadmap' && <RoadmapView {...props} setBoard={setBoard} canManage={canManage} />}
            {viewKey === 'wiki' && <WikiView board={board} />}
            {viewKey === 'form' && <FormView board={board} view={active} canManage={canManage} items={board.items} onSubmitted={load} />}
            {viewKey === 'duties' && <DutyView board={board} index={dutyIndex} users={users} canManage={canManage} reload={load}
              onFilter={(id) => {
                const target = board.views.find((v) => v.type === 'list') || board.views.find((v) => v.type === 'table')
                pendingFilters.current = { duty: String(id) }
                if (target) navigate(`/board/${boardId}/${target.id}`); else setFilters({ ...EMPTY_FILTERS, duty: String(id) })
              }} />}
          </div>
        </div>
      </div>

      {itemParam && <ItemDrawer key={itemParam} itemId={Number(itemParam)} board={board} statusById={statusById} ops={ops}
        onClose={() => navigate(`/board/${boardId}/${active?.id || ''}`)} />}
      {settings && <BoardSettings board={board} initialTab={settings} onClose={() => setSettings(null)} onSaved={() => { load(); refreshBoards() }} onColumnsChanged={load}
        onDeleted={() => { refreshBoards(); navigate('/') }} />}
      {moving && <MoveItemModal item={moving} board={board} onClose={() => setMoving(null)}
        onMoved={() => {
          const id = moving.id
          setItems((items) => items.filter((i) => i.id !== id && i.parentId !== id))
          refreshBoards()
          navigate(`/board/${boardId}/${active?.id || ''}`)
        }} />}
      {selIds.size > 0 && (viewKey === 'list' || viewKey === 'table') && (
        <BulkBar board={board} ids={[...selIds]} canDelete={ops.canDelete} confirm={confirm} onClear={() => setSelected(new Set())} onDone={load} />
      )}
      {confirmDialog}
    </div>
  )
}


/** 워크스페이스 머리글의 즐겨찾기 버튼 (사이드바 목록의 favorite 상태를 따름) */
function FavoriteButton({ boardId }) {
  const { boards } = useApp()
  const toggle = useFavorite()
  const b = boards.find((x) => x.id === boardId)
  if (!b) return null
  return (
    <button className={`btn btn-white btn-sm fav-btn ${b.favorite ? 'on' : ''}`} title={b.favorite ? '즐겨찾기 해제' : '즐겨찾기에 추가'} onClick={() => toggle(b)}>
      <Icon name="star" size={14} /> {b.favorite ? '즐겨찾기됨' : '즐겨찾기'}
    </button>
  )
}
