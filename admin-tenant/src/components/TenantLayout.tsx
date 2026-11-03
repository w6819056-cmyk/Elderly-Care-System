import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useTenantAuth } from '../auth/AuthProvider'
import { useTenantMenus } from '../auth/TenantMenuProvider'
import { buildTenantNavigation } from '../navigation'

export default function TenantLayout({ workspace }: { workspace: 'HQ' | 'STORE' }) {
  const { principal, logout } = useTenantAuth()
  const menus = useTenantMenus()
  const navigate = useNavigate()
  const navigation = principal ? buildTenantNavigation(principal, menus.menus) : []
  const isHq = workspace === 'HQ'

  function signOut() {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="tenant-shell">
      <aside className="tenant-sidebar">
        <div className="tenant-brand"><div><strong>企业管理系统</strong><small>{isHq ? '总部工作台' : '门店工作台'}</small></div></div>
        <nav aria-label="企业导航">
          {navigation.map(item => <NavLink key={item.key} to={item.route} end>{item.label}</NavLink>)}
        </nav>
        {menus.status === 'error' && <div className="menu-load-error" role="alert">模块菜单暂不可用，已隐藏模块入口。<button onClick={menus.retry}>重试</button></div>}
        <div className="tenant-nav-account"><span className="tenant-avatar">{principal?.username.slice(0, 1).toUpperCase()}</span><span>{principal?.username}</span><button onClick={signOut}>退出</button></div>
      </aside>
      <div className="tenant-main">

        <main className="tenant-content"><Outlet /></main>
      </div>
    </div>
  )
}
