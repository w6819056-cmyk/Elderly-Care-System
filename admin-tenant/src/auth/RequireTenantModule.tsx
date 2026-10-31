import { useTenantMenus } from './TenantMenuProvider'
import { RequireTenantWorkspace } from './RequireTenantWorkspace'
import ForbiddenPage from '../pages/ForbiddenPage'
import ModuleRoutePage from '../pages/ModuleRoutePage'

export function RequireTenantModule({ moduleCode, route }: { moduleCode: string; route: string }) {
  const menuState = useTenantMenus()

  return (
    <RequireTenantWorkspace workspace="STORE">
      {menuState.status === 'loading' && <section className="tenant-inline-state" role="status">正在验证模块授权…</section>}
      {menuState.status === 'error' && <section className="tenant-inline-state" role="alert"><h1>模块暂不可用</h1><p>当前无法验证此模块的授权状态。</p></section>}
      {menuState.status === 'ready' && (() => {
        const menu = menuState.menus.find(item => item.moduleCode === moduleCode && item.route === route)
        return menu ? <ModuleRoutePage menu={menu} /> : <ForbiddenPage />
      })()}
      {menuState.status === 'idle' && <main className="state-page" role="status">正在加载企业模块…</main>}
    </RequireTenantWorkspace>
  )
}
