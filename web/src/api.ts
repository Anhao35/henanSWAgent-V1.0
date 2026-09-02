import axios from 'axios'

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
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
  onEvent: (event: Record<string, string>) => void,
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
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const line of lines) {
      if (line.trim()) onEvent(JSON.parse(line))
    }
  }
  if (buffer.trim()) onEvent(JSON.parse(buffer))
}

