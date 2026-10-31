import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from './AuthProvider'
import { RequireTenantWorkspace } from './RequireTenantWorkspace'
import { saveTenantSession } from '../api/session'

const principal = { staffId: 5, staffPublicId: 'st_5', username: 'store-user', staffType: 'STORE' as const,
  homeStoreId: 11, accessContext: { permissionCodes: ['store.service.view'] } }

describe('tenant workspace route guard', () => {
  beforeEach(() => {
    localStorage.clear()
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', principal)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'OK', message: 'success', data: principal }), { status: 200 })))
  })

  it('denies a STORE employee a direct HQ route even with a URL pasted manually', async () => {
    render(<MemoryRouter>
      <AuthProvider><RequireTenantWorkspace workspace="HQ"><p>总部机密</p></RequireTenantWorkspace></AuthProvider>
    </MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '无权访问' })).toBeInTheDocument()
    expect(screen.queryByText('总部机密')).not.toBeInTheDocument()
  })

  it('denies a STORE route when its required permission is absent', async () => {
    render(<MemoryRouter>
      <AuthProvider><RequireTenantWorkspace workspace="STORE" permission="store.order.manage"><p>订单操作</p></RequireTenantWorkspace></AuthProvider>
    </MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '无权访问' })).toBeInTheDocument()
    expect(screen.queryByText('订单操作')).not.toBeInTheDocument()
  })
})
