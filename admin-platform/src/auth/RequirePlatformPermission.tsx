import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { usePlatformAuth } from './AuthProvider'
import ForbiddenPage from '../pages/ForbiddenPage'

export function canAccessPlatformRoute(permissionCodes: string[], requiredPermission: string): boolean {
  return permissionCodes.includes(requiredPermission)
}

export function RequirePlatformPermission({ permission, children }: {
  permission: string
  children: ReactNode
}) {
  const auth = usePlatformAuth()
  if (auth.status === 'checking') return <main role="status">正在验证平台会话…</main>
  if (auth.status === 'anonymous') return <Navigate to="/login" replace />
  if (auth.status === 'unavailable') return <main role="alert">平台服务暂不可用，请重试。</main>
  if (!auth.principal || !canAccessPlatformRoute(auth.principal.permissionCodes, permission)) return <ForbiddenPage />
  return children
}
