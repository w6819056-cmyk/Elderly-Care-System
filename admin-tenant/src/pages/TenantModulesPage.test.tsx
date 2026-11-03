import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthProvider'
import { saveTenantSession } from '../api/session'
import TenantModulesPage from './TenantModulesPage'

const principal = {
  staffId: 4,
  staffPublicId: 'st_hq_4',
  username: 'hq-operator',
  staffType: 'HQ' as const,
  homeStoreId: null,
  accessContext: { permissionCodes: ['core.module.view', 'core.module.manage'] }
}

const moduleEntry = {
  moduleCode: 'membership',
  licenseType: 'FORMAL',
  licenseStatus: 'ACTIVE',
  effectiveState: 'ACTIVE',
  startsAt: '2026-01-01T00:00:00Z',
  expiresAt: null,
  perpetual: true,
  licenseVersion: 2,
  storeQuota: 3,
  assignedStores: 0,
  enabled: false
}

describe('HQ module authorization page', () => {
  beforeEach(() => {
    cleanup()
    localStorage.clear()
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', principal)
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      let data: unknown = null
      if (url.endsWith('/auth/me')) data = principal
      else if (url.endsWith('/hq/modules')) data = [moduleEntry]
      else if (url.endsWith('/license/status')) data = { licenseId: 'lic_1', version: 2, instanceStatus: 'ACTIVE', modules: [] }
      else if (url.endsWith('/assignments')) data = []
      return new Response(JSON.stringify({ code: 'OK', message: 'success', data }), { status: 200 })
    }))
  })

  it('shows server license data and sends an explicit activation command', async () => {
    const user = userEvent.setup()
    render(<MemoryRouter><AuthProvider><TenantModulesPage /></AuthProvider></MemoryRouter>)

    expect(await screen.findByText('会员运营')).toBeInTheDocument()
    expect(screen.getByText('0 / 3')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '启用模块' }))
    expect(fetch).toHaveBeenCalledWith('/api/tenant/v1/hq/modules/membership/activation', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ enabled: true })
    }))
  })

  it('does not show activation controls without the management permission', async () => {
    const readOnlyPrincipal = { ...principal, accessContext: { permissionCodes: ['core.module.view'] } }
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', readOnlyPrincipal)
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input)
      const data = url.endsWith('/auth/me') ? readOnlyPrincipal
        : url.endsWith('/hq/modules') ? [moduleEntry]
          : url.endsWith('/license/status') ? { licenseId: 'lic_1', version: 2, instanceStatus: 'ACTIVE', modules: [] }
            : []
      return new Response(JSON.stringify({ code: 'OK', message: 'success', data }), { status: 200 })
    })
    render(<MemoryRouter><AuthProvider><TenantModulesPage /></AuthProvider></MemoryRouter>)

    expect(await screen.findByText('会员运营')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '启用模块' })).not.toBeInTheDocument()
  })
})
