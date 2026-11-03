export interface PlatformNavigationItem {
  key: string
  label: string
  path: string
  requiredPermission: string | null
}

export const PLATFORM_NAVIGATION: PlatformNavigationItem[] = [
  { key: 'home', label: '工作台', path: '/', requiredPermission: null },
  { key: 'enterprises', label: '企业管理', path: '/enterprises', requiredPermission: 'platform.enterprise.view' },
  { key: 'modules', label: '模块中心', path: '/modules', requiredPermission: 'platform.module.view' },
  { key: 'module-shop', label: '模块售卖', path: '/module-shop', requiredPermission: 'platform.module.view' },
  { key: 'sales', label: '销售授权', path: '/sales', requiredPermission: 'platform.order.view' }
]

export function filterPlatformNavigation(
  items: PlatformNavigationItem[],
  permissionCodes: string[]
): PlatformNavigationItem[] {
  const grants = new Set(permissionCodes)
  return items.filter(item => item.requiredPermission === null || grants.has(item.requiredPermission))
}
