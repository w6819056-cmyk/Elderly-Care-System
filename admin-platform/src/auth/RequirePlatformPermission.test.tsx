import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from './AuthProvider'
import { RequirePlatformPermission } from './RequirePlatformPermission'
import { savePlatformSession } from '../api/session'

const principal = { userId: 9, publicId: 'pa_9', username: 'operator', roleCodes: ['VIEWER'], permissionCodes: [] }

describe('platform permission route guard', () => {
  beforeEach(() => {
    localStorage.clear()
    savePlatformSession('platform-token', '2030-01-01T00:00:00Z', principal)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'OK', message: 'success', data: principal }), { status: 200 })))
  })

  it('denies a direct route when the current server principal lacks its permission', async () => {
    render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AuthProvider><RequirePlatformPermission permission="platform.enterprise.view"><p>企业功能</p></RequirePlatformPermission></AuthProvider>
    </MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '无权访问' })).toBeInTheDocument()
    expect(screen.queryByText('企业功能')).not.toBeInTheDocument()
  })
})
