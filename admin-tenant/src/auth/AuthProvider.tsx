import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { TenantApiError } from '../api/client'
import { tenantApi } from '../api/tenant'
import {
  clearTenantSession, getTenantToken, saveTenantSession, TENANT_SESSION_EXPIRED_EVENT,
  type TenantStaffPrincipal
} from '../api/session'

export type TenantAuthStatus = 'checking' | 'authenticated' | 'anonymous' | 'unavailable'

interface TenantAuthValue {
  status: TenantAuthStatus
  principal: TenantStaffPrincipal | null
  error: string | null
  loginHq(account: string, password: string): Promise<TenantStaffPrincipal>
  logout(): void
  retry(): void
}

const TenantAuthContext = createContext<TenantAuthValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<TenantAuthStatus>('checking')
  const [principal, setPrincipal] = useState<TenantStaffPrincipal | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const onSessionExpired = () => {
      setPrincipal(null)
      setError(null)
      setStatus('anonymous')
    }
    window.addEventListener(TENANT_SESSION_EXPIRED_EVENT, onSessionExpired)
    return () => window.removeEventListener(TENANT_SESSION_EXPIRED_EVENT, onSessionExpired)
  }, [])

  useEffect(() => {
    let active = true
    const token = getTenantToken()
    if (!token) {
      setPrincipal(null)
      setError(null)
      setStatus('anonymous')
      return () => { active = false }
    }

    setStatus('checking')
    setPrincipal(null)
    setError(null)
    tenantApi.me().then(current => {
      if (!active) return
      const expiresAt = localStorage.getItem('tenant.expires_at') ?? ''
      saveTenantSession(token, expiresAt, current)
      setPrincipal(current)
      setStatus('authenticated')
    }).catch(reason => {
      if (!active) return
      if (reason instanceof TenantApiError && reason.status === 401) {
        setPrincipal(null)
        setStatus('anonymous')
        return
      }
      setPrincipal(null)
      setError(reason instanceof Error ? reason.message : '无法连接企业服务')
      setStatus('unavailable')
    })
    return () => { active = false }
  }, [attempt])

  const loginHq = useCallback(async (account: string, password: string) => {
    const result = await tenantApi.hqLogin(account, password)
    saveTenantSession(result.accessToken, result.expiresAt, result.principal)
    setPrincipal(result.principal)
    setError(null)
    setStatus('authenticated')
    return result.principal
  }, [])

  const logout = useCallback(() => {
    clearTenantSession()
    setPrincipal(null)
    setError(null)
    setStatus('anonymous')
  }, [])

  const retry = useCallback(() => setAttempt(value => value + 1), [])
  const value = useMemo(() => ({ status, principal, error, loginHq, logout, retry }),
    [status, principal, error, loginHq, logout, retry])

  return <TenantAuthContext.Provider value={value}>{children}</TenantAuthContext.Provider>
}

export function useTenantAuth(): TenantAuthValue {
  const context = useContext(TenantAuthContext)
  if (!context) throw new Error('useTenantAuth must be used inside AuthProvider')
  return context
}
