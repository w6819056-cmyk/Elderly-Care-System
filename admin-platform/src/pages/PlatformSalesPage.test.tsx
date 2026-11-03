import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import PlatformSalesPage from './PlatformSalesPage'

const mocks = vi.hoisted(() => ({ request: vi.fn(), permissions: [] as string[] }))

vi.mock('../api/client', () => ({ request: mocks.request }))
vi.mock('../auth/AuthProvider', () => ({
  usePlatformAuth: () => ({ principal: { permissionCodes: mocks.permissions } })
}))

afterEach(cleanup)
beforeEach(() => {
  vi.clearAllMocks()
  mocks.permissions = []
  mocks.request.mockResolvedValue([])
})

describe('platform sales authorization workspace', () => {
  it('blocks users without sales permission', () => {
    render(<PlatformSalesPage />)
    expect(screen.getByText('无权查看销售授权或商品配置')).toBeInTheDocument()
    expect(mocks.request).not.toHaveBeenCalled()
  })

  it('loads the sales order list in its own workspace', async () => {
    mocks.permissions = ['platform.enterprise.view', 'platform.plan.view', 'platform.order.view']
    mocks.request.mockResolvedValueOnce({items:[]}).mockResolvedValueOnce({items:[],total:0,page:1,pageSize:100})
    render(<PlatformSalesPage />)
    fireEvent.click(await screen.findByRole('tab',{name:'销售订单'}))
    expect(await screen.findByText('当前没有符合条件的订单')).toBeInTheDocument()
    expect(mocks.request).toHaveBeenCalledWith('/api/platform/v1/orders?page=1&pageSize=100')
  })

  it('exposes product configuration to module and plan managers', async () => {
    mocks.permissions = ['platform.enterprise.view', 'platform.plan.view', 'platform.order.view', 'platform.module.view', 'platform.module.manage', 'platform.plan.manage']
    mocks.request
      .mockResolvedValueOnce({ items: [] })
      .mockResolvedValueOnce({ items: [{ moduleCode: 'membership', name: '会员模块', saleStatus: 'ON_SALE', prices: [{ billingMode: 'PERIOD', durationDays: 365, amount: '199.00', currency: 'CNY', storeQuota: 3 }] }] })
      .mockResolvedValueOnce([])
    render(<PlatformSalesPage />)
    fireEvent.click(screen.getByRole('tab', { name: '商品配置' }))
    expect(await screen.findByText('单模块定价')).toBeInTheDocument()
    expect(await screen.findByText('套餐组合')).toBeInTheDocument()
    expect(await screen.findByText('会员模块')).toBeInTheDocument()
  })

  it('saves module price schedules through the module catalog API', async () => {
    mocks.permissions = ['platform.enterprise.view', 'platform.plan.view', 'platform.order.view', 'platform.module.view', 'platform.module.manage', 'platform.plan.manage']
    mocks.request
      .mockResolvedValueOnce({ items: [] })
      .mockResolvedValueOnce({ items: [{ moduleCode: 'membership', name: '会员模块', saleStatus: 'ON_SALE', prices: [{ billingMode: 'PERIOD', durationDays: 365, amount: '199.00', currency: 'CNY', storeQuota: 3 }] }] })
      .mockResolvedValueOnce([])
    render(<PlatformSalesPage />)
    fireEvent.click(screen.getByRole('tab', { name: '商品配置' }))
    const save = await screen.findByRole('button', { name: '保存价格方案' })
    fireEvent.click(save)
    await waitFor(() => expect(mocks.request).toHaveBeenCalledWith('/api/platform/v1/modules/membership/prices', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ prices: [{ billingMode: 'PERIOD', durationDays: 365, amount: '199.00', currency: 'CNY', storeQuota: 3, trialDays: null }] })
    })))
  })

  it('lets platform staff publish a priced draft module for standalone and plan sales', async () => {
    mocks.permissions = ['platform.enterprise.view', 'platform.plan.view', 'platform.order.view', 'platform.module.view', 'platform.module.manage', 'platform.plan.manage']
    mocks.request
      .mockResolvedValueOnce({ items: [] })
      .mockResolvedValueOnce({ items: [{ moduleCode: 'membership', name: '会员模块', saleStatus: 'DRAFT', prices: [{ billingMode: 'PERIOD', durationDays: 365, amount: '199.00', currency: 'CNY', storeQuota: 3 }] }] })
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce({ moduleCode: 'membership', name: '会员模块', saleStatus: 'ON_SALE', prices: [{ billingMode: 'PERIOD', durationDays: 365, amount: '199.00', currency: 'CNY', storeQuota: 3 }] })
    render(<PlatformSalesPage />)
    fireEvent.click(screen.getByRole('tab', { name: '商品配置' }))
    fireEvent.click(await screen.findByRole('button', { name: '上架销售' }))
    await waitFor(() => expect(mocks.request).toHaveBeenCalledWith('/api/platform/v1/modules/membership/status', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ status: 'ON_SALE' }) })))
    expect(await screen.findByRole('button', { name: '停售' })).toBeInTheDocument()
  })

  it('creates a package from configured module prices', async () => {
    mocks.permissions = ['platform.enterprise.view', 'platform.plan.view', 'platform.order.view', 'platform.module.view', 'platform.plan.manage']
    mocks.request
      .mockResolvedValueOnce({ items: [] })
      .mockResolvedValueOnce({ items: [{ moduleCode: 'membership', name: '会员模块', saleStatus: 'ON_SALE', prices: [{ billingMode: 'PERIOD', durationDays: 365, amount: '199.00', currency: 'CNY', storeQuota: 3 }] }] })
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce({ planCode: 'member_bundle', name: '会员套餐', amount: 199, minimumAmount: 199, currency: 'CNY', saleStatus: 'ON_SALE', items: [] })
    render(<PlatformSalesPage />)
    fireEvent.click(screen.getByRole('tab', { name: '商品配置' }))
    fireEvent.change(await screen.findByLabelText('套餐名称'), { target: { value: '会员套餐' } })
    fireEvent.change(screen.getByLabelText('套餐编码'), { target: { value: 'member_bundle' } })
    fireEvent.change(screen.getByLabelText('套餐价格'), { target: { value: '199.00' } })
    fireEvent.change(screen.getByLabelText('套餐说明'), { target: { value: '会员模块组合' } })
    fireEvent.change(screen.getByLabelText('套餐模块'), { target: { value: 'membership|PERIOD|365|3' } })
    fireEvent.click(screen.getByRole('button', { name: '添加到套餐' }))
    fireEvent.click(screen.getByRole('button', { name: '创建套餐' }))
    await waitFor(() => expect(mocks.request).toHaveBeenCalledWith('/api/platform/v1/plans', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ code: 'member_bundle', name: '会员套餐', description: '会员模块组合', amount: '199.00', minimumAmount: '199.00', items: [{ moduleCode: 'membership', billingMode: 'PERIOD', durationDays: 365, storeQuota: 3 }] })
    })))
  })

  it('takes a package off sale from the product catalog', async () => {
    mocks.permissions = ['platform.plan.view', 'platform.plan.manage']
    mocks.request
      .mockResolvedValueOnce([{ planCode: 'care_suite', name: '康养套装', description: '基础服务', amount: 1999, minimumAmount: 1800, currency: 'CNY', saleStatus: 'ON_SALE', items: [] }])
      .mockResolvedValueOnce({ planCode: 'care_suite', name: '康养套装', amount: 1999, minimumAmount: 1800, currency: 'CNY', saleStatus: 'OFF_SALE', items: [] })
    render(<PlatformSalesPage />)
    fireEvent.click(await screen.findByRole('button', { name: '停售' }))
    await waitFor(() => expect(mocks.request).toHaveBeenCalledWith('/api/platform/v1/plans/care_suite/status', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ status: 'OFF_SALE' }) })))
  })

  it('quotes a selected standalone module using its configured billing period', async () => {
    mocks.permissions = ['platform.enterprise.view', 'platform.plan.view', 'platform.order.view', 'platform.module.view']
    mocks.request
      .mockResolvedValueOnce({ items: [{ publicId: 'ent_demo', name: '康颐集团', status: 'ACTIVE' }] })
      .mockResolvedValueOnce([{ planCode: 'care_suite', name: '康养基础套装', description: '基础能力', amount: 1999, minimumAmount: 1999, currency: 'CNY', saleStatus: 'ON_SALE', items: [] }])
      .mockResolvedValueOnce({ items: [{ moduleCode: 'membership', name: '会员模块', saleStatus: 'ON_SALE', prices: [{ billingMode: 'PERIOD', durationDays: 365, amount: '199.00', currency: 'CNY', storeQuota: 3 }] }] })
      .mockResolvedValueOnce({ enterpriseId: 'ent_demo', planCode: null, currency: 'CNY', originalAmount: 199, deductionAmount: 0, payableAmount: 199, items: [] })
    render(<PlatformSalesPage />)
    fireEvent.change(await screen.findByLabelText('企业'), { target: { value: 'ent_demo' } })
    fireEvent.click(screen.getByRole('radio', { name: '单模块' }))
    fireEvent.change(await screen.findByLabelText('购买模块'), { target: { value: 'membership|PERIOD|365|3' } })
    const quoteButton = screen.getByRole('button', { name: '获取报价' })
    await waitFor(() => expect(quoteButton).not.toBeDisabled())
    fireEvent.click(quoteButton)
    expect(await screen.findByText('¥199.00')).toBeInTheDocument()
    expect(mocks.request).toHaveBeenLastCalledWith('/api/platform/v1/orders/quote', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ enterpriseId: 'ent_demo', planCode: null, items: [{ moduleCode: 'membership', billingMode: 'PERIOD', durationDays: 365, storeQuota: 3 }], period: { billingMode: 'PERIOD', durationDays: 365, storeQuota: 3 } })
    }))
  })

  it('refreshes the module catalog when switching into standalone purchase', async () => {
    mocks.permissions = ['platform.enterprise.view', 'platform.plan.view', 'platform.order.view', 'platform.module.view', 'platform.plan.manage']
    mocks.request
      .mockResolvedValueOnce({ items: [{ publicId: 'ent_demo', name: '康颐集团', status: 'ACTIVE' }] })
      .mockResolvedValueOnce({ items: [{ moduleCode: 'membership', name: '会员模块', saleStatus: 'DRAFT', prices: [{ billingMode: 'PERIOD', durationDays: 365, amount: '199.00', currency: 'CNY', storeQuota: 3 }] }] })
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce({ items: [{ moduleCode: 'membership', name: '会员模块', saleStatus: 'ON_SALE', prices: [{ billingMode: 'PERIOD', durationDays: 365, amount: '199.00', currency: 'CNY', storeQuota: 3 }] }] })
    render(<PlatformSalesPage />)
    fireEvent.click(screen.getByRole('tab', { name: '商品配置' }))
    expect(await screen.findByText('会员模块')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: '企业授权' }))
    fireEvent.click(screen.getByRole('radio', { name: '单模块' }))
    expect(await screen.findByRole('option', { name: /会员模块/ })).toBeInTheDocument()
    expect(mocks.request.mock.calls.filter(([path]) => path === '/api/platform/v1/modules?page=1&pageSize=100')).toHaveLength(2)
  })

  it('quotes a selected plan for an enterprise', async () => {
    mocks.permissions = ['platform.enterprise.view', 'platform.plan.view', 'platform.order.view']
    mocks.request
      .mockResolvedValueOnce({ items: [{ publicId: 'ent_demo', name: '康颐集团', status: 'ACTIVE' }] })
      .mockResolvedValueOnce([{ planCode: 'care_suite', name: '康养基础套装', description: '基础能力', amount: 1999, minimumAmount: 1999, currency: 'CNY', saleStatus: 'ON_SALE', items: [] }])
      .mockResolvedValueOnce({ enterpriseId: 'ent_demo', planCode: 'care_suite', currency: 'CNY', originalAmount: 1999, deductionAmount: 0, payableAmount: 1999, items: [] })
    render(<PlatformSalesPage />)
    expect(await screen.findByRole('option', { name: /康颐集团/ })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('企业'), { target: { value: 'ent_demo' } })
    expect(await screen.findByRole('option', { name: /康养基础套装/ })).toBeInTheDocument()
    const quoteButton = screen.getByRole('button', { name: '获取报价' })
    await waitFor(() => expect(quoteButton).not.toBeDisabled())
    fireEvent.click(quoteButton)
    expect(await screen.findByText('¥1,999.00')).toBeInTheDocument()
    expect(mocks.request).toHaveBeenLastCalledWith('/api/platform/v1/orders/quote', expect.objectContaining({ method: 'POST' }))
  })

  it('creates and simulates payment for a quoted order', async () => {
    mocks.permissions = ['platform.enterprise.view', 'platform.plan.view', 'platform.order.view', 'platform.order.manage']
    mocks.request
      .mockResolvedValueOnce({ items: [{ publicId: 'ent_demo', name: '康颐集团', status: 'ACTIVE' }] })
      .mockResolvedValueOnce([{ planCode: 'care_suite', name: '康养基础套装', description: '基础能力', amount: 1999, minimumAmount: 1999, currency: 'CNY', saleStatus: 'ON_SALE', items: [] }])
      .mockResolvedValueOnce({ enterpriseId: 'ent_demo', planCode: 'care_suite', currency: 'CNY', originalAmount: 1999, deductionAmount: 0, payableAmount: 1999, items: [] })
      .mockResolvedValueOnce({ publicId: 'order_1', orderNo: 'SO20261102001', status: 'PENDING_PAYMENT', payableAmount: 1999, items: [], payments: [] })
      .mockResolvedValueOnce({ publicId: 'order_1', orderNo: 'SO20261102001', status: 'FULFILLED', payableAmount: 1999, items: [], payments: [] })
    render(<PlatformSalesPage />)
    fireEvent.change(await screen.findByLabelText('企业'), { target: { value: 'ent_demo' } })
    expect(await screen.findByRole('option', { name: /康养基础套装/ })).toBeInTheDocument()
    const quoteButton = screen.getByRole('button', { name: '获取报价' })
    await waitFor(() => expect(quoteButton).not.toBeDisabled())
    fireEvent.click(quoteButton)
    fireEvent.click(await screen.findByRole('button', { name: '创建订单' }))
    expect(await screen.findByText('SO20261102001')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '模拟支付并履约' }))
    expect(await screen.findByText('已履约')).toBeInTheDocument()
  })
})
