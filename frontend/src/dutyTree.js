// 담당업무 평면 목록 → 트리 도우미
export function buildDutyIndex(duties) {
  const byId = new Map(duties.map((d) => [d.id, d]))
  const children = new Map()
  for (const d of duties) {
    const key = d.parentId != null && byId.has(d.parentId) ? d.parentId : null
    if (!children.has(key)) children.set(key, [])
    children.get(key).push(d)
  }
  for (const l of children.values()) l.sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)
  const path = (id) => {
    const names = []
    let cur = byId.get(id), guard = 0
    while (cur && guard++ < 200) { names.unshift(cur.name); cur = cur.parentId != null ? byId.get(cur.parentId) : null }
    return names.join(' > ')
  }
  const descendants = (id) => {
    const out = new Set([id])
    const stack = [id]
    while (stack.length) for (const c of children.get(stack.pop()) || []) { out.add(c.id); stack.push(c.id) }
    return out
  }
  /** 깊이 우선 평탄화 (depth 포함). collapsed 에 든 노드의 자식은 생략 */
  const flatten = (collapsed = new Set()) => {
    const out = []
    const walk = (parent, depth) => {
      for (const d of children.get(parent) || []) {
        const kids = children.get(d.id) || []
        out.push({ ...d, depth, hasChildren: kids.length > 0 })
        if (!collapsed.has(d.id)) walk(d.id, depth + 1)
      }
    }
    walk(null, 0)
    return out
  }
  const rootOf = (id) => {
    let cur = byId.get(id), guard = 0
    while (cur && cur.parentId != null && byId.has(cur.parentId) && guard++ < 200) cur = byId.get(cur.parentId)
    return cur
  }
  return { byId, children, path, descendants, flatten, rootOf }
}
