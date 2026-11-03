import { useTenantAuth } from './AuthProvider'
import TenantLayout from '../components/TenantLayout'
import { useLocation, useParams } from 'react-router-dom'
import RuntimeModulePage from '../pages/RuntimeModulePage'
import { isRuntimeModuleRoute } from '../navigation'
import { useTenantMenus } from './TenantMenuProvider'
import { RequireTenantWorkspace } from './RequireTenantWorkspace'
import ForbiddenPage from '../pages/ForbiddenPage'
import ModuleRoutePage from '../pages/ModuleRoutePage'

export function RequireTenantModule({ moduleCode: registeredCode, route: registeredRoute }: { moduleCode?: string; route?: string }) {
  const {principal} = useTenantAuth()
  const workspace = principal?.staffType === 'HQ' ? 'HQ' : 'STORE'
  const params = useParams()
  const location = useLocation()
  const moduleCode = registeredCode ?? params.moduleCode ?? ''
  const route = registeredRoute ?? location.pathname
  const runtime = registeredCode === undefined
  const menuState = useTenantMenus()

  return (
    <RequireTenantWorkspace workspace={workspace}>
      {menuState.status === 'loading' && <section className="tenant-inline-state" role="status">正在验证模块授权…</section>}
      {menuState.status === 'error' && <section className="tenant-inline-state" role="alert"><h1>模块暂不可用</h1><p>当前无法验证此模块的授权状态。</p></section>}
      {menuState.status === 'ready' && (() => {
        const menu = menuState.menus.find(item => item.moduleCode === moduleCode && item.route === route && (!runtime || isRuntimeModuleRoute(moduleCode, item.route)))
        return menu ? (runtime ? <RuntimeModulePage key={moduleCode + route} moduleCode={moduleCode} route={route} /> : <ModuleRoutePage menu={menu} />) : <ForbiddenPage />
      })()}
      {menuState.status === 'idle' && <main className="state-page" role="status">正在加载企业模块…</main>}
    </RequireTenantWorkspace>
  )
}

export function TenantModuleLayout() { const {principal} = useTenantAuth(); const workspace = principal?.staffType === "HQ" ? "HQ" : "STORE"; return <RequireTenantWorkspace workspace={workspace}><TenantLayout workspace={workspace}/></RequireTenantWorkspace> }
