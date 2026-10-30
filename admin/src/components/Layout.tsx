import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { clearSession, getUser } from '../api/client'

const ROLE_LABEL: Record<string, string> = {
  PLATFORM_ADMIN: '平台运营管理员',
  SYS_ADMIN: '系统管理员',
  ORG_ADMIN: '机构管理员',
  PROVIDER: '服务人员'
}

export default function Layout() {
  const user = getUser()
  const navigate = useNavigate()
  const isAdmin = user?.role === 'PLATFORM_ADMIN' || user?.role === 'SYS_ADMIN'

  const logout = () => {
    clearSession()
    navigate('/login', { replace: true })
  }

  return (
    <div className="layout">
      <aside className="sidebar">
        <h1>综合智慧养老平台</h1>
        <div className="role">{ROLE_LABEL[user?.role ?? ''] ?? user?.role}</div>
        <nav>
          <NavLink to="/dashboard" className={({ isActive }) => (isActive ? 'active' : '')}>
            数据看板
          </NavLink>
          {isAdmin && (
            <NavLink to="/orders" className={({ isActive }) => (isActive ? 'active' : '')}>
              订单监控
            </NavLink>
          )}
          {!isAdmin && (
            <NavLink to="/work-orders" className={({ isActive }) => (isActive ? 'active' : '')}>
              我的工单
            </NavLink>
          )}
          <NavLink to="/alerts" className={({ isActive }) => (isActive ? 'active' : '')}>
            告警工作台
          </NavLink>
          {isAdmin && (
            <>
              <NavLink to="/providers" className={({ isActive }) => (isActive ? 'active' : '')}>
                服务商审核
              </NavLink>
              <NavLink to="/items" className={({ isActive }) => (isActive ? 'active' : '')}>
                服务项目审核
              </NavLink>
              <NavLink to="/users" className={({ isActive }) => (isActive ? 'active' : '')}>
                用户管理
              </NavLink>
            </>
          )}
        </nav>
      </aside>
      <main className="main">
        <div className="topbar">
          <h2>综合智慧养老平台 · 管理端</h2>
          <div className="who">
            <span>{user?.nickname}</span>
            <span className="muted">{user?.phone}</span>
            <button onClick={logout}>退出登录</button>
          </div>
        </div>
        <Outlet />
      </main>
    </div>
  )
}
