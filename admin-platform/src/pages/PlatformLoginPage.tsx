import { useState, type FormEvent } from 'react'
import { Navigate } from 'react-router-dom'
import { usePlatformAuth } from '../auth/AuthProvider'

export default function PlatformLoginPage() {
  const { status, login, retry, error } = usePlatformAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [formError, setFormError] = useState('')
  const [busy, setBusy] = useState(false)

  if (status === 'authenticated') return <Navigate to="/" replace />
  if (status === 'checking') return <main className="auth-state" role="status">正在验证平台会话…</main>
  if (status === 'unavailable') return <main className="auth-state"><h1>平台服务暂不可用</h1><p>{error}</p><button onClick={retry}>重试</button></main>

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setBusy(true)
    setFormError('')
    try {
      await login(username.trim(), password)
    } catch (reason) {
      setFormError(reason instanceof Error ? reason.message : '登录失败，请稍后重试')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="login-frame">
      <section className="login-panel">
        <div className="login-heading"><span className="platform-mark">养</span><div><p>PLATFORM CONTROL</p><h1>平台总管理端登录</h1></div></div>
        <form onSubmit={event => void submit(event)}>
          <label htmlFor="platform-username">管理员账号</label>
          <input id="platform-username" autoComplete="username" value={username} onChange={event => setUsername(event.target.value)} required />
          <label htmlFor="platform-password">密码</label>
          <input id="platform-password" type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} required />
          {(formError || error) && <p className="form-error" role="alert">{formError || error}</p>}
          <button className="primary-action" type="submit" disabled={busy}>{busy ? '正在登录…' : '登录'}</button>
        </form>
        <footer>平台控制面 · 仅限平台授权管理员</footer>
      </section>
    </main>
  )
}
