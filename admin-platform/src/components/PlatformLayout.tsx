import { Cloud, House, SignOut } from '@phosphor-icons/react'
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
        <div className="platform-brand"><Cloud size={49} weight="regular" /><div><strong>养老云</strong><small>平台总管理端</small></div></div>
        <nav aria-label="平台导航">
          {navigation.map(item => <NavLink key={item.key} to={item.path} end><House size={23} weight="fill" /><span>{item.label}</span></NavLink>)}
        </nav>
        <div className="platform-sidebar-foot"><span className="platform-avatar">{principal?.username.slice(0, 1).toUpperCase()}</span><span>{principal?.username}</span><button className="sidebar-signout" onClick={signOut} aria-label="退出登录"><SignOut size={18} /></button></div>
      </aside>
      <div className="platform-main">

        <div className="platform-content"><Outlet /></div>
      </div>
    </div>
  )
}
