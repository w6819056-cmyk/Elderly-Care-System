import {afterEach, expect, it, vi} from 'vitest'
import {cleanup, render, screen, fireEvent} from '@testing-library/react'
import {MemoryRouter} from 'react-router-dom'
import RuntimeModulePage, {createModuleRequest} from './RuntimeModulePage'
import App from '../App'
const mocks=vi.hoisted(()=>({request:vi.fn(),clear:vi.fn(),menus:[] as any[]}))
vi.mock('../api/client',()=>({request:mocks.request,TenantApiError:class extends Error{}}))
vi.mock('../api/session',()=>({getTenantToken:()=> 'staff-token',clearTenantSession:mocks.clear,TENANT_SESSION_EXPIRED_EVENT:'expired'}))
vi.mock('../auth/AuthProvider',()=>({AuthProvider:({children}:any)=>children,useTenantAuth:()=>({status:'authenticated',principal:{staffType:'STORE',accessContext:{permissionCodes:[]}}})}))
vi.mock('../auth/RequireTenantWorkspace',()=>({RequireTenantWorkspace:({children,workspace}:any)=>workspace==='STORE'?children:null}))
vi.mock('../auth/TenantMenuProvider',()=>({TenantMenuProvider:({children}:any)=>children,useTenantMenus:()=>({status:'ready',menus:mocks.menus})}))
vi.mock('../components/TenantLayout',async()=>{const {Outlet}=await import('react-router-dom');return {default:()=> <Outlet/>}})
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.clearAllMocks()})
it('limits requests to the exact module API namespace',async()=>{
 mocks.request.mockResolvedValue({ok:true})
 const request=createModuleRequest('care-new')
 await expect(request('/api/tenant/v1/modules/care-new/tasks')).resolves.toEqual({ok:true})
 await expect(request('/api/tenant/v1/modules/care-new/tasks?q=%E5%BC%A0%E4%B8%89')).resolves.toEqual({ok:true})
 for(const path of ['/api/tenant/v1/modules/other/tasks','/api/tenant/v1/modules/care-newish/tasks','/api/tenant/v1/modules/care-new/../other','/api/tenant/v1/modules/care-new/%2e%2e/other','https://example.com']){
 await expect(request(path)).rejects.toThrow('超出允许路径')
 }
})
it('clears only the employee session on unauthorized entry',async()=>{
 vi.stubGlobal('fetch',vi.fn().mockResolvedValue({status:401,ok:false}))
 render(<RuntimeModulePage moduleCode="care-new" route="/modules/care-new"/>)
 expect(await screen.findByText('员工会话已失效，请重新登录')).toBeInTheDocument()
 expect(mocks.clear).toHaveBeenCalledOnce()
})
it('retries entry loading with the employee bearer token and aborts on cleanup',async()=>{
 const fetcher=vi.fn().mockResolvedValue({status:503,ok:false})
 vi.stubGlobal('fetch',fetcher)
 const view=render(<RuntimeModulePage moduleCode="care-new" route="/modules/care-new/tasks"/>)
 await screen.findByText('模块加载失败 (503)')
 const init=fetcher.mock.calls[0][1]
 expect(init.headers.Authorization).toBe('Bearer staff-token')
 fireEvent.click(screen.getByText('重试'))
 await screen.findByText('模块加载失败 (503)')
 expect(fetcher).toHaveBeenCalledTimes(2)
 expect(init.signal.aborted).toBe(true)
 view.unmount()
 expect(fetcher.mock.calls[1][1].signal.aborted).toBe(true)
})
it('matches the absolute server menu URL under the STORE layout',async()=>{
 mocks.menus=[{moduleCode:'care-new',route:'/modules/care-new/tasks',key:'tasks',label:'任务'}]
 vi.stubGlobal('fetch',vi.fn().mockResolvedValue({status:503,ok:false}))
 render(<MemoryRouter initialEntries={['/modules/care-new/tasks']}><App/></MemoryRouter>)
 expect(await screen.findByText('模块加载失败 (503)')).toBeInTheDocument()
 expect(screen.queryByText('页面不存在')).not.toBeInTheDocument()
})
it('does not select another menu route for the same module',async()=>{
 mocks.menus=[{moduleCode:'care-new',route:'/modules/care-new/tasks',key:'tasks',label:'任务'}]
 const fetcher=vi.fn();vi.stubGlobal('fetch',fetcher)
 render(<MemoryRouter initialEntries={['/modules/care-new/private']}><App/></MemoryRouter>)
 expect(fetcher).not.toHaveBeenCalled()
 expect(screen.queryByText('正在加载模块…')).not.toBeInTheDocument()
})
