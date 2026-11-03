import { useTenantAuth } from '../auth/AuthProvider'
import * as React from 'react'
import { request as tenantRequest } from '../api/client'
import { clearTenantSession, getTenantToken, TENANT_SESSION_EXPIRED_EVENT } from '../api/session'
import { isRuntimeModuleRoute } from '../navigation'

export function createModuleRequest(code: string, signal?: AbortSignal) {
  return <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    const prefix = `/api/tenant/v1/modules/${code}/`
    const pathname = path.split('?')[0]
    if (!/^[a-z][a-z0-9-]{1,31}$/.test(code) || !pathname.startsWith(prefix) ||
      /[\\%#]/.test(pathname) || pathname.split('/').some(p => p === '.' || p === '..')) {
      return Promise.reject(new Error('模块请求超出允许路径'))
    }
    return tenantRequest<T>(path, { ...init, signal: signal ?? init.signal })
  }
}

interface ModuleProps {
  React: typeof React
  workspace: 'HQ' | 'STORE'
  route: string
  request: ReturnType<typeof createModuleRequest>
}
class ModuleBoundary extends React.Component<{children: React.ReactNode; retry: () => void}, {failed: boolean}> {
  state = {failed: false}
  static getDerivedStateFromError() { return {failed: true} }
  render() {
    return this.state.failed ? <section role="alert">模块渲染失败。<button onClick={this.props.retry}>重试</button></section> : this.props.children
  }
}

export default function RuntimeModulePage({moduleCode, route}: {moduleCode: string; route: string}) {
  const {principal} = useTenantAuth()
  const workspace = principal?.staffType === 'HQ' ? 'HQ' : 'STORE'
  const [attempt, setAttempt] = React.useState(0)
  const [entry, setEntry] = React.useState<{Component: React.ComponentType<ModuleProps>; request: ModuleProps['request']} | null>(null)
  const [error, setError] = React.useState('')
  React.useEffect(() => {
    const controller = new AbortController()
    let url: string | undefined
    let active = true
    setEntry(null); setError('')
    async function load() {
      try {
        if (!isRuntimeModuleRoute(moduleCode, route)) throw new Error('模块路径无效')
        const token = getTenantToken()
        if (!token) throw new Error('员工会话已失效，请重新登录')
        const base = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') ?? ''
        const response = await fetch(`${base}/api/tenant/v1/modules/runtime/${moduleCode}/entry`, {
          headers: {Authorization: `Bearer ${token}`}, signal: controller.signal
        })
        if (!active) return
        if (response.status === 401) {
          clearTenantSession()
          window.dispatchEvent(new Event(TENANT_SESSION_EXPIRED_EVENT))
          throw new Error('员工会话已失效，请重新登录')
        }
        if (!response.ok) throw new Error(`模块加载失败 (${response.status})`)
        const source = await response.text()
        if (!active) return
        url = URL.createObjectURL(new Blob([source], {type: 'text/javascript'}))
        // Trusted, signed, self-contained ES module; React is supplied through props.
        const module = await import(/* @vite-ignore */ url)
        if (typeof module.default !== 'function') throw new Error('模块缺少 default 组件函数')
        if (active) setEntry({Component: module.default, request: createModuleRequest(moduleCode, controller.signal)})
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : '模块加载失败')
      } finally {
        if (url) { URL.revokeObjectURL(url); url = undefined }
      }
    }
    void load()
    return () => {active = false; controller.abort(); if (url) URL.revokeObjectURL(url)}
  }, [moduleCode, route, attempt, workspace])
  const retry = () => {setEntry(null); setAttempt(value => value + 1)}
  if (error) return <section role="alert"><p>{error}</p><button onClick={retry}>重试</button></section>
  if (!entry) return <section role="status">正在加载模块…</section>
  return <ModuleBoundary key={moduleCode + route + attempt} retry={retry}><entry.Component React={React} workspace={workspace} route={route} request={entry.request} /></ModuleBoundary>
}
