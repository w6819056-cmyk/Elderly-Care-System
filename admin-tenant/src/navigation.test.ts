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
  it('accepts only server module entries matching a registered route', () => {
    expect(filterRegisteredModuleMenus(menus).map(menu => menu.moduleCode)).toEqual(['membership', 'institution'])
  })

  it('shows HQ module settings only when the current principal has permission', () => {
    const hq = { staffId: 4, staffPublicId: 'st_4', username: 'hq', staffType: 'HQ' as const,
      homeStoreId: null, accessContext: { permissionCodes: [] } }
    expect(buildTenantNavigation(hq, menus).map(item => item.route)).toEqual(['/hq'])
    expect(buildTenantNavigation({ ...hq, accessContext: { permissionCodes: ['core.module.view'] } }, menus)
      .map(item => item.route)).toEqual(['/hq', '/hq/modules'])
  })

  it('uses server-returned module menus only for a store employee', () => {
    const store = { staffId: 5, staffPublicId: 'st_5', username: 'store', staffType: 'STORE' as const,
      homeStoreId: 11, accessContext: { permissionCodes: [] } }
    expect(buildTenantNavigation(store, menus).map(item => item.route))
      .toEqual(['/store', '/membership', '/institution'])
  })
})
