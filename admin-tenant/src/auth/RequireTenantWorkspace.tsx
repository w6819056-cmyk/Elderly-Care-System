import type { ReactNode } from 'react'
import { Link, Navigate } from 'react-router-dom'
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
  if (auth.status === 'anonymous' && workspace === 'HQ') return <Navigate to="/hq/login" replace />
  if (auth.status === 'anonymous') return <main className="tenant-state-page"><h1>门店员工请使用专属入口登录</h1><p>请通过门店专属登录地址进入工作台。</p><Link to="/brand">返回品牌首页</Link></main>
  if (auth.status === 'unavailable') return <main className="tenant-state-page" role="alert"><h1>企业服务暂不可用</h1><p>{auth.error}</p><button onClick={auth.retry}>重试</button></main>
  if (!auth.principal || auth.principal.staffType !== workspace) return <ForbiddenPage />
  if (permission && !hasTenantPermission(auth.principal.accessContext.permissionCodes, permission)) return <ForbiddenPage />
  return children
}
