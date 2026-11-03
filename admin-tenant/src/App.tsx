import CustomerManagementPage from './pages/CustomerManagementPage'
import TenantShopPage from './pages/TenantShopPage'
import ModuleInstallPage from './pages/ModuleInstallPage'
import CustomerPage from './pages/CustomerPage'
import StoreDashboardPage from './pages/StoreDashboardPage'
import PublicHomePage from './pages/PublicHomePage'
import SiteContentPage from './pages/SiteContentPage'
import VisitBookingsPage from './pages/VisitBookingsPage'
import StorePortalPage from './pages/StorePortalPage'
import StaffApplicationsPage from './pages/StaffApplicationsPage'
import { Link, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useTenantAuth } from './auth/AuthProvider'
import { RequireTenantWorkspace } from './auth/RequireTenantWorkspace'
import { TenantMenuProvider } from './auth/TenantMenuProvider'
import { RequireTenantModule, TenantModuleLayout } from './auth/RequireTenantModule'
import TenantLayout from './components/TenantLayout'
import { REGISTERED_MODULE_ROUTES } from './navigation'
import ForbiddenPage from './pages/ForbiddenPage'
import TenantHomePage from './pages/TenantHomePage'
import TenantLoginPage from './pages/TenantLoginPage'
import TenantPermissionsPage from './pages/TenantPermissionsPage'
import TenantStaffPage from './pages/TenantStaffPage'
import TenantStoresPage from './pages/TenantStoresPage'
import TenantModulesPage from './pages/TenantModulesPage'

function HeadquartersLoginRoute() {
  const { status, principal } = useTenantAuth()
  if (status === 'checking') return <main className="tenant-state-page" role="status">正在验证员工会话…</main>
  if (status === 'authenticated' && principal) return <Navigate to={principal.staffType === 'HQ' ? '/hq' : '/store'} replace />
  return <TenantLoginPage />
}

function WorkspaceHomeRedirect() {
  const { status, principal, error, retry } = useTenantAuth()
  if (status === 'checking') return <main className="tenant-state-page" role="status">正在验证员工会话…</main>
  if (status === 'anonymous') return <Navigate to="/hq/login" replace />
  if (status === 'unavailable') return <main className="tenant-state-page" role="alert"><h1>企业服务暂不可用</h1><p>{error}</p><button onClick={retry}>重试</button></main>
  return <Navigate to={principal?.staffType === 'HQ' ? '/hq' : '/store'} replace />
}

function NotFoundPage() {
  return <main className="tenant-state-page"><h1>页面不存在</h1><Link to="/">返回工作台</Link></main>
}

function TenantRoutes() {
  return (
    <Routes>
      <Route path="/customer" element={<CustomerPage />} />
      <Route path="/customer/login" element={<CustomerPage mode="login" key="customer-login" />} />
      <Route path="/customer/register" element={<CustomerPage mode="register" key="customer-register" />} />
      <Route path="/brand" element={<PublicHomePage brand />} />
      <Route path="/stores/:storeNo" element={<PublicHomePage />} />
      <Route path="/stores/:storeNo/visit" element={<StorePortalPage mode="visit" />} />
      <Route path="/store-admin/:storeNo" element={<StorePortalPage mode="staff-home" />} />
      <Route path="/store-admin/:storeNo/login" element={<StorePortalPage mode="login" />} />
      <Route path="/store-admin/:storeNo/register" element={<StorePortalPage mode="register" />} />
      <Route path="/login" element={<Navigate to="/hq/login" replace />} />
      <Route path="/hq/login" element={<HeadquartersLoginRoute />} />
      <Route path="/forbidden" element={<ForbiddenPage />} />
      <Route path="/" element={<WorkspaceHomeRedirect />} />
      <Route path="/hq" element={<RequireTenantWorkspace workspace="HQ"><TenantLayout workspace="HQ" /></RequireTenantWorkspace>}>
        <Route index element={<TenantHomePage workspace="HQ" />} />
        <Route path="customers" element={<RequireTenantWorkspace workspace="HQ" permission="core.customer.view"><CustomerManagementPage /></RequireTenantWorkspace>} />
        <Route path="site" element={<RequireTenantWorkspace workspace="HQ" permission="core.site.view"><SiteContentPage /></RequireTenantWorkspace>} />
        <Route path="permissions" element={<RequireTenantWorkspace workspace="HQ"><TenantPermissionsPage /></RequireTenantWorkspace>} />
        <Route path="visits" element={<RequireTenantWorkspace workspace="HQ" permission="core.visit.view"><VisitBookingsPage /></RequireTenantWorkspace>} />
        <Route path="staff-applications" element={<RequireTenantWorkspace workspace="HQ" permission="iam.staff.view"><StaffApplicationsPage /></RequireTenantWorkspace>} />
        <Route path="staff" element={<RequireTenantWorkspace workspace="HQ" permission="iam.staff.view"><TenantStaffPage /></RequireTenantWorkspace>} />
        <Route path="stores" element={<RequireTenantWorkspace workspace="HQ" permission="core.store.view"><TenantStoresPage /></RequireTenantWorkspace>} />
        <Route path="shop" element={<RequireTenantWorkspace workspace="HQ" permission="core.module.view"><TenantShopPage /></RequireTenantWorkspace>} />
        <Route path="module-install" element={<RequireTenantWorkspace workspace="HQ" permission="core.module.view"><ModuleInstallPage /></RequireTenantWorkspace>} />
        <Route path="modules" element={<RequireTenantWorkspace workspace="HQ" permission="core.module.view"><TenantModulesPage /></RequireTenantWorkspace>} />
      </Route>
      <Route path="/store" element={<RequireTenantWorkspace workspace="STORE"><TenantLayout workspace="STORE" /></RequireTenantWorkspace>}>
        <Route index element={<StoreDashboardPage />} />
        <Route path="customers" element={<RequireTenantWorkspace workspace="STORE" permission="core.customer.view"><CustomerManagementPage /></RequireTenantWorkspace>} />
        {REGISTERED_MODULE_ROUTES.map(module => (
          <Route key={module.route} path={module.route.slice(1)} element={<RequireTenantModule moduleCode={module.moduleCode} route={module.route} />} />
        ))}
        <Route path="site" element={<RequireTenantWorkspace workspace="STORE" permission="core.site.view"><SiteContentPage /></RequireTenantWorkspace>} />
        <Route path="visits" element={<RequireTenantWorkspace workspace="STORE" permission="core.visit.view"><VisitBookingsPage /></RequireTenantWorkspace>} />
      </Route>
      <Route element={<TenantModuleLayout />}>
        <Route path="/modules/:moduleCode/*" element={<RequireTenantModule />} />
        {REGISTERED_MODULE_ROUTES.map(module => (
          <Route key={module.route} path={module.route} element={<RequireTenantModule moduleCode={module.moduleCode} route={module.route} />} />
        ))}
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default function App() {
  return <AuthProvider><TenantMenuProvider><TenantRoutes /></TenantMenuProvider></AuthProvider>
}
