import { clearPlatformSession, getPlatformToken, type PlatformPrincipal } from './session'

interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

export class ApiClientError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message)
    this.name = 'ApiClientError'
  }
}

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') ?? ''

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = getPlatformToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(`${apiBaseUrl}${path}`, { ...init, headers })
  let body: ApiResponse<T>
  try {
    body = JSON.parse(await response.text()) as ApiResponse<T>
  } catch {
    if (response.status === 401) clearPlatformSession()
    throw new ApiClientError(
      response.status,
      'INVALID_RESPONSE',
      response.ok ? '平台返回了无法识别的数据格式' : '平台服务暂不可用，请检查后端服务后重试'
    )
  }
  if (!response.ok || body.code !== 'OK') {
    if (response.status === 401) clearPlatformSession()
    throw new ApiClientError(response.status, body.code, body.message || `请求失败 (${response.status})`)
  }
  return body.data
}

export interface PlatformLoginResult {
  accessToken: string
  expiresAt: string
  principal: PlatformPrincipal
}

export const platformApi = {
  login: (username: string, password: string) => request<PlatformLoginResult>('/api/platform/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password })
  }),
  me: () => request<PlatformPrincipal>('/api/platform/v1/auth/me')
}
