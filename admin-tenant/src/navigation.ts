import type { TenantModuleMenu } from './api/tenant'
import type { TenantStaffPrincipal } from './api/session'

export interface TenantNavigationItem {
  key: string
  label: string
  route: string
  moduleCode?: string
}

export const REGISTERED_MODULE_ROUTES = [
  { moduleCode: 'membership', route: '/membership' },
  { moduleCode: 'institution', route: '/institution' },
  { moduleCode: 'community', route: '/community' }
]

export function filterRegisteredModuleMenus(menus: TenantModuleMenu[]): TenantModuleMenu[] {
  return menus.filter(menu => REGISTERED_MODULE_ROUTES.some(
    registered => registered.moduleCode === menu.moduleCode && registered.route === menu.route
  ))
}

export function buildTenantNavigation(
  principal: TenantStaffPrincipal,
  moduleMenus: TenantModuleMenu[]
): TenantNavigationItem[] {
  if (principal.staffType === 'HQ') {
    const entries: TenantNavigationItem[] = [
      { key: 'hq-home', label: '总部工作台', route: '/hq' }
    ]
    if (principal.accessContext.permissionCodes.includes('core.module.view')) {
      entries.push({ key: 'hq-modules', label: '模块授权', route: '/hq/modules' })
    }
    return entries
  }
  return [
    { key: 'store-home', label: '门店工作台', route: '/store' },
    ...filterRegisteredModuleMenus(moduleMenus).map(menu => ({
      key: menu.key, label: menu.label, route: menu.route, moduleCode: menu.moduleCode
    }))
  ]
}
