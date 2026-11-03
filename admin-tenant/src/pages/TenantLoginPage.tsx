import { useState, type FormEvent } from 'react'
import { useTenantAuth } from '../auth/AuthProvider'

export default function TenantLoginPage() {
  const { loginHq } = useTenantAuth()
  const [account, setAccount] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      await loginHq(account.trim(), password)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '登录失败，请检查账号和密码')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="tenant-login-page"><aside className="login-story"><img src="/images/enterprise-welcome.png" alt="长者相伴饮茶，AI 生成配图" /><div><h2>让陪伴，成为日常</h2><p>以专业服务，守护每一段温暖时光。</p></div></aside>
      <section className="tenant-login-panel">
        <div className="tenant-login-brand"><span className="tenant-mark">总</span><div><p>HEADQUARTERS WORKSPACE</p><h1>企业总部登录</h1></div></div>
        <p className="login-intro">使用企业总部管理员账号，进入总部管理工作区。</p>
        <form onSubmit={event => void submit(event)}>
          <label htmlFor="tenant-account">总部员工账号</label>
          <input id="tenant-account" placeholder="请输入总部员工账号" autoComplete="username" value={account} onChange={event => setAccount(event.target.value)} required />
          <label htmlFor="tenant-password">密码</label>
          <input id="tenant-password" type="password" placeholder="请输入登录密码" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} required />
          {error && <p className="tenant-error" role="alert">{error}</p>}
          <button className="tenant-primary-action" type="submit" disabled={busy}>{busy ? '正在登录…' : '登录'}</button>
        </form>
        <footer>企业总部管理端 · 仅限总部员工</footer>
      </section>
    </main>
  )
}
