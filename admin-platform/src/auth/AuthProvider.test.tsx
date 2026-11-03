import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, usePlatformAuth } from './AuthProvider'
import { savePlatformSession } from '../api/session'

const cachedPrincipal = { userId: 9, publicId: 'pa_9', username: 'cached', roleCodes: ['OLD'], permissionCodes: ['platform.account.manage'] }
const currentPrincipal = { ...cachedPrincipal, username: 'fresh', permissionCodes: ['platform.enterprise.view'] }

function Probe() {
  const auth = usePlatformAuth()
  return <output>{auth.status}:{auth.principal?.username ?? '-'}</output>
}

describe('platform auth bootstrap', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn())
  })

  it('replaces cached identity and permissions with the platform /me response', async () => {
    savePlatformSession('platform-token', '2030-01-01T00:00:00Z', cachedPrincipal)
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'OK', message: 'success', data: currentPrincipal }), { status: 200 }))

    render(<AuthProvider><Probe /></AuthProvider>)

    expect(await screen.findByText('authenticated:fresh')).toBeInTheDocument()
    await waitFor(() => expect(JSON.parse(localStorage.getItem('platform.principal') ?? '{}').permissionCodes)
      .toEqual(['platform.enterprise.view']))
  })

  it('keeps the session and shows unavailable state when /me cannot be reached', async () => {
    savePlatformSession('platform-token', '2030-01-01T00:00:00Z', cachedPrincipal)
    vi.mocked(fetch).mockRejectedValue(new TypeError('network offline'))

    render(<AuthProvider><Probe /></AuthProvider>)

    expect(await screen.findByText('unavailable:-')).toBeInTheDocument()
    expect(localStorage.getItem('platform.access_token')).toBe('platform-token')
  })
})
