import { FormEvent, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { clearTenantSession, getTenantToken, tenantLogin } from '../../api/tenantModules'

export default function TenantLogin() {
  const navigate = useNavigate()
  const [account, setAccount] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  if (getTenantToken()) return <Navigate to="/hq/modules" replace />

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      const principal = await tenantLogin(account, password)
      if (principal.staffType !== 'HQ') {
        clearTenantSession()
        throw new Error('该入口仅供企业总部管理员使用')
      }
      navigate('/hq/modules', { replace: true })
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '登录失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="tenant-login-page">
      <form className="tenant-login" onSubmit={submit}>
        <h1>企业总部</h1>
        <p>总部管理工作区</p>
        <label htmlFor="account">账号</label>
        <input id="account" autoComplete="username" value={account} onChange={event => setAccount(event.target.value)} required />
        <label htmlFor="password">密码</label>
        <input id="password" type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} required />
        {error && <div className="tenant-error" role="alert">{error}</div>}
        <button className="primary" disabled={busy}>{busy ? '登录中…' : '登录'}</button>
      </form>
    </main>
  )
}
