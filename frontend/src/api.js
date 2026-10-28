// fetch 래퍼: 세션 쿠키 + CSRF(XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더)
function csrf() {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return m ? decodeURIComponent(m[1]) : ''
}

export class ApiError extends Error {
  constructor(status, message, code) {
    super(message)
    this.status = status
    this.code = code
  }
}

let onUnauthorized = () => {}
export function setUnauthorizedHandler(fn) { onUnauthorized = fn }

async function request(method, url, body, isForm) {
  const headers = { 'X-XSRF-TOKEN': csrf() }
  let payload
  if (body !== undefined && !isForm) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  } else if (isForm) {
    payload = body
  }
  const res = await fetch(url, { method, headers, body: payload, credentials: 'same-origin' })
  if (res.status === 401 && !url.startsWith('/api/auth/')) {
    onUnauthorized()
  }
  const text = await res.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { data = text }
  if (!res.ok) {
    const msg = (data && data.message) || (res.status === 403 ? '권한이 없거나 세션이 만료되었습니다.' : `요청 실패 (${res.status})`)
    throw new ApiError(res.status, msg, data && data.code)
  }
  return data
}

export const api = {
  get: (u) => request('GET', u),
  post: (u, b) => request('POST', u, b ?? {}),
  put: (u, b) => request('PUT', u, b),
  patch: (u, b) => request('PATCH', u, b),
  del: (u) => request('DELETE', u),
  postForm: (u, form) => request('POST', u, form, true),
  upload: (u, file) => { const f = new FormData(); f.append('file', file); return request('POST', u, f, true) },
}
