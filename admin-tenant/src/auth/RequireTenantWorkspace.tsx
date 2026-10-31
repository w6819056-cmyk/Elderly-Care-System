import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useTenantAuth } from './AuthProvider'
import ForbiddenPage from '../pages/ForbiddenPage'

export function hasTenantPermission(permissionCodes: string[], permission: string): boolean {
  return permissionCodes.includes(permission)
}

export function RequireTenantWorkspace({ workspace, permission, children }: {
  workspace: 'HQ' | 'STORE'
  permission?: string
  children: ReactNode
}) {
  const auth = useTenantAuth()
  if (auth.status === 'checking') return <main className="tenant-state-page" role="status">正在验证员工会话…</main>
  if (auth.status === 'anonymous') return <Navigate to="/login" replace />
  if (auth.status === 'unavailable') return <main className="tenant-state-page" role="alert"><h1>企业服务暂不可用</h1><p>{auth.error}</p><button onClick={auth.retry}>重试</button></main>
  if (!auth.principal || auth.principal.staffType !== workspace) return <ForbiddenPage />
  if (permission && !hasTenantPermission(auth.principal.accessContext.permissionCodes, permission)) return <ForbiddenPage />
  return children
}
