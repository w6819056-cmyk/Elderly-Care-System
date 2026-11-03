import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import TenantStoresPage from './TenantStoresPage'
const mocks=vi.hoisted(()=>({stores:vi.fn(),create:vi.fn(),status:vi.fn(),principal:{accessContext:{permissionCodes:['core.store.manage'],dataScope:{type:'ALL'}}}}))
vi.mock('../auth/AuthProvider',()=>({useTenantAuth:()=>({principal:mocks.principal})}))
vi.mock('../api/organization',()=>({organizationApi:mocks}))
afterEach(cleanup)
beforeEach(()=>{vi.clearAllMocks();mocks.principal.accessContext.permissionCodes=['core.store.manage'];mocks.principal.accessContext.dataScope.type='ALL';mocks.stores.mockResolvedValue([])})
describe('store management',()=>{
 it('creates a trimmed store with optional fields and refreshes',async()=>{const user=userEvent.setup();mocks.create.mockResolvedValue({});render(<TenantStoresPage/>);await screen.findByText('当前没有门店');await user.click(screen.getByRole('button',{name:'新建门店'}));await user.type(screen.getByLabelText('门店编号 *'),' S-1 ');await user.type(screen.getByLabelText('门店名称 *'),' 新门店 ');await user.click(screen.getByRole('button',{name:'创建门店'}));await waitFor(()=>expect(mocks.create).toHaveBeenCalledWith({storeNo:'S-1',name:'新门店',regionCode:'',address:''}));expect(await screen.findByRole('status')).toHaveTextContent('门店已创建')})
 it('requires confirmation before status mutation',async()=>{const user=userEvent.setup();mocks.stores.mockResolvedValue([{publicId:'s1',storeNo:'S1',name:'中心店',status:'ACTIVE'}]);mocks.status.mockResolvedValue({});render(<TenantStoresPage/>);await screen.findByText('中心店');await user.click(screen.getByRole('button',{name:'停用'}));expect(mocks.status).not.toHaveBeenCalled();await user.click(screen.getByRole('button',{name:'确认'}));await waitFor(()=>expect(mocks.status).toHaveBeenCalledWith('s1','SUSPENDED'))})
 it('hides writes for restricted data scope',async()=>{mocks.principal.accessContext.dataScope.type='STORES';render(<TenantStoresPage/>);await screen.findByText('当前没有门店');expect(screen.queryByRole('button',{name:'新建门店'})).not.toBeInTheDocument()})
})

