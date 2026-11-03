import type { TenantModuleMenu } from './api/tenant'
import type { TenantStaffPrincipal } from './api/session'

export interface TenantNavigationItem {
  key: string
  label: string
  route: string
  moduleCode?: string
}

export function isRuntimeModuleRoute(moduleCode: string, route: string): boolean {
  if (!/^[a-z][a-z0-9-]{1,31}$/.test(moduleCode)) return false
  if (/[\\%?#]/.test(route) || route.split('/').some(part => part === '.' || part === '..')) return false
  const base = '/modules/' + moduleCode
  return route === base || route.startsWith(base + '/')
}

export function filterRuntimeModuleMenus(menus: TenantModuleMenu[]): TenantModuleMenu[] {
  return menus.filter(menu => isRuntimeModuleRoute(menu.moduleCode, menu.route))
}

export function buildTenantNavigation(
  principal: TenantStaffPrincipal,
  moduleMenus: TenantModuleMenu[]
): TenantNavigationItem[] {
  if (principal.staffType === 'HQ') {
    const entries: TenantNavigationItem[] = [
      { key: 'hq-home', label: '总部工作台', route: '/hq' }
    ]
    if (principal.accessContext.permissionCodes.includes('core.store.view')) { entries.push({ key: 'hq-stores', label: '门店管理', route: '/hq/stores' }) }
    if (principal.accessContext.permissionCodes.includes('iam.staff.view')) { entries.push({ key: 'hq-staff', label: '员工管理', route: '/hq/staff' }); entries.push({key:'hq-applications',label:'入职申请',route:'/hq/staff-applications'}) }
    if (principal.accessContext.permissionCodes.some(p => p === 'iam.role.view' || p === 'iam.permission-group.view')) { entries.push({ key: 'hq-permissions', label: '角色与权限组', route: '/hq/permissions' }) }
    if (principal.accessContext.permissionCodes.includes('core.customer.view')) { entries.push({key:'hq-customers',label:'客户管理',route:'/hq/customers'}) }
    if (principal.accessContext.permissionCodes.includes('core.visit.view')) { entries.push({key:'hq-visits',label:'咨询预约',route:'/hq/visits'}) }
    if (principal.accessContext.permissionCodes.includes('core.site.view')) { entries.push({key:'hq-site',label:'首页内容',route:'/hq/site'}) }
    if (principal.accessContext.permissionCodes.includes('core.module.view')) {
      entries.push({ key: 'hq-modules', label: '模块授权', route: '/hq/modules' }); entries.push({key:'hq-module-install',label:'模块安装',route:'/hq/module-install'}); entries.push({key:'hq-shop',label:'模块购买',route:'/hq/shop'})
    }
    entries.push(...filterRuntimeModuleMenus(moduleMenus).filter((menu, index, all) => all.findIndex(other => other.moduleCode === menu.moduleCode && other.route === menu.route) === index).map(menu => ({key: menu.key, label: menu.label, route: menu.route, moduleCode: menu.moduleCode})))
    return entries
  }
  return [
    { key: 'store-home', label: '门店工作台', route: '/store' },
    ...(principal.accessContext.permissionCodes.includes('core.customer.view') ? [{key:'store-customers',label:'客户管理',route:'/store/customers'}] : []),
    ...(principal.accessContext.permissionCodes.includes('core.site.view') ? [{key:'store-site',label:'首页内容',route:'/store/site'}] : []),
    ...(principal.accessContext.permissionCodes.includes('core.visit.view') ? [{key:'store-visits',label:'咨询预约',route:'/store/visits'}] : []),
    ...filterRuntimeModuleMenus(moduleMenus).map(menu => ({
      key: menu.key, label: menu.label, route: menu.route, moduleCode: menu.moduleCode
    }))
  ]
}
