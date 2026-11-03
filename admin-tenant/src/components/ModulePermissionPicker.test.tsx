import { afterEach,describe,expect,it,vi } from 'vitest'
import { cleanup,render,screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import ModulePermissionPicker from './ModulePermissionPicker'
vi.mock('../api/client',()=>({request:()=>Promise.resolve([{code:'core',name:'基础经营'},{code:'membership',name:'会员运营'},{code:'future',name:'新增康复模块'}])}))
afterEach(cleanup)
const defs=[{code:'core.store.view',moduleCode:'core',name:'查看门店',description:''},{code:'membership:member:read',moduleCode:'membership',name:'查看会员资料',description:''},{code:'future:record:read',moduleCode:'future',name:'查看康复记录',description:''}]
function Example(){const [checked,setChecked]=useState<string[]>([]);return <ModulePermissionPicker definitions={defs} mode="group" checked={checked} onChecked={setChecked}/>}
describe('module permission selection',()=>{it('discovers future modules and preserves selections across groups',async()=>{const user=userEvent.setup();render(<Example/>);await user.click(screen.getByLabelText(/查看门店/));await user.click(await screen.findByRole('button',{name:/新增康复模块/}));await user.click(screen.getByLabelText(/查看康复记录/));await user.click(screen.getByRole('button',{name:/基础经营/}));expect(screen.getByLabelText(/查看门店/)).toBeChecked();await user.click(screen.getByRole('button',{name:/新增康复模块/}));expect(screen.getByLabelText(/查看康复记录/)).toBeChecked()})})
