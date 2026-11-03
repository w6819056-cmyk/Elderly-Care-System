import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request, TenantApiError } from './client'
import { saveTenantSession } from './session'

const principal = { staffId: 4, staffPublicId: 'st_4', username: 'hq-admin', staffType: 'HQ' as const,
  homeStoreId: null, accessContext: { permissionCodes: [] } }

describe('tenant API client', () => {
  beforeEach(() => {
    localStorage.clear()
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', principal)
    vi.stubGlobal('fetch', vi.fn())
  })

  it('attaches only the tenant bearer token and parses the API envelope', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'OK', message: 'success', data: { ready: true } }), { status: 200 }))
    expect(await request<{ ready: boolean }>('/api/tenant/v1/auth/me')).toEqual({ ready: true })
    const headers = new Headers(vi.mocked(fetch).mock.calls[0][1]?.headers)
    expect(headers.get('Authorization')).toBe('Bearer tenant-token')
  })

  it('clears the tenant session on 401 while preserving platform credentials', async () => {
    localStorage.setItem('platform.access_token', 'platform-token')
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: 'expired', data: null }), { status: 401 }))
    await expect(request('/api/tenant/v1/auth/me')).rejects.toMatchObject({ status: 401 })
    expect(localStorage.getItem('tenant.access_token')).toBeNull()
    expect(localStorage.getItem('platform.access_token')).toBe('platform-token')
  })

  it('keeps the tenant session on 403', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'PERMISSION_DENIED', message: 'forbidden', data: null }), { status: 403 }))
    await expect(request('/api/tenant/v1/hq/modules')).rejects.toBeInstanceOf(TenantApiError)
    expect(localStorage.getItem('tenant.access_token')).toBe('tenant-token')
  })

  it('reports an empty non-JSON service response without exposing a JSON parse error', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('', { status: 502, statusText: 'Bad Gateway' }))
    await expect(request('/api/tenant/v1/auth/me')).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_RESPONSE',
      message: '企业服务暂不可用，请检查后端服务后重试'
    })
  })

  it('clears the tenant session on 401 even when the response body is not JSON', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('', { status: 401 }))
    await expect(request('/api/tenant/v1/auth/me')).rejects.toMatchObject({ status: 401, code: 'INVALID_RESPONSE' })
    expect(localStorage.getItem('tenant.access_token')).toBeNull()
  })
})
