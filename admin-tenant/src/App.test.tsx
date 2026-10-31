import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { saveTenantSession } from './api/session'

const hq = { staffId: 4, staffPublicId: 'st_4', username: 'hq-user', staffType: 'HQ' as const,
  homeStoreId: null, accessContext: { permissionCodes: ['core.module.view'] } }
const store = { staffId: 5, staffPublicId: 'st_5', username: 'store-user', staffType: 'STORE' as const,
  homeStoreId: 11, accessContext: { permissionCodes: [] } }

function renderAt(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)
}

function mockApi(principal: typeof hq | typeof store) {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const data = url.endsWith('/auth/me') ? principal : []
    return Promise.resolve(new Response(JSON.stringify({ code: 'OK', message: 'success', data }), { status: 200 }))
  }))
}

describe('tenant application routes', () => {
  beforeEach(() => {
    cleanup()
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn())
  })

  it('renders the tenant login route', () => {
    renderAt('/login')
    expect(screen.getByRole('heading', { name: '企业员工登录' })).toBeInTheDocument()
    expect(screen.getByLabelText('员工账号')).toBeInTheDocument()
    expect(screen.getByLabelText('密码')).toBeInTheDocument()
  })

  it('redirects anonymous workspace visits to login', async () => {
    renderAt('/hq')
    expect(await screen.findByRole('heading', { name: '企业员工登录' })).toBeInTheDocument()
  })

  it('routes an authenticated HQ employee to the HQ workspace', async () => {
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', hq)
    mockApi(hq)
    renderAt('/')
    expect(await screen.findByRole('heading', { name: '总部工作台' })).toBeInTheDocument()
  })

  it('routes an authenticated STORE employee to the store workspace', async () => {
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', store)
    mockApi(store)
    renderAt('/')
    expect(await screen.findByRole('heading', { name: '门店工作台' })).toBeInTheDocument()
  })

  it('denies a store employee direct access to an HQ route', async () => {
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', store)
    mockApi(store)
    renderAt('/hq/modules')
    expect(await screen.findByRole('heading', { name: '无权访问' })).toBeInTheDocument()
  })

  it('does not expose an unlicensed module route', async () => {
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', store)
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const data = String(input).endsWith('/auth/me') ? store : []
      return Promise.resolve(new Response(JSON.stringify({ code: 'OK', message: 'success', data }), { status: 200 }))
    }))
    renderAt('/store/membership')
    expect(await screen.findByRole('heading', { name: '无权访问' })).toBeInTheDocument()
    expect(screen.queryByText('会员运营')).not.toBeInTheDocument()
  })

  it('keeps a module unavailable when the server menu request fails', async () => {
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', store)
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      if (String(input).endsWith('/auth/me')) {
        return Promise.resolve(new Response(JSON.stringify({ code: 'OK', message: 'success', data: store }), { status: 200 }))
      }
      return Promise.reject(new TypeError('network offline'))
    }))
    renderAt('/store/membership')
    expect(await screen.findByRole('heading', { name: '模块暂不可用' })).toBeInTheDocument()
    expect(screen.queryByText('会员运营')).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '重试' })).toHaveLength(1)
  })

  it('shows a menu retry action on the store workspace when loading fails', async () => {
    saveTenantSession('tenant-token', '2030-01-01T00:00:00Z', store)
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      if (String(input).endsWith('/auth/me')) {
        return Promise.resolve(new Response(JSON.stringify({ code: 'OK', message: 'success', data: store }), { status: 200 }))
      }
      return Promise.reject(new TypeError('network offline'))
    }))
    renderAt('/store')
    expect(await screen.findByRole('heading', { name: '门店工作台' })).toBeInTheDocument()
    expect(await screen.findByText('模块菜单暂不可用，已隐藏模块入口。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument()
  })

  it('renders a not-found state for unknown routes', () => {
    renderAt('/unknown')
    expect(screen.getByRole('heading', { name: '页面不存在' })).toBeInTheDocument()
  })
})
