/** 화면 전체 배율 — 아이콘·아바타·팝오버 등 JS 로 크기를 정하는 요소에 적용 (CSS 는 이미 확대된 값) */
export const UI_SCALE = 1.12
/** 화면 폭에 따른 루트 글자 크기 배율 (styles.css 의 html font-size 와 연동) */
export function rootScale() {
  try { return (parseFloat(getComputedStyle(document.documentElement).fontSize) || 16) / 16 } catch { return 1 }
}
export const sc = (n) => Math.round(n * UI_SCALE * rootScale())

export const PRIORITIES = [
  { value: 'CRITICAL', label: '긴급' },
  { value: 'HIGH', label: '높음' },
  { value: 'MEDIUM', label: '보통' },
  { value: 'LOW', label: '낮음' },
]
export const priorityOf = (v) => PRIORITIES.find((p) => p.value === v)

// 채도를 낮춘 차분한 팔레트 (상태·그룹·보드 색상용)
export const PALETTE = [
  '#0061F2', '#5800E8', '#6900C7', '#E30059', '#E81500', '#F76400',
  '#F4A100', '#00AC69', '#00BA94', '#00CFD5', '#69707A', '#212832',
]

export function todayStr() {
  const d = new Date()
  return fmtDate(d)
}
export function fmtDate(d) {
  const y = d.getFullYear(), m = String(d.getMonth() + 1).padStart(2, '0'), day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
export function parseDate(s) {
  if (!s) return null
  const [y, m, d] = s.split('-').map(Number)
  return new Date(y, m - 1, d)
}
export function addDays(s, n) {
  const d = parseDate(s); d.setDate(d.getDate() + n); return fmtDate(d)
}
export function diffDays(a, b) {
  return Math.round((parseDate(b) - parseDate(a)) / 86400000)
}
export function shortDate(s) {
  if (!s) return ''
  const d = parseDate(s)
  return `${d.getMonth() + 1}/${d.getDate()}`
}
export function relTime(iso) {
  const t = new Date(iso).getTime()
  const s = Math.floor((Date.now() - t) / 1000)
  if (s < 60) return '방금'
  if (s < 3600) return `${Math.floor(s / 60)}분 전`
  if (s < 86400) return `${Math.floor(s / 3600)}시간 전`
  if (s < 86400 * 7) return `${Math.floor(s / 86400)}일 전`
  return iso.slice(0, 10)
}
export function fmtSize(n) {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
export function isOverdue(item, status) {
  return !!item.dueDate && item.dueDate < todayStr() && !(status && status.done)
}
const AVATAR_COLORS = ['#0061F2', '#00AC69', '#6900C7', '#E81500', '#00BA94', '#E30059', '#F76400', '#5800E8']
export function avatarColor(id) { return AVATAR_COLORS[(id || 0) % AVATAR_COLORS.length] }
export function initials(name) {
  const n = (name || '?').trim()
  if (n.includes('관리자')) return '관리'
  if (/^[가-힣]{3,4}$/.test(n)) return n.slice(-2) // 한국어 이름은 이름 두 글자
  return n.slice(0, 2).toUpperCase()
}

const WD = ['일', '월', '화', '수', '목', '금', '토']
/** 업무 날짜 표기: 오늘/내일/어제, 올해는 'M월 D일', 그 외 'YYYY.M.D' */
export function friendlyDate(s) {
  if (!s) return ''
  const t = todayStr()
  const diff = diffDays(t, s)
  if (diff === 0) return '오늘'
  if (diff === 1) return '내일'
  if (diff === -1) return '어제'
  const d = parseDate(s)
  if (s.slice(0, 4) === t.slice(0, 4)) return `${d.getMonth() + 1}월 ${d.getDate()}일`
  return `${d.getFullYear()}.${d.getMonth() + 1}.${d.getDate()}`
}
export function weekday(s) { return WD[parseDate(s).getDay()] }
export function fmtDateTime(iso) {
  if (!iso) return ''
  return iso.slice(0, 16).replace('T', ' ')
}
/** 보드 아이콘 색상 */
export function boardColor(id) { return PALETTE[(id * 5) % PALETTE.length] }
