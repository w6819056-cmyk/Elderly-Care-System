import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { PLATFORM_NAVIGATION, filterPlatformNavigation } from '../navigation'
import { usePlatformAuth } from '../auth/AuthProvider'

export default function PlatformLayout() {
  const { principal, logout } = usePlatformAuth()
  const navigate = useNavigate()
  const navigation = filterPlatformNavigation(PLATFORM_NAVIGATION, principal?.permissionCodes ?? [])

  function signOut() {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="platform-shell">
      <aside className="platform-sidebar">
        <div className="platform-brand"><span className="platform-mark">养</span><div><strong>养老云平台</strong><small>平台总管理端</small></div></div>
        <nav aria-label="平台导航">
          {navigation.map(item => <NavLink key={item.key} to={item.path} end>{item.label}</NavLink>)}
        </nav>
        <div className="platform-sidebar-foot">控制面 · 平台服务</div>
      </aside>
      <div className="platform-main">
        <header className="platform-topbar">
          <div><span className="platform-breadcrumb">平台运营</span><span className="platform-divider">/</span><strong>管理工作区</strong></div>
          <div className="platform-account"><span className="platform-avatar">{principal?.username.slice(0, 1).toUpperCase()}</span><span>{principal?.username}</span><button onClick={signOut}>退出</button></div>
        </header>
        <div className="platform-content"><Outlet /></div>
      </div>
    </div>
  )
}
