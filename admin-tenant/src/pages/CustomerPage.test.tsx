import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {cleanup,render,screen} from '@testing-library/react'
import {MemoryRouter,Route,Routes} from 'react-router-dom'
import CustomerPage from './CustomerPage'
const mocks=vi.hoisted(()=>({request:vi.fn(),token:vi.fn()}))
vi.mock('../api/customer',()=>({customerRequest:mocks.request,customerToken:mocks.token,clearCustomer:vi.fn(),customerSignIn:vi.fn()}))
afterEach(cleanup)
beforeEach(()=>{vi.clearAllMocks();mocks.token.mockReturnValue('consumer-token')})
it('redirects anonymous visitors to consumer login',async()=>{
 mocks.token.mockReturnValue(null)
 render(<MemoryRouter initialEntries={['/customer']}><Routes><Route path="/customer" element={<CustomerPage/>}/><Route path="/customer/login" element={<p>消费者登录入口</p>}/></Routes></MemoryRouter>)
 expect(await screen.findByText('消费者登录入口')).toBeInTheDocument();expect(mocks.request).not.toHaveBeenCalled()
})
it('loads persisted bookings again after remount',async()=>{
 mocks.request.mockImplementation((path:string)=>Promise.resolve(path==='/me'?{username:'customer',displayName:'用户'}:[{bookingNo:'booking-1',storeNo:'001',storeName:'康养门店',visitorName:'访客',phone:'13800001111',visitType:'VISIT',preferredDate:'2026-11-02',status:'CONFIRMED'}]))
 const first=render(<MemoryRouter><CustomerPage/></MemoryRouter>);expect(await screen.findByText('康养门店')).toBeInTheDocument();first.unmount()
 render(<MemoryRouter><CustomerPage/></MemoryRouter>);expect(await screen.findByText('康养门店')).toBeInTheDocument();expect(mocks.request.mock.calls.filter(([path])=>path==='/bookings')).toHaveLength(2)
})
it('does not present a network failure as an empty booking list',async()=>{
 mocks.request.mockRejectedValue(new Error('网络暂不可用'))
 render(<MemoryRouter><CustomerPage/></MemoryRouter>)
 expect(await screen.findByRole('alert')).toHaveTextContent('网络暂不可用');expect(screen.queryByText(/暂无预约/)).not.toBeInTheDocument()
})
it('renders only host supplied feature cards and own public identity',async()=>{
 mocks.request.mockImplementation((path:string)=>Promise.resolve(path==='/me'?{publicId:'own-uuid',displayName:'本人'}:path==='/features'?[{moduleCode:'membership',data:{member:false,displayName:'会员权益'}}]:[]))
 render(<MemoryRouter><CustomerPage/></MemoryRouter>);expect(await screen.findByText('own-uuid')).toBeInTheDocument();expect(await screen.findByText('未开通')).toBeInTheDocument();expect(mocks.request).toHaveBeenCalledWith('/features')
})
