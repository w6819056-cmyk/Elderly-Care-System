import { FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError, CurrentUser, setSession } from '../api/client'

interface LoginResponse {
  token: string
  user: CurrentUser
}

export default function Login() {
  const navigate = useNavigate()
  const [phone, setPhone] = useState('13700000001')
  const [password, setPassword] = useState('888888')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const data = await api.post<LoginResponse>('/api/auth/login', { phone, password })
      if (data.user.role !== 'PLATFORM_ADMIN' && data.user.role !== 'SYS_ADMIN' &&
          data.user.role !== 'ORG_ADMIN' && data.user.role !== 'PROVIDER') {
        setError('该账号不是管理端账号，请使用小程序端登录')
        return
      }
      setSession(data.token, data.user)
      navigate('/dashboard', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '登录失败，请检查后端服务是否已启动')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <h1>综合智慧养老平台 · 管理端</h1>
        <p className="hint">
          机构/服务商端与平台运营端共用本入口。
          <br />
          演示账号：平台 13700000001 · 机构 13900000001，密码 888888
        </p>
        <label>手机号</label>
        <input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="请输入手机号" />
        <label>密码</label>
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="请输入密码"
        />
        {error && <div className="error">{error}</div>}
        <button className="primary" type="submit" disabled={loading}>
          {loading ? '登录中…' : '登录'}
        </button>
      </form>
    </div>
  )
}
