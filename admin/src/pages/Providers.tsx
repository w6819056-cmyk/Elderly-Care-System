import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import { ProviderRow } from '../api/types'

const AUDIT_LABEL: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

export default function Providers() {
  const [rows, setRows] = useState<ProviderRow[]>([])
  const [filter, setFilter] = useState('PENDING')
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      const query = filter ? `?auditStatus=${filter}` : ''
      setRows(await api.get<ProviderRow[]>(`/api/admin/providers${query}`))
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [filter])

  useEffect(() => {
    void load()
  }, [load])

  const audit = async (row: ProviderRow, decision: 'APPROVE' | 'REJECT') => {
    const note = window.prompt(decision === 'APPROVE' ? '请输入通过意见' : '请输入驳回原因', '') ?? ''
    try {
      await api.post(`/api/admin/providers/${row.id}/audit`, { decision, note })
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '审核失败')
    }
  }

  return (
    <div>
      <div className="card">
        <div className="row">
          <strong>服务商入驻审核</strong>
          <span className="spacer" />
          <select value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="">全部</option>
            <option value="PENDING">待审核</option>
            <option value="APPROVED">已通过</option>
            <option value="REJECTED">已驳回</option>
          </select>
          <button onClick={load}>刷新</button>
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
                <th>机构名称</th>
                <th>联系人</th>
                <th>联系电话</th>
                <th>服务区域</th>
                <th>评分</th>
                <th>审核状态</th>
                <th>审核意见</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.name}</td>
                  <td>{r.contactName}</td>
                  <td className="muted">{r.contactPhone}</td>
                  <td>{r.serviceArea}</td>
                  <td>{r.rating}</td>
                  <td>
                    <span className={`badge ${r.auditStatus === 'APPROVED' ? 'green' : r.auditStatus === 'REJECTED' ? 'red' : 'yellow'}`}>
                      {AUDIT_LABEL[r.auditStatus] ?? r.auditStatus}
                    </span>
                  </td>
                  <td className="muted">{r.auditNote ?? '—'}</td>
                  <td>
                    {r.auditStatus === 'PENDING' ? (
                      <div className="row">
                        <button className="primary" onClick={() => audit(r, 'APPROVE')}>
                          通过
                        </button>
                        <button className="danger" onClick={() => audit(r, 'REJECT')}>
                          驳回
                        </button>
                      </div>
                    ) : (
                      <span className="muted">已处理</span>
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
