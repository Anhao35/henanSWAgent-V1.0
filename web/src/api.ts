import axios from 'axios'

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  // Spring Security 6 returns a BREACH-protected (masked) token from
  // /auth/csrf, while the XSRF-TOKEN cookie contains the raw token. Axios
  // would otherwise overwrite our masked header with that raw cookie value,
  // causing every mutating request to be rejected with HTTP 403.
  withXSRFToken: false,
  timeout: 30_000,
})

let csrfToken = ''

export async function ensureCsrf() {
  if (csrfToken) return csrfToken
  const { data } = await api.get('/auth/csrf')
  csrfToken = data.token
  return csrfToken
}

api.interceptors.request.use(async (config) => {
  const method = (config.method || 'get').toLowerCase()
  if (!['get', 'head', 'options'].includes(method)) {
    config.headers['X-XSRF-TOKEN'] = await ensureCsrf()
  }
  return config
})

export async function streamRequest(
  url: string,
  body: unknown,
  onEvent: (event: Record<string, any>) => void,
) {
  const token = await ensureCsrf()
  const response = await fetch(`/api${url}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': token,
      'X-Request-ID': crypto.randomUUID(),
    },
    body: JSON.stringify(body),
  })
  if (!response.ok || !response.body) {
    const error = await response.json().catch(() => ({ message: `请求失败（${response.status}）` }))
    throw new Error(error.message || `请求失败（${response.status}）`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let terminal = false
  function dispatch(line: string) {
    if (!line.trim()) return
    const event = JSON.parse(line)
    if (event.type === 'done' || event.type === 'error') terminal = true
    onEvent(event)
  }
  try {
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const line of lines) {
      dispatch(line)
    }
  }
  buffer += decoder.decode()
  dispatch(buffer)
  if (!terminal) throw new Error('连接已中断，任务可能仍在执行；请查看运行过程，不要重复提交')
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
}
