import { Link, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, usePlatformAuth } from './auth/AuthProvider'
import PlatformLayout from './components/PlatformLayout'
import ForbiddenPage from './pages/ForbiddenPage'
import PlatformHomePage from './pages/PlatformHomePage'
import PlatformLoginPage from './pages/PlatformLoginPage'

function RequirePlatformAuth({ children }: { children: JSX.Element }) {
  const auth = usePlatformAuth()
  if (auth.status === 'checking') return <main className="auth-state" role="status">正在验证平台会话…</main>
  if (auth.status === 'anonymous') return <Navigate to="/login" replace />
  if (auth.status === 'unavailable') return <main className="auth-state" role="alert"><h1>平台服务暂不可用</h1><p>{auth.error}</p><button onClick={auth.retry}>重试</button></main>
  return children
}

function LoginRoute() {
  const auth = usePlatformAuth()
  if (auth.status === 'authenticated') return <Navigate to="/" replace />
  return <PlatformLoginPage />
}

function NotFoundPage() {
  return <main className="auth-state"><h1>页面不存在</h1><Link to="/">返回平台工作台</Link></main>
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginRoute />} />
      <Route path="/forbidden" element={<ForbiddenPage />} />
      <Route path="/" element={<RequirePlatformAuth><PlatformLayout /></RequirePlatformAuth>}>
        <Route index element={<PlatformHomePage />} />
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default function App() {
  return <AuthProvider><AppRoutes /></AuthProvider>
}
