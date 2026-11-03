import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'

interface UserRow {
  id: number
  phone: string
  role: string
  nickname: string
  orgId?: number
  status: string
  createdAt: string
}

const ROLE_LABEL: Record<string, string> = {
  ELDER: '老人',
  FAMILY: '家属',
  PROVIDER: '护理员',
  ORG_ADMIN: '机构管理员',
  PLATFORM_ADMIN: '平台管理员',
  SYS_ADMIN: '系统管理员'
}

export default function Users() {
  const [rows, setRows] = useState<UserRow[]>([])
  const [role, setRole] = useState('')
  const [keyword, setKeyword] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    const params = new URLSearchParams()
    if (role) params.set('role', role)
    if (keyword) params.set('keyword', keyword)
    try {
      setRows(await api.get<UserRow[]>(`/api/admin/users?${params.toString()}`))
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [role, keyword])

  useEffect(() => {
    void load()
  }, [load])

  const toggle = async (row: UserRow) => {
    const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    try {
      await api.post(`/api/admin/users/${row.id}/status`, { status: next })
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '操作失败')
    }
  }

  return (
    <div>
      <div className="card">
        <div className="row">
          <strong>用户管理</strong>
          <span className="spacer" />
          <select value={role} onChange={(e) => setRole(e.target.value)}>
            <option value="">全部角色</option>
            {Object.entries(ROLE_LABEL).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
          <input
            placeholder="手机号或昵称"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && load()}
          />
          <button onClick={load}>查询</button>
        </div>
        {error && <div className="error">{error}</div>}
      </div>

      <div className="card">
        {rows.length === 0 ? (
          <div className="empty">暂无数据</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>昵称</th>
                <th>手机号</th>
                <th>角色</th>
                <th>所属机构</th>
                <th>状态</th>
                <th>注册时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.nickname}</td>
                  <td className="muted">{r.phone}</td>
                  <td>{ROLE_LABEL[r.role] ?? r.role}</td>
                  <td className="muted">{r.orgId ?? '—'}</td>
                  <td>
                    <span className={`badge ${r.status === 'ACTIVE' ? 'green' : 'red'}`}>
                      {r.status === 'ACTIVE' ? '正常' : '已停用'}
                    </span>
                  </td>
                  <td className="muted">{r.createdAt}</td>
                  <td>
                    {r.role === 'SYS_ADMIN' ? (
                      <span className="muted">不可操作</span>
                    ) : (
                      <button className={r.status === 'ACTIVE' ? 'danger' : ''} onClick={() => toggle(r)}>
                        {r.status === 'ACTIVE' ? '停用' : '启用'}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
