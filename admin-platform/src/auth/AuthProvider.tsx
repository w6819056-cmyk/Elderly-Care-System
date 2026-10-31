import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiClientError, platformApi } from '../api/client'
import {
  clearPlatformSession, getPlatformToken, savePlatformSession, type PlatformPrincipal
} from '../api/session'

export type PlatformAuthStatus = 'checking' | 'authenticated' | 'anonymous' | 'unavailable'

interface PlatformAuthValue {
  status: PlatformAuthStatus
  principal: PlatformPrincipal | null
  error: string | null
  login(username: string, password: string): Promise<void>
  logout(): void
  retry(): void
}

const PlatformAuthContext = createContext<PlatformAuthValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<PlatformAuthStatus>('checking')
  const [principal, setPrincipal] = useState<PlatformPrincipal | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    const token = getPlatformToken()
    if (!token) {
      setPrincipal(null)
      setError(null)
      setStatus('anonymous')
      return () => { active = false }
    }

    setStatus('checking')
    setPrincipal(null)
    setError(null)
    platformApi.me().then(current => {
      if (!active) return
      const expiresAt = localStorage.getItem('platform.expires_at') ?? ''
      savePlatformSession(token, expiresAt, current)
      setPrincipal(current)
      setStatus('authenticated')
    }).catch(reason => {
      if (!active) return
      if (reason instanceof ApiClientError && reason.status === 401) {
        setPrincipal(null)
        setStatus('anonymous')
        return
      }
      setPrincipal(null)
      setError(reason instanceof Error ? reason.message : '无法连接平台服务')
      setStatus('unavailable')
    })
    return () => { active = false }
  }, [attempt])

  const login = useCallback(async (username: string, password: string) => {
    const result = await platformApi.login(username, password)
    savePlatformSession(result.accessToken, result.expiresAt, result.principal)
    setPrincipal(result.principal)
    setError(null)
    setStatus('authenticated')
  }, [])

  const logout = useCallback(() => {
    clearPlatformSession()
    setPrincipal(null)
    setError(null)
    setStatus('anonymous')
  }, [])

  const retry = useCallback(() => setAttempt(value => value + 1), [])
  const value = useMemo(() => ({ status, principal, error, login, logout, retry }),
    [status, principal, error, login, logout, retry])

  return <PlatformAuthContext.Provider value={value}>{children}</PlatformAuthContext.Provider>
}

export function usePlatformAuth(): PlatformAuthValue {
  const context = useContext(PlatformAuthContext)
  if (!context) throw new Error('usePlatformAuth must be used inside AuthProvider')
  return context
}
