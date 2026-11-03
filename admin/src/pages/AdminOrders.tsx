import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import { ORDER_STATUS_LABEL, OrderView, ProviderRow } from '../api/types'

export default function AdminOrders() {
  const [orders, setOrders] = useState<OrderView[]>([])
  const [providers, setProviders] = useState<ProviderRow[]>([])
  const [status, setStatus] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      const query = status ? `?status=${status}` : ''
      const [orderList, providerList] = await Promise.all([
        api.get<OrderView[]>(`/api/admin/orders${query}`),
        api.get<ProviderRow[]>('/api/admin/providers?auditStatus=APPROVED')
      ])
      setOrders(orderList)
      setProviders(providerList)
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [status])

  useEffect(() => {
    void load()
  }, [load])

  const dispatchTo = async (orderId: number, providerId: number) => {
    setBusy(true)
    try {
      await api.post(`/api/admin/orders/${orderId}/dispatch`, { providerId })
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '改派失败')
    } finally {
      setBusy(false)
    }
  }

  const refund = async (orderId: number) => {
    const reason = window.prompt('请输入退款原因', '用户申请取消')
    if (reason === null) return
    setBusy(true)
    try {
      await api.post(`/api/admin/orders/${orderId}/refund`, { reason })
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '退款失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <div className="card">
        <div className="row">
          <strong>订单监控</strong>
          <span className="spacer" />
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">全部状态</option>
            {Object.entries(ORDER_STATUS_LABEL).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
          <button onClick={load}>刷新</button>
        </div>
        {error && <div className="error">{error}</div>}
      </div>

      <div className="card">
        {orders.length === 0 ? (
          <div className="empty">暂无订单</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>订单号</th>
                <th>老人</th>
                <th>服务项目</th>
                <th>金额</th>
                <th>状态</th>
                <th>服务商</th>
                <th>工单</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => (
                <tr key={o.id}>
                  <td>{o.orderNo}</td>
                  <td>{o.elderName}</td>
                  <td>{o.itemName}</td>
                  <td>{o.amount.toFixed(2)}</td>
                  <td>
                    <span className={`badge ${o.status === 'COMPLETED' ? 'green' : o.status === 'REFUNDED' || o.status === 'CANCELLED' ? 'red' : 'blue'}`}>
                      {ORDER_STATUS_LABEL[o.status] ?? o.status}
                    </span>
                  </td>
                  <td>{o.providerName ?? <span className="muted">待人工派单</span>}</td>
                  <td className="muted">{o.workOrderStatus ?? '—'}</td>
                  <td className="muted">{o.createdAt}</td>
                  <td>
                    <div className="row">
                      {o.status === 'PENDING_ACCEPT' && (
                        <select
                          disabled={busy}
                          value=""
                          onChange={(e) => {
                            const pid = Number(e.target.value)
                            if (pid) void dispatchTo(o.id, pid)
                          }}
                        >
                          <option value="">改派…</option>
                          {providers.map((p) => (
                            <option key={p.id} value={p.id}>
                              {p.name}
                            </option>
                          ))}
                        </select>
                      )}
                      {(o.status === 'PENDING_ACCEPT' || o.status === 'IN_SERVICE') && (
                        <button className="danger" disabled={busy} onClick={() => refund(o.id)}>
                          退款
                        </button>
                      )}
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
