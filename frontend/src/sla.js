/** SLA 계산 — 백엔드 SlaCalc 와 같은 규칙 (영업시간 = 평일 09:00~18:00) */
const OPEN = 9, CLOSE = 18

export function addBusinessHours(from, hours) {
  let t = new Date(from)
  let remain = hours * 60
  for (let guard = 0; guard < 2000; guard++) {
    const day = t.getDay()
    const mins = t.getHours() * 60 + t.getMinutes()
    if (day === 0 || day === 6 || mins >= CLOSE * 60) { t = new Date(t.getFullYear(), t.getMonth(), t.getDate() + 1, OPEN, 0, 0); continue }
    if (mins < OPEN * 60) t = new Date(t.getFullYear(), t.getMonth(), t.getDate(), OPEN, 0, 0)
    const untilClose = CLOSE * 60 - (t.getHours() * 60 + t.getMinutes())
    if (remain <= untilClose) return new Date(t.getTime() + remain * 60000)
    remain -= untilClose
    t = new Date(t.getFullYear(), t.getMonth(), t.getDate() + 1, OPEN, 0, 0)
  }
  return t
}

export function deadline(createdAt, hours, business) {
  const from = new Date(createdAt)
  return business ? addBusinessHours(from, hours) : new Date(from.getTime() + hours * 3600000)
}

export function fmtHours(h) {
  if (h == null) return ''
  const n = Number(h)
  if (n >= 48 && n % 24 === 0) return `${n / 24}일`
  return `${Number.isInteger(n) ? n : n.toFixed(1)}h`
}

/** 남은/초과 시간을 짧게: 3h, 2일, -5h */
export function fmtRemain(ms) {
  const sign = ms < 0 ? '-' : ''
  const abs = Math.abs(ms)
  const h = abs / 3600000
  if (h < 1) return `${sign}${Math.max(1, Math.round(abs / 60000))}분`
  if (h < 48) return `${sign}${Math.round(h)}h`
  return `${sign}${Math.round(h / 24)}일`
}

/**
 * 업무의 SLA 상태.
 * 반환: null(유형에 목표 없음) 또는 { kind: 'resolve'|'response', state: 'met'|'ok'|'warn'|'breach'|'done', label, title, ms }
 */
export function slaOf(item, type, board, now = Date.now()) {
  if (!type || (!type.resolveHours && !type.responseHours)) return null
  const business = board?.slaBusinessHours
  // 응답 목표: 응답 전이면 응답 기준, 응답했으면 처리 기준
  if (type.responseHours && !item.respondedAt && !item.resolvedAt) {
    const dl = deadline(item.createdAt, type.responseHours, business)
    return judge('response', dl, now, '응답')
  }
  if (!type.resolveHours) {
    if (item.respondedAt) return { kind: 'response', state: 'met', label: '응답', title: `응답 완료 ${fmt(item.respondedAt)}` }
    return null
  }
  const dl = deadline(item.createdAt, type.resolveHours, business)
  if (item.resolvedAt) {
    const met = new Date(item.resolvedAt) <= dl
    return { kind: 'resolve', state: met ? 'met' : 'breach', done: true, label: met ? '준수' : '초과', title: `${met ? '목표 안에 처리' : '목표 초과 처리'} · 처리 ${fmt(item.resolvedAt)} / 목표 ${fmt(dl)}`, ms: dl - new Date(item.resolvedAt) }
  }
  return judge('resolve', dl, now, '처리')
}

function judge(kind, dl, now, word) {
  const ms = dl - now
  const h = ms / 3600000
  const state = ms < 0 ? 'breach' : h <= 4 ? 'warn' : 'ok'
  const label = ms < 0 ? `${word} ${fmtRemain(ms)}` : `${word} ${fmtRemain(ms)}`
  return { kind, state, label, title: `${word} 목표 ${fmt(dl)} (${ms < 0 ? fmtRemain(-ms) + ' 초과' : fmtRemain(ms) + ' 남음'})`, ms }
}

function fmt(d) {
  const x = new Date(d)
  const p = (n) => String(n).padStart(2, '0')
  return `${x.getFullYear()}-${p(x.getMonth() + 1)}-${p(x.getDate())} ${p(x.getHours())}:${p(x.getMinutes())}`
}

export const RECUR_OPTIONS = [
  ['', '반복 없음'], ['DAILY:1', '매일'], ['WEEKLY:1', '매주'], ['WEEKLY:2', '2주마다'], ['MONTHLY:1', '매월'], ['MONTHLY:3', '분기마다'], ['MONTHLY:6', '반기마다'], ['YEARLY:1', '매년'],
]
export function recurLabel(rule) {
  if (!rule) return ''
  const hit = RECUR_OPTIONS.find(([k]) => k === rule)
  if (hit) return hit[1]
  const [u, n] = rule.split(':')
  return `${n}${{ DAILY: '일', WEEKLY: '주', MONTHLY: '개월', YEARLY: '년' }[u] || ''}마다`
}

export const LINK_TYPES = [
  ['BLOCKS', '차단함', '차단됨'], ['RELATES', '관련', '관련'], ['DUPLICATES', '중복함', '중복됨'], ['CAUSES', '원인임', '원인'],
]
export function linkLabel(type, outgoing) {
  const t = LINK_TYPES.find(([k]) => k === type)
  return t ? (outgoing ? t[1] : t[2]) : type
}

/** 완료되지 않은 업무가 이 업무를 차단하는지 (board.links 기준) */
export function blockedBy(item, board, statusById) {
  const out = []
  for (const l of board.links || []) {
    if (l.type !== 'BLOCKS' || l.toItemId !== item.id) continue
    const from = board.items.find((i) => i.id === l.fromItemId)
    if (!from) { out.push({ id: l.fromItemId, external: true }); continue }
    if (!statusById[from.statusId]?.done) out.push(from)
  }
  return out
}

export const itemKey = (board, item) => (item.keyNum != null ? `${board.keyPrefix || 'TB'}-${item.keyNum}` : `#${item.id}`)
