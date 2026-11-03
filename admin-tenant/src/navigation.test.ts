import { describe, expect, it } from 'vitest'
import { buildTenantNavigation, filterRegisteredModuleMenus } from './navigation'
import type { TenantModuleMenu } from './api/tenant'

const menus: TenantModuleMenu[] = [
  { moduleCode: 'membership', key: 'membership', label: '会员管理', route: '/membership', icon: 'users', state: 'ACTIVE' },
  { moduleCode: 'institution', key: 'institution', label: '机构康养', route: '/institution', icon: 'building', state: 'ACTIVE' },
  { moduleCode: 'unknown', key: 'other', label: '其他模块', route: '/unknown', icon: 'box', state: 'ACTIVE' },
  { moduleCode: 'membership', key: 'spoof', label: '异常路由', route: '/hq/modules', icon: 'alert', state: 'ACTIVE' }
]

describe('tenant permission navigation', () => {
  it('shows customer management only with its dedicated permission in the correct workspace', () => {
    const hq = {staffId:1,staffPublicId:'hq',username:'owner',staffType:'HQ' as const,homeStoreId:null,accessContext:{permissionCodes:['core.customer.view']}}
    expect(buildTenantNavigation(hq,[]).map(item=>item.route)).toEqual(['/hq','/hq/customers'])
    const store = {...hq,staffType:'STORE' as const,homeStoreId:1}
    expect(buildTenantNavigation(store,[]).map(item=>item.route)).toEqual(['/store','/store/customers'])
    expect(buildTenantNavigation({...store,accessContext:{permissionCodes:['core.visit.view']}},[]).some(item=>item.route.includes('customers'))).toBe(false)
  })
  it('accepts only server module entries matching a registered route', () => {
    expect(filterRegisteredModuleMenus(menus).map(menu => menu.moduleCode)).toEqual(['membership', 'institution'])
  })

  it('shows HQ module settings only when the current principal has permission', () => {
    const hq = { staffId: 4, staffPublicId: 'st_4', username: 'hq', staffType: 'HQ' as const,
      homeStoreId: null, accessContext: { permissionCodes: [] } }
    expect(buildTenantNavigation(hq, menus).map(item => item.route)).toEqual(['/hq'])
    expect(buildTenantNavigation({ ...hq, accessContext: { permissionCodes: ['core.module.view'] } }, menus)
      .map(item => item.route)).toEqual(['/hq', '/hq/modules', '/hq/module-install', '/hq/shop'])
  })

  it('uses server-returned module menus only for a store employee', () => {
    const store = { staffId: 5, staffPublicId: 'st_5', username: 'store', staffType: 'STORE' as const,
      homeStoreId: 11, accessContext: { permissionCodes: [] } }
    expect(buildTenantNavigation(store, menus).map(item => item.route))
      .toEqual(['/store', '/membership', '/institution'])
  })
})
it('accepts runtime routes only inside their own valid module namespace',()=>{
 const entry=(moduleCode:string,route:string)=>({moduleCode,route,key:route,label:'模块',icon:'box',state:'ACTIVE'} as TenantModuleMenu)
 expect(filterRegisteredModuleMenus([
 entry('care-new','/modules/care-new'),entry('care-new','/modules/care-new/tasks'),
 entry('care-new','/modules/other'),entry('care-new','/modules/care-newish'),
 entry('A','/modules/A'),entry('a','/modules/a'),entry('care-new','/modules/care-new/../hq'),
 entry('care-new','/modules/care-new/%2e%2e/hq'),entry('care-new','/hq/modules')
 ]).map(m=>m.route)).toEqual(['/modules/care-new','/modules/care-new/tasks'])
})
it('shows HQ runtime server menus once and preserves STORE legacy menus',()=>{
 const principal={staffId:4,staffPublicId:'hq',username:'hq',staffType:'HQ' as const,homeStoreId:null,accessContext:{permissionCodes:[]}}
 const runtime={...menus[0],route:'/modules/membership'}
 expect(buildTenantNavigation(principal,[runtime,{...runtime,key:'another'},menus[0]]).map(m=>m.route)).toEqual(['/hq','/modules/membership'])
 expect(buildTenantNavigation({...principal,staffType:'STORE'},[runtime,menus[0]]).map(m=>m.route)).toEqual(['/store','/modules/membership','/membership'])
})
