import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, useTenantAuth } from './AuthProvider'
import { saveTenantSession } from '../api/session'

const cachedPrincipal = { staffId: 4, staffPublicId: 'st_4', username: 'cached', staffType: 'HQ' as const,
  homeStoreId: null, accessContext: { permissionCodes: ['core.module.view'] } }
const currentPrincipal = { ...cachedPrincipal, username: 'fresh', accessContext: { permissionCodes: ['iam.role.view'] } }

function Probe() {
  const auth = useTenantAuth()
  return <output>{auth.status}:{auth.principal?.username ?? '-'}:{auth.principal?.accessContext.permissionCodes[0] ?? '-'}</output>
}

describe('tenant auth bootstrap', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn())
  })

  it('replaces cached tenant identity and permissions with the /me response', async () => {
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', cachedPrincipal)
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'OK', message: 'success', data: currentPrincipal }), { status: 200 }))
    render(<AuthProvider><Probe /></AuthProvider>)
    expect(await screen.findByText('authenticated:fresh:iam.role.view')).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('tenant.principal') ?? '{}').accessContext.permissionCodes).toEqual(['iam.role.view'])
  })

  it('keeps the tenant token but exposes no principal when the service is offline', async () => {
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', cachedPrincipal)
    vi.mocked(fetch).mockRejectedValue(new TypeError('network offline'))
    render(<AuthProvider><Probe /></AuthProvider>)
    expect(await screen.findByText('unavailable:-:-')).toBeInTheDocument()
    expect(localStorage.getItem('tenant.access_token')).toBe('tenant-token')
  })
})
