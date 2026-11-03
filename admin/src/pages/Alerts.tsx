import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import { ALERT_LEVEL_LABEL, ALERT_STATUS_LABEL, ALERT_TYPE_LABEL, AlertView } from '../api/types'

const NEXT_ACTION: Record<string, { action: string; label: string }[]> = {
  PENDING: [{ action: 'ACK', label: '确认处置' }],
  ACKED: [
    { action: 'FOLLOW', label: '记录回访' },
    { action: 'ARCHIVE', label: '直接归档' }
  ],
  FOLLOWED: [{ action: 'ARCHIVE', label: '归档' }],
  ARCHIVED: []
}

export default function Alerts() {
  const [alerts, setAlerts] = useState<AlertView[]>([])
  const [level, setLevel] = useState('')
  const [status, setStatus] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    const params = new URLSearchParams({ limit: '100' })
    if (level) params.set('level', level)
    if (status) params.set('status', status)
    try {
      setAlerts(await api.get<AlertView[]>(`/api/alerts?${params.toString()}`))
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [level, status])

  useEffect(() => {
    void load()
  }, [load])

  const handle = async (alert: AlertView, action: string) => {
    const note = window.prompt('请输入处置说明（可留空）', '') ?? ''
    try {
      await api.post(`/api/alerts/${alert.id}/handle`, { action, note })
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '处置失败')
    }
  }

  return (
    <div>
      <div className="card">
        <div className="row">
          <strong>告警工作台</strong>
          <span className="spacer" />
          <select value={level} onChange={(e) => setLevel(e.target.value)}>
            <option value="">全部级别</option>
            <option value="RED">紧急</option>
            <option value="YELLOW">关注</option>
            <option value="BLUE">提示</option>
          </select>
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">全部状态</option>
            <option value="PENDING">待处置</option>
            <option value="ACKED">已确认</option>
            <option value="FOLLOWED">已回访</option>
            <option value="ARCHIVED">已归档</option>
          </select>
          <button onClick={load}>刷新</button>
        </div>
        {error && <div className="error">{error}</div>}
      </div>

      <div className="card">
        {alerts.length === 0 ? (
          <div className="empty">暂无告警</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>触发时间</th>
                <th>老人</th>
                <th>类型</th>
                <th>级别</th>
                <th>内容</th>
                <th>状态</th>
                <th>处置说明</th>
                <th>操作</th>
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
                  <td className="muted">{a.handleNote ?? '—'}</td>
                  <td>
                    <div className="row">
                      {(NEXT_ACTION[a.status] ?? []).map((na) => (
                        <button key={na.action} className="primary" onClick={() => handle(a, na.action)}>
                          {na.label}
                        </button>
                      ))}
                      {a.status === 'ARCHIVED' && <span className="muted">已闭环</span>}
                    </div>
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
