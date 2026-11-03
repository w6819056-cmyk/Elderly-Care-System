import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { tenantApi, type TenantModuleMenu } from '../api/tenant'
import { useTenantAuth } from './AuthProvider'

type TenantMenuStatus = 'idle' | 'loading' | 'ready' | 'error'
interface TenantMenuValue {
  status: TenantMenuStatus
  menus: TenantModuleMenu[]
  error: string | null
  retry(): void
}

const TenantMenuContext = createContext<TenantMenuValue>({ status: 'idle', menus: [], error: null, retry: () => undefined })

export function TenantMenuProvider({ children }: { children: ReactNode }) {
  const { status: authStatus } = useTenantAuth()
  const [value, setValue] = useState<Omit<TenantMenuValue, 'retry'>>({ status: 'idle', menus: [], error: null })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (authStatus !== 'authenticated') {
      setValue({ status: 'idle', menus: [], error: null })
      return
    }
    let active = true
    setValue({ status: 'loading', menus: [], error: null })
    tenantApi.menus().then(menus => {
      if (active) setValue({ status: 'ready', menus, error: null })
    }).catch(reason => {
      if (active) setValue({
        status: 'error', menus: [], error: reason instanceof Error ? reason.message : '模块菜单加载失败'
      })
    })
    return () => { active = false }
  }, [authStatus, attempt])

  return <TenantMenuContext.Provider value={{ ...value, retry: () => setAttempt(value => value + 1) }}>{children}</TenantMenuContext.Provider>
}

export function useTenantMenus(): TenantMenuValue {
  return useContext(TenantMenuContext)
}
