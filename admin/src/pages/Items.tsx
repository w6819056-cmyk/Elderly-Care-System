import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import { ItemRow } from '../api/types'

const AUDIT_LABEL: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

const CATEGORY_LABEL: Record<string, string> = {
  HOUSEKEEPING: '居家保洁',
  NURSING: '上门护理',
  MEAL: '助餐',
  ESCORT: '陪同就医',
  MEDICAL: '医疗服务'
}

export default function Items() {
  const [rows, setRows] = useState<ItemRow[]>([])
  const [filter, setFilter] = useState('PENDING')
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      const query = filter ? `?auditStatus=${filter}` : ''
      setRows(await api.get<ItemRow[]>(`/api/admin/items${query}`))
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [filter])

  useEffect(() => {
    void load()
  }, [load])

  const audit = async (row: ItemRow, decision: 'APPROVE' | 'REJECT') => {
    const note = window.prompt('审核意见', '') ?? ''
    try {
      await api.post(`/api/admin/items/${row.id}/audit`, { decision, note })
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '审核失败')
    }
  }

  return (
    <div>
      <div className="card">
        <div className="row">
          <strong>服务项目审核</strong>
          <span className="spacer" />
          <span className="muted">未审核通过的项目不会出现在老人端服务商城</span>
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
                <th>项目名称</th>
                <th>分类</th>
                <th>价格（元）</th>
                <th>服务区域</th>
                <th>服务商</th>
                <th>审核状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.name}</td>
                  <td>{CATEGORY_LABEL[r.category] ?? r.category}</td>
                  <td>{r.price.toFixed(2)}</td>
                  <td>{r.serviceArea}</td>
                  <td>{r.providerName ?? <span className="muted">平台自营</span>}</td>
                  <td>
                    <span className={`badge ${r.auditStatus === 'APPROVED' ? 'green' : r.auditStatus === 'REJECTED' ? 'red' : 'yellow'}`}>
                      {AUDIT_LABEL[r.auditStatus] ?? r.auditStatus}
                    </span>
                  </td>
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
