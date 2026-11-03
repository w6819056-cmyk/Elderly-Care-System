import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import { ORDER_STATUS_LABEL, OrderView, WORK_ORDER_STATUS_LABEL } from '../api/types'

export default function ProviderOrders() {
  const [orders, setOrders] = useState<OrderView[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState<number | null>(null)

  const load = useCallback(async () => {
    try {
      setOrders(await api.get<OrderView[]>('/api/provider/orders'))
      setError('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const act = async (order: OrderView, path: string, payload?: unknown) => {
    setBusy(order.id)
    try {
      // 后端打卡/接单接口以 workOrderId 为标识，这里按订单反查工单
      const detail = await api.get<{ workOrder: { id: number } }>(`/api/provider/orders/${order.id}/work-order`)
      const workOrderId = detail.workOrder.id
      await api.post(`/api/provider/work-orders/${workOrderId}/${path}`, payload)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '操作失败')
    } finally {
      setBusy(null)
    }
  }

  return (
    <div>
      <div className="card">
        <div className="row">
          <strong>我的工单</strong>
          <span className="spacer" />
          <span className="muted">接单 → 开始打卡 → 结束打卡，结束后进入待评价并生成结算流水</span>
          <button onClick={load}>刷新</button>
        </div>
        {error && <div className="error">{error}</div>}
      </div>

      <div className="card">
        {orders.length === 0 ? (
          <div className="empty">暂无工单</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>订单号</th>
                <th>老人</th>
                <th>服务项目</th>
                <th>预约时间</th>
                <th>地址</th>
                <th>订单状态</th>
                <th>工单状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => {
                const disabled = busy === o.id
                return (
                  <tr key={o.id}>
                    <td>{o.orderNo}</td>
                    <td>{o.elderName}</td>
                    <td>{o.itemName}</td>
                    <td className="muted">{o.appointTime ?? '立即服务'}</td>
                    <td className="muted">{o.address}</td>
                    <td>
                      <span className="badge blue">{ORDER_STATUS_LABEL[o.status] ?? o.status}</span>
                    </td>
                    <td>
                      <span className={`badge ${o.workOrderStatus === 'COMPLETED' ? 'green' : 'gray'}`}>
                        {o.workOrderStatus ? WORK_ORDER_STATUS_LABEL[o.workOrderStatus] ?? o.workOrderStatus : '—'}
                      </span>
                    </td>
                    <td>
                      <div className="row">
                        {o.workOrderStatus === 'DISPATCHED' && (
                          <button className="primary" disabled={disabled} onClick={() => act(o, 'accept', { nurseId: 6 })}>
                            接单
                          </button>
                        )}
                        {o.workOrderStatus === 'ACCEPTED' && (
                          <button
                            className="primary"
                            disabled={disabled}
                            onClick={() => act(o, 'checkin?action=START', { lng: 116.481, lat: 39.921 })}
                          >
                            开始服务打卡
                          </button>
                        )}
                        {o.workOrderStatus === 'IN_SERVICE' && (
                          <button
                            className="primary"
                            disabled={disabled}
                            onClick={() =>
                              act(o, 'checkin?action=END', {
                                lng: 116.481,
                                lat: 39.921,
                                note: '服务完成，已向家属反馈'
                              })
                            }
                          >
                            结束服务打卡
                          </button>
                        )}
                        {o.workOrderStatus === 'COMPLETED' && <span className="muted">待结算</span>}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
