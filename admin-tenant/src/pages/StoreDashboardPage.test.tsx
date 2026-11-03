import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {cleanup,render,screen} from '@testing-library/react'
import {MemoryRouter} from 'react-router-dom'
import StoreDashboardPage from './StoreDashboardPage'
const mocks=vi.hoisted(()=>({list:vi.fn(),edit:vi.fn(),request:vi.fn(),permissions:[] as string[]}))
vi.mock('../auth/AuthProvider',()=>({useTenantAuth:()=>({principal:{username:'店员',staffType:'STORE',accessContext:{permissionCodes:mocks.permissions}}})}))
vi.mock('../auth/TenantMenuProvider',()=>({useTenantMenus:()=>({menus:[],status:'ready'})}))
vi.mock('../api/storePortal',()=>({visitApi:{list:mocks.list}}))
vi.mock('../api/siteContent',()=>({siteApi:{edit:mocks.edit}}))
vi.mock('../api/client',()=>({request:mocks.request}))
afterEach(cleanup)
beforeEach(()=>{vi.clearAllMocks();mocks.permissions=[]})
it('does not fetch protected data without permission',async()=>{
 render(<MemoryRouter><StoreDashboardPage/></MemoryRouter>)
 await screen.findByText('当前账号没有预约查看权限，请联系企业管理员。')
 expect(mocks.list).not.toHaveBeenCalled();expect(mocks.request).not.toHaveBeenCalled()
 expect(screen.queryByRole('link',{name:/维护门店首页/})).not.toBeInTheDocument()
})
it('reports a failed request instead of misleading empty counts',async()=>{
 mocks.permissions=['core.visit.view'];mocks.list.mockRejectedValue(new Error('连接中断'))
 render(<MemoryRouter><StoreDashboardPage/></MemoryRouter>)
 expect(await screen.findByRole('alert')).toHaveTextContent('连接中断')
 expect(screen.queryByText('当前没有待处理的预约')).not.toBeInTheDocument()
})
it('shows only actionable bookings and links to processing',async()=>{
 mocks.permissions=['core.visit.view'];mocks.list.mockResolvedValue([{publicId:'1',visitorName:'预约访客',preferredDate:'2026-11-02',visitType:'VISIT',status:'PENDING'},{publicId:'2',visitorName:'已完成访客',status:'COMPLETED'}])
 render(<MemoryRouter><StoreDashboardPage/></MemoryRouter>)
 expect(await screen.findByText('预约访客')).toBeInTheDocument()
 expect(screen.queryByText('已完成访客')).not.toBeInTheDocument()
 expect(screen.getByRole('link',{name:'查看并处理全部预约 →'})).toHaveAttribute('href','/store/visits')
})
