import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('tenant application routes', () => {
  it('renders the tenant login route', () => {
    render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={['/login']}><App /></MemoryRouter>)
    expect(screen.getByRole('heading', { name: '企业员工登录' })).toBeInTheDocument()
  })

  it('renders a not-found state for unknown routes', () => {
    render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={['/unknown']}><App /></MemoryRouter>)
    expect(screen.getByRole('heading', { name: '页面不存在' })).toBeInTheDocument()
  })
})
