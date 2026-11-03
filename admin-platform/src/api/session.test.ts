import { beforeEach, describe, expect, it } from 'vitest'
import { clearPlatformSession, getPlatformPrincipal, getPlatformToken, savePlatformSession } from './session'

describe('platform session storage', () => {
  beforeEach(() => localStorage.clear())

  it('stores the platform session in its own namespace', () => {
    const principal = { userId: 9, publicId: 'pa_9', username: 'operator', roleCodes: ['OWNER'], permissionCodes: ['platform.enterprise.view'] }
    savePlatformSession('platform-token', '2030-01-01T00:00:00Z', principal)
    expect(getPlatformToken()).toBe('platform-token')
    expect(getPlatformPrincipal()).toEqual(principal)
    expect(localStorage.getItem('tenant.access_token')).toBeNull()
    expect(localStorage.getItem('ec_token')).toBeNull()
  })

  it('clears only platform session keys', () => {
    localStorage.setItem('tenant.access_token', 'tenant-token')
    localStorage.setItem('tenant.principal', '{"staffType":"HQ"}')
    localStorage.setItem('ec_token', 'legacy-token')
    savePlatformSession('platform-token', '2030-01-01T00:00:00Z', {
      userId: 9, publicId: 'pa_9', username: 'operator', roleCodes: [], permissionCodes: []
    })

    clearPlatformSession()

    expect(getPlatformToken()).toBeNull()
    expect(localStorage.getItem('tenant.access_token')).toBe('tenant-token')
    expect(localStorage.getItem('tenant.principal')).toBe('{"staffType":"HQ"}')
    expect(localStorage.getItem('ec_token')).toBe('legacy-token')
  })
})
