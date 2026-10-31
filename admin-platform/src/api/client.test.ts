import { beforeEach, describe, expect, it, vi } from 'vitest'
import { savePlatformSession } from './session'
import { ApiClientError, request } from './client'

const principal = { userId: 9, publicId: 'pa_9', username: 'operator', roleCodes: ['OWNER'], permissionCodes: [] }

describe('platform API client', () => {
  beforeEach(() => {
    localStorage.clear()
    savePlatformSession('platform-token', '2030-01-01T00:00:00Z', principal)
    vi.stubGlobal('fetch', vi.fn())
  })

  it('attaches only the platform bearer token and parses the API envelope', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'OK', message: 'success', data: { ready: true } }), { status: 200 }))
    const data = await request<{ ready: boolean }>('/api/platform/v1/auth/me')
    expect(data).toEqual({ ready: true })
    const headers = new Headers(vi.mocked(fetch).mock.calls[0][1]?.headers)
    expect(headers.get('Authorization')).toBe('Bearer platform-token')
  })

  it('clears the platform session on 401 and preserves other app sessions', async () => {
    localStorage.setItem('tenant.access_token', 'tenant-token')
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: 'expired', data: null }), { status: 401 }))
    await expect(request('/api/platform/v1/auth/me')).rejects.toMatchObject({ status: 401 })
    expect(localStorage.getItem('platform.access_token')).toBeNull()
    expect(localStorage.getItem('tenant.access_token')).toBe('tenant-token')
  })

  it('preserves the platform session on 403', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'PERMISSION_DENIED', message: 'forbidden', data: null }), { status: 403 }))
    await expect(request('/api/platform/v1/enterprises')).rejects.toBeInstanceOf(ApiClientError)
    expect(localStorage.getItem('platform.access_token')).toBe('platform-token')
  })
})
