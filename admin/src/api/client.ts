// 后端统一响应：{ code: 0, message: 'success', data: T }
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

const TOKEN_KEY = 'ec_token'
const USER_KEY = 'ec_user'

export interface CurrentUser {
  userId: number
  phone: string
  nickname: string
  role: string
  orgId?: number
  elderId?: number
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getUser(): CurrentUser | null {
  const raw = localStorage.getItem(USER_KEY)
  return raw ? (JSON.parse(raw) as CurrentUser) : null
}

export function setSession(token: string, user: CurrentUser) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export class ApiError extends Error {
  status: number
  code: number
  constructor(status: number, code: number, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init.headers as Record<string, string> | undefined)
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const resp = await fetch(path, { ...init, headers })
  const text = await resp.text()
  const body = text ? (JSON.parse(text) as ApiResult<T>) : ({ code: 0, message: '', data: null as T })

  if (!resp.ok || body.code !== 0) {
    if (resp.status === 401) {
      clearSession()
    }
    throw new ApiError(resp.status, body.code, body.message || `请求失败（${resp.status}）`)
  }
  return body.data
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, payload?: unknown) =>
    request<T>(path, { method: 'POST', body: payload === undefined ? undefined : JSON.stringify(payload) })
}
