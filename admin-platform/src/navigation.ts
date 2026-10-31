export interface PlatformNavigationItem {
  key: string
  label: string
  path: string
  requiredPermission: string | null
}

export const PLATFORM_NAVIGATION: PlatformNavigationItem[] = [
  { key: 'home', label: '工作台', path: '/', requiredPermission: null }
]

export function filterPlatformNavigation(
  items: PlatformNavigationItem[],
  permissionCodes: string[]
): PlatformNavigationItem[] {
  const grants = new Set(permissionCodes)
  return items.filter(item => item.requiredPermission === null || grants.has(item.requiredPermission))
}
