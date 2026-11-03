import { beforeEach, describe, expect, it } from 'vitest'
import { clearTenantSession, getTenantPrincipal, getTenantToken, saveTenantSession } from './session'

describe('tenant session storage', () => {
  beforeEach(() => localStorage.clear())

  it('stores the tenant employee session in its own namespace', () => {
    const principal = { staffId: 4, staffPublicId: 'st_4', username: 'hq-admin', staffType: 'HQ' as const,
      homeStoreId: null, accessContext: { permissionCodes: ['core.module.view'] } }
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', principal)
    expect(getTenantToken()).toBe('tenant-token')
    expect(getTenantPrincipal()).toEqual(principal)
    expect(localStorage.getItem('platform.access_token')).toBeNull()
    expect(localStorage.getItem('ec_token')).toBeNull()
  })

  it('clears only tenant session keys', () => {
    localStorage.setItem('platform.access_token', 'platform-token')
    localStorage.setItem('platform.principal', '{"username":"operator"}')
    localStorage.setItem('ec_token', 'legacy-token')
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', {
      staffId: 4, staffPublicId: 'st_4', username: 'hq-admin', staffType: 'HQ', homeStoreId: null,
      accessContext: { permissionCodes: [] }
    })

    clearTenantSession()

    expect(getTenantToken()).toBeNull()
    expect(localStorage.getItem('platform.access_token')).toBe('platform-token')
    expect(localStorage.getItem('platform.principal')).toBe('{"username":"operator"}')
    expect(localStorage.getItem('ec_token')).toBe('legacy-token')
  })
})
