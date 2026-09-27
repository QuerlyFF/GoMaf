import type { ApiData, ApiError, CsrfToken, Session } from './types'

let csrf: CsrfToken | null = null

export class ApiClientError extends Error {
  constructor(public readonly details: ApiError) {
    super(details.message)
  }
}

async function parseJson<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T
  const body = (await response.json()) as T | ApiError
  if (!response.ok) throw new ApiClientError(body as ApiError)
  return body as T
}

export async function ensureCsrf(): Promise<CsrfToken> {
  if (csrf) return csrf
  const response = await fetch('/api/v1/csrf', { credentials: 'same-origin' })
  const body = await parseJson<ApiData<CsrfToken>>(response)
  csrf = body.data
  return csrf
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body != null && !headers.has('Content-Type') && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
    const token = await ensureCsrf()
    headers.set(token.headerName, token.token)
  }

  const response = await fetch(path, {
    ...init,
    headers,
    credentials: 'same-origin',
  })
  if (response.status === 401) csrf = null
  return parseJson<T>(response)
}

export function getSession(): Promise<ApiData<Session>> {
  return apiRequest('/api/v1/session')
}

export async function login(username: string, password: string, captcha: string): Promise<ApiData<Session>> {
  const result = await apiRequest<ApiData<Session>>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password, captcha }),
  })
  csrf = null
  return result
}


export async function registerAccount(
  username: string,
  password: string,
  passwordConfirm: string,
  captcha: string,
): Promise<ApiData<{ userId: number; username: string }>> {
  const result = await apiRequest<ApiData<{ userId: number; username: string }>>('/api/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ username, password, passwordConfirm, captcha }),
  })
  csrf = null
  return result
}

export async function logout(): Promise<void> {
  await apiRequest<void>('/api/v1/auth/logout', { method: 'POST' })
  csrf = null
}
