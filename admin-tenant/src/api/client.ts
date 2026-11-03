import { clearTenantSession, getTenantToken, TENANT_SESSION_EXPIRED_EVENT } from './session'

interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

export class TenantApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message)
    this.name = 'TenantApiError'
  }
}

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') ?? ''

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = getTenantToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(`${apiBaseUrl}${path}`, { ...init, headers })
  let body: ApiResponse<T>
  try {
    body = JSON.parse(await response.text()) as ApiResponse<T>
  } catch {
    if (response.status === 401) {
      clearTenantSession()
      window.dispatchEvent(new Event(TENANT_SESSION_EXPIRED_EVENT))
    }
    throw new TenantApiError(
      response.status,
      'INVALID_RESPONSE',
      response.ok ? '企业服务返回了无法识别的数据格式' : '企业服务暂不可用，请检查后端服务后重试'
    )
  }
  if (!response.ok || body.code !== 'OK') {
    if (response.status === 401) {
      clearTenantSession()
      window.dispatchEvent(new Event(TENANT_SESSION_EXPIRED_EVENT))
    }
    throw new TenantApiError(response.status, body.code, body.message || `请求失败 (${response.status})`)
  }
  return body.data
}
