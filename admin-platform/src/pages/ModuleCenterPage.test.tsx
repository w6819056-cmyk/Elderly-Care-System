import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {cleanup,render,screen} from '@testing-library/react'
import ModuleCenterPage, { groupPackageRows } from './ModuleCenterPage'
const mocks=vi.hoisted(()=>({request:vi.fn(),permissions:[] as string[]}))
vi.mock('../api/client',()=>({request:mocks.request}))
vi.mock('../auth/AuthProvider',()=>({usePlatformAuth:()=>({principal:{permissionCodes:mocks.permissions}})}))
afterEach(cleanup)
beforeEach(()=>{vi.clearAllMocks();mocks.permissions=[];mocks.request.mockResolvedValue([])})
it('does not fetch packages without platform view permission',()=>{render(<ModuleCenterPage/>);expect(screen.getByText('无权查看模块中心')).toBeInTheDocument();expect(mocks.request).not.toHaveBeenCalled()})
it('lets read-only staff see package records without upload actions',async()=>{mocks.permissions=['platform.module.view'];mocks.request.mockResolvedValue([{moduleCode:'membership',name:'会员运营',version:'1.0.0',sha256:'abcd',status:'VERIFIED',createdAt:'2026-11-01T10:00:00Z'}]);render(<ModuleCenterPage/>);expect(await screen.findByText('会员运营')).toBeInTheDocument();expect(screen.queryByRole('button',{name:'校验并收录'})).not.toBeInTheDocument();expect(screen.getByText('新版本会归入当前模块，不会生成新的模块卡片。')).toBeInTheDocument()})

it('groups package versions into one module card and selects the highest published version',()=>{
 const groups=groupPackageRows([
  {moduleCode:'membership',name:'会员运营',version:'1.0.0',sha256:'old',status:'PUBLISHED',createdAt:'2026-11-01T10:00:00Z'},
  {moduleCode:'membership',name:'会员运营',version:'1.2.0',sha256:'new',status:'PUBLISHED',createdAt:'2026-11-02T10:00:00Z'},
  {moduleCode:'membership',name:'会员运营',version:'1.3.0',sha256:'pending',status:'VERIFIED',createdAt:'2026-11-03T10:00:00Z'},
  {moduleCode:'community',name:'社区居家',version:'2.0.0',sha256:'community',status:'PUBLISHED',createdAt:'2026-11-02T11:00:00Z'}
 ])
 expect(groups).toHaveLength(2)
 expect(groups.find(group=>group.moduleCode==='membership')).toMatchObject({latestPublishedVersion:'1.2.0'})
 expect(groups.find(group=>group.moduleCode==='membership')?.versions).toHaveLength(3)
})
