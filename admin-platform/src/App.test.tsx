import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import App from './App'

const future = { v7_startTransition: true, v7_relativeSplatPath: true }

describe('platform application routes', () => {
  it('renders the platform login route', async () => {
    render(<MemoryRouter future={future} initialEntries={['/login']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '平台总管理端登录' })).toBeInTheDocument()
  })

  it('renders a not-found state for unknown routes', () => {
    render(<MemoryRouter future={future} initialEntries={['/unknown']}><App /></MemoryRouter>)
    expect(screen.getByRole('heading', { name: '页面不存在' })).toBeInTheDocument()
  })
})
