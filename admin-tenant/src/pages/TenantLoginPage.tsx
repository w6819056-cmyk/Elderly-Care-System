import { useState, type FormEvent } from 'react'
import { useTenantAuth } from '../auth/AuthProvider'

export default function TenantLoginPage() {
  const { login } = useTenantAuth()
  const [account, setAccount] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      await login(account.trim(), password)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '登录失败，请检查账号和密码')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="tenant-login-page">
      <section className="tenant-login-panel">
        <div className="tenant-login-brand"><span className="tenant-mark">企</span><div><p>ENTERPRISE WORKSPACE</p><h1>企业员工登录</h1></div></div>
        <form onSubmit={event => void submit(event)}>
          <label htmlFor="tenant-account">员工账号</label>
          <input id="tenant-account" autoComplete="username" value={account} onChange={event => setAccount(event.target.value)} required />
          <label htmlFor="tenant-password">密码</label>
          <input id="tenant-password" type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} required />
          {error && <p className="tenant-error" role="alert">{error}</p>}
          <button className="tenant-primary-action" type="submit" disabled={busy}>{busy ? '正在登录…' : '登录'}</button>
        </form>
        <footer>企业服务 · 总部与门店统一员工入口</footer>
      </section>
    </main>
  )
}
