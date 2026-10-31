import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('platform application routes', () => {
  it('renders the platform login route', () => {
    render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={['/login']}><App /></MemoryRouter>)
    expect(screen.getByRole('heading', { name: '平台总管理端登录' })).toBeInTheDocument()
  })

  it('renders a not-found state for unknown routes', () => {
    render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={['/unknown']}><App /></MemoryRouter>)
    expect(screen.getByRole('heading', { name: '页面不存在' })).toBeInTheDocument()
  })
})
