import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import StaffAccessEditor from './StaffAccessEditor'
const mocks=vi.hoisted(()=>({get:vi.fn(),roles:vi.fn(),scope:vi.fn(),directory:vi.fn(),stores:vi.fn()}))
vi.mock('../auth/AuthProvider',()=>({useTenantAuth:()=>({principal:{accessContext:{permissionCodes:['iam.staff.manage','iam.role.view','core.store.view'],dataScope:{type:'ALL'}}}})}))
vi.mock('../api/iam',()=>({staffAccessApi:mocks,iamApi:{roles:mocks.directory}}))
vi.mock('../api/organization',()=>({organizationApi:{stores:mocks.stores}}))
vi.mock('../pages/TenantStoresPage',()=>({StoreDialog:({children}:{children:React.ReactNode})=><div>{children}</div>}))
afterEach(()=>{cleanup();vi.clearAllMocks()})
const staff={id:2,publicId:'staff-2',username:'manager',displayName:'经理',staffType:'HQ',homeStoreId:null,status:'ACTIVE'}
describe('staff access editor',()=>{
 it('preserves current selection and confirms before replacing roles',async()=>{const user=userEvent.setup();mocks.get.mockResolvedValue({roleCodes:['HQ_MANAGER'],protectedOwner:false,scopeType:'SELF',storePublicIds:[],storeGroupPublicIds:[]});mocks.directory.mockResolvedValue([{roleCode:'HQ_MANAGER',name:'总部经理',roleDomain:'HQ',protectedRole:false,status:'ACTIVE'}]);mocks.roles.mockResolvedValue({});render(<StaffAccessEditor staff={staff} mode="roles" close={()=>{}} done={()=>{}}/>);expect(await screen.findByLabelText('总部经理')).toBeChecked();await user.click(screen.getByRole('button',{name:'保存配置'}));expect(mocks.roles).not.toHaveBeenCalled();await user.click(screen.getByRole('button',{name:'确认保存'}));await waitFor(()=>expect(mocks.roles).toHaveBeenCalledWith('staff-2',['HQ_MANAGER']))})
 it('does not allow changing a protected owner',async()=>{mocks.get.mockResolvedValue({roleCodes:['TENANT_OWNER'],protectedOwner:true,scopeType:'ALL',storePublicIds:[],storeGroupPublicIds:[]});mocks.directory.mockResolvedValue([]);render(<StaffAccessEditor staff={staff} mode="roles" close={()=>{}} done={()=>{}}/>);await screen.findByText('企业所有者账号受保护，不能修改角色或范围。');expect(screen.queryByRole('button',{name:'保存配置'})).not.toBeInTheDocument()})
})
