import { describe, expect, it } from 'vitest'
import { filterPlatformNavigation, type PlatformNavigationItem } from './navigation'

describe('platform permission navigation', () => {
  const entries: PlatformNavigationItem[] = [
    { key: 'home', label: '工作台', path: '/', requiredPermission: null },
    { key: 'enterprises', label: '企业', path: '/enterprises', requiredPermission: 'platform.enterprise.view' },
    { key: 'orders', label: '订单', path: '/orders', requiredPermission: 'platform.order.view' }
  ]

  it('keeps only entries granted by the current principal', () => {
    expect(filterPlatformNavigation(entries, ['platform.enterprise.view']).map(item => item.key))
      .toEqual(['home', 'enterprises'])
  })
})
