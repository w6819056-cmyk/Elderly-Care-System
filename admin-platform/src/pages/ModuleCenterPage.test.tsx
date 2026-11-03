import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {cleanup,render,screen} from '@testing-library/react'
import ModuleCenterPage from './ModuleCenterPage'
const mocks=vi.hoisted(()=>({request:vi.fn(),permissions:[] as string[]}))
vi.mock('../api/client',()=>({request:mocks.request}))
vi.mock('../auth/AuthProvider',()=>({usePlatformAuth:()=>({principal:{permissionCodes:mocks.permissions}})}))
afterEach(cleanup)
beforeEach(()=>{vi.clearAllMocks();mocks.permissions=[];mocks.request.mockResolvedValue([])})
it('does not fetch packages without platform view permission',()=>{render(<ModuleCenterPage/>);expect(screen.getByText('无权查看模块中心')).toBeInTheDocument();expect(mocks.request).not.toHaveBeenCalled()})
it('lets read-only staff see package records without upload actions',async()=>{mocks.permissions=['platform.module.view'];mocks.request.mockResolvedValue([{moduleCode:'membership',name:'会员运营',version:'1.0.0',sha256:'abcd',status:'VERIFIED',createdAt:'2026-11-01T10:00:00Z'}]);render(<ModuleCenterPage/>);expect(await screen.findByText('会员运营')).toBeInTheDocument();expect(screen.queryByRole('button',{name:'校验并收录'})).not.toBeInTheDocument();expect(screen.getByText('更新功能请上传更高版本的签名包；已收录的版本不可覆盖。')).toBeInTheDocument()})
