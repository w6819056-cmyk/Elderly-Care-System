import { useEffect, useState } from 'react'
import { api, getUser } from '../api/client'
import { ALERT_LEVEL_LABEL, ALERT_STATUS_LABEL, ALERT_TYPE_LABEL, AlertView, DashboardStats } from '../api/types'

export default function Dashboard() {
  const user = getUser()
  const isAdmin = user?.role === 'PLATFORM_ADMIN' || user?.role === 'SYS_ADMIN'
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [alerts, setAlerts] = useState<AlertView[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    api
      .get<DashboardStats>('/api/admin/dashboard')
      .then(setStats)
      .catch((e) => setError(e.message))
    api
      .get<AlertView[]>('/api/alerts?limit=8')
      .then(setAlerts)
      .catch(() => undefined)
  }, [])

  return (
    <div>
      {error && <div className="card error">{error}</div>}

      <div className="stats">
        <div className="stat">
          <div className="label">老人用户</div>
          <div className="value">{stats?.elderCount ?? '—'}</div>
        </div>
        <div className="stat">
          <div className="label">家属用户</div>
          <div className="value">{stats?.familyCount ?? '—'}</div>
        </div>
        <div className="stat">
          <div className="label">在营服务商</div>
          <div className="value">{stats?.providerCount ?? '—'}</div>
        </div>
        <div className="stat">
          <div className="label">订单总数</div>
          <div className="value">{stats?.orderCount ?? '—'}</div>
        </div>
        <div className="stat">
          <div className="label">今日新增订单</div>
          <div className="value">{stats?.todayOrderCount ?? '—'}</div>
        </div>
        <div className="stat alert">
          <div className="label">未归档告警</div>
          <div className="value">{stats?.activeAlertCount ?? '—'}</div>
        </div>
        <div className="stat">
          <div className="label">待审核事项</div>
          <div className="value">{stats?.pendingAuditCount ?? '—'}</div>
        </div>
        <div className="stat">
          <div className="label">已收款金额（元）</div>
          <div className="value">{stats ? stats.totalRevenue.toFixed(2) : '—'}</div>
        </div>
      </div>

      <div className="card" style={{ marginTop: 16 }}>
        <div className="row" style={{ marginBottom: 10 }}>
          <strong>最新告警</strong>
          <span className="spacer" />
          <span className="muted">
            {isAdmin ? '红色告警需优先处置，处置流程：确认 → 回访 → 归档' : '仅展示与本机构服务老人相关的告警'}
          </span>
        </div>
        {alerts.length === 0 ? (
          <div className="empty">暂无告警数据</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>老人</th>
                <th>类型</th>
                <th>级别</th>
                <th>内容</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              {alerts.map((a) => (
                <tr key={a.id}>
                  <td className="muted">{a.createdAt}</td>
                  <td>{a.elderName}</td>
                  <td>{ALERT_TYPE_LABEL[a.type] ?? a.type}</td>
                  <td>
                    <span className={`badge ${a.level === 'RED' ? 'red' : a.level === 'YELLOW' ? 'yellow' : 'blue'}`}>
                      {ALERT_LEVEL_LABEL[a.level] ?? a.level}
                    </span>
                  </td>
                  <td>{a.content}</td>
                  <td>
                    <span className="badge gray">{ALERT_STATUS_LABEL[a.status] ?? a.status}</span>
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
