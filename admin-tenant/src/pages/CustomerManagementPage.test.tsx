import '@testing-library/jest-dom/vitest'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react'
import CustomerManagementPage from './CustomerManagementPage'
const mocks=vi.hoisted(()=>({request:vi.fn()}))
vi.mock('../api/client',()=>({request:mocks.request}))
vi.mock('../auth/AuthProvider',()=>({useTenantAuth:()=>({principal:{staffType:'HQ'}})}))
const customer={publicId:'c1',displayName:'张阿姨',username:'zhang',createdAt:'2026-11-01T10:00:00Z',bookingCount:2,stores:[{publicId:'s1',storeNo:'001',name:'康颐门店'}]}
afterEach(cleanup)
beforeEach(()=>{vi.resetAllMocks();mocks.request.mockImplementation((path:string)=>Promise.resolve(path.endsWith('/c1')?{customer,bookings:[{publicId:'b1',storeName:'康颐门店',visitorName:'张阿姨',phone:'13800000000',preferredDate:'2026-11-03',status:'PENDING',visitType:'VISIT',remark:null}]}:{items:[customer],total:21,page:1,size:20}))})
it('opens customer details with visible appointments',async()=>{render(<CustomerManagementPage/>);fireEvent.click(await screen.findByRole('button',{name:'查看详情'}));expect(await screen.findByText('13800000000')).toBeInTheDocument();expect(screen.getByRole('dialog')).toBeInTheDocument()})
it('searches and paginates on the server',async()=>{render(<CustomerManagementPage/>);fireEvent.click(await screen.findByRole('button',{name:'下一页'}));await waitFor(()=>expect(mocks.request).toHaveBeenCalledWith(expect.stringContaining('page=2')));fireEvent.change(screen.getByRole('searchbox'),{target:{value:'张'}});fireEvent.submit(screen.getByRole('search'));await waitFor(()=>expect(mocks.request).toHaveBeenCalledWith(expect.stringContaining('q=%E5%BC%A0&page=1')))})
it('shows a plain empty state',async()=>{mocks.request.mockResolvedValue({items:[],total:0,page:1,size:20});render(<CustomerManagementPage/>);expect(await screen.findByText('当前没有可见客户')).toBeInTheDocument();expect(screen.queryByRole('button',{name:'查看详情'})).not.toBeInTheDocument()})
it('retries failed requests',async()=>{mocks.request.mockRejectedValueOnce(new Error('读取失败')).mockResolvedValue({items:[],total:0,page:1,size:20});render(<CustomerManagementPage/>);expect(await screen.findByRole('alert')).toHaveTextContent('读取失败');fireEvent.click(screen.getByRole('button',{name:'刷新'}));expect(await screen.findByText('当前没有可见客户')).toBeInTheDocument()})
