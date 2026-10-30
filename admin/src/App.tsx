import { Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import AdminOrders from './pages/AdminOrders'
import ProviderOrders from './pages/ProviderOrders'
import Alerts from './pages/Alerts'
import Providers from './pages/Providers'
import Items from './pages/Items'
import Users from './pages/Users'
import { getUser } from './api/client'

function RequireAuth({ children }: { children: JSX.Element }) {
  const user = getUser()
  if (!user) {
    return <Navigate to="/login" replace />
  }
  return children
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<Dashboard />} />
        <Route path="orders" element={<AdminOrders />} />
        <Route path="work-orders" element={<ProviderOrders />} />
        <Route path="alerts" element={<Alerts />} />
        <Route path="providers" element={<Providers />} />
        <Route path="items" element={<Items />} />
        <Route path="users" element={<Users />} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  )
}
