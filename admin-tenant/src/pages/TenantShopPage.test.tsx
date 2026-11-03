import '@testing-library/jest-dom/vitest'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {MemoryRouter} from 'react-router-dom'
import TenantShopPage from './TenantShopPage'
const mocks=vi.hoisted(()=>({request:vi.fn(),permissions:['core.module.view','core.module.purchase'] as string[],configured:true}))
vi.mock('../api/client',()=>({request:mocks.request}))
vi.mock('../auth/AuthProvider',()=>({useTenantAuth:()=>({principal:{staffType:'HQ',accessContext:{permissionCodes:mocks.permissions}}})}))
afterEach(cleanup)
beforeEach(()=>{vi.clearAllMocks();mocks.permissions=['core.module.view','core.module.purchase'];mocks.configured=true;mocks.request.mockImplementation((path:string,init?:RequestInit)=>Promise.resolve(path.endsWith('/configuration')?{instanceConfigured:mocks.configured}:path.endsWith('/catalog')?{cards:[{cardId:1,productId:1,planId:null,name:'会员年卡',description:'会员权益管理',imageUrl:'/images/membership-cover.svg',amount:199,billingMode:'PERIOD',durationDays:365,storeQuota:3}],mockPaymentEnabled:true}:path.endsWith('/quote')?{originalAmount:199,deductionAmount:0,payableAmount:199,currency:'CNY',items:[]}:path.endsWith('/orders')?(init?.method==='POST'?{publicId:'order-1',orderNo:'SO-DEMO-1',status:'PENDING_PAYMENT',payableAmount:199,currency:'CNY',items:[]}:{items:[]}):{}))})
const show=()=>render(<MemoryRouter><TenantShopPage/></MemoryRouter>)
it('shows activation guidance without requesting checkout when disconnected',async()=>{mocks.configured=false;show();expect(await screen.findByText('先连接企业实例')).toBeInTheDocument();expect(mocks.request).not.toHaveBeenCalledWith('/api/tenant/v1/hq/shop/catalog')})
it('prevents a read-only employee from purchasing',async()=>{mocks.permissions=['core.module.view'];show();expect(await screen.findByRole('button',{name:'需总部购买权限'})).toBeDisabled()})
it('obtains the enterprise quote and submits a stable idempotent order request',async()=>{show();fireEvent.click(await screen.findByRole('button',{name:'选购 / 续费'}));fireEvent.click(screen.getByRole('button',{name:'获取企业报价'}));fireEvent.click(await screen.findByRole('button',{name:'确认创建订单'}));await waitFor(()=>expect(mocks.request).toHaveBeenCalledWith('/api/tenant/v1/hq/shop/orders',expect.objectContaining({method:'POST'})));const post=mocks.request.mock.calls.filter(([path,init])=>path==='/api/tenant/v1/hq/shop/orders'&&init?.method==='POST')[0];const body=JSON.parse(post[1].body);expect(body.productId).toBe(1);expect(body.enterpriseId).toBeUndefined();expect(body.idempotencyKey).toMatch(/[a-f0-9-]{36}/)})
