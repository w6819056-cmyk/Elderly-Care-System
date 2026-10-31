import { Link, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useTenantAuth } from './auth/AuthProvider'
import { RequireTenantWorkspace } from './auth/RequireTenantWorkspace'
import { TenantMenuProvider } from './auth/TenantMenuProvider'
import { RequireTenantModule } from './auth/RequireTenantModule'
import TenantLayout from './components/TenantLayout'
import { REGISTERED_MODULE_ROUTES } from './navigation'
import ForbiddenPage from './pages/ForbiddenPage'
import TenantHomePage from './pages/TenantHomePage'
import TenantLoginPage from './pages/TenantLoginPage'
import TenantModulesPage from './pages/TenantModulesPage'

function LoginRoute() {
  const { status, principal } = useTenantAuth()
  if (status === 'checking') return <main className="tenant-state-page" role="status">正在验证员工会话…</main>
  if (status === 'authenticated' && principal) return <Navigate to={principal.staffType === 'HQ' ? '/hq' : '/store'} replace />
  return <TenantLoginPage />
}

function WorkspaceHomeRedirect() {
  const { status, principal, error, retry } = useTenantAuth()
  if (status === 'checking') return <main className="tenant-state-page" role="status">正在验证员工会话…</main>
  if (status === 'anonymous') return <Navigate to="/login" replace />
  if (status === 'unavailable') return <main className="tenant-state-page" role="alert"><h1>企业服务暂不可用</h1><p>{error}</p><button onClick={retry}>重试</button></main>
  return <Navigate to={principal?.staffType === 'HQ' ? '/hq' : '/store'} replace />
}

function NotFoundPage() {
  return <main className="tenant-state-page"><h1>页面不存在</h1><Link to="/">返回工作台</Link></main>
}

function TenantRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginRoute />} />
      <Route path="/forbidden" element={<ForbiddenPage />} />
      <Route path="/" element={<WorkspaceHomeRedirect />} />
      <Route path="/hq" element={<RequireTenantWorkspace workspace="HQ"><TenantLayout workspace="HQ" /></RequireTenantWorkspace>}>
        <Route index element={<TenantHomePage workspace="HQ" />} />
        <Route path="modules" element={<RequireTenantWorkspace workspace="HQ" permission="core.module.view"><TenantModulesPage /></RequireTenantWorkspace>} />
      </Route>
      <Route path="/store" element={<RequireTenantWorkspace workspace="STORE"><TenantLayout workspace="STORE" /></RequireTenantWorkspace>}>
        <Route index element={<TenantHomePage workspace="STORE" />} />
        {REGISTERED_MODULE_ROUTES.map(module => (
          <Route key={module.route} path={module.route.slice('/'.length)} element={<RequireTenantModule moduleCode={module.moduleCode} route={module.route} />} />
        ))}
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default function App() {
  return <AuthProvider><TenantMenuProvider><TenantRoutes /></TenantMenuProvider></AuthProvider>
}
