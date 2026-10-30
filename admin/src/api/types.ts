export interface ProviderRow {
  id: number
  name: string
  contactName: string
  contactPhone: string
  serviceArea: string
  rating: number
  auditStatus: string
  auditNote?: string
  createdAt: string
}

export interface ItemRow {
  id: number
  name: string
  category: string
  price: number
  serviceArea: string
  providerId?: number
  providerName?: string
  auditStatus: string
  status: string
}

export interface OrderView {
  id: number
  orderNo: string
  elderId: number
  elderName: string
  itemId: number
  itemName: string
  providerId?: number
  providerName?: string
  amount: number
  status: string
  appointTime?: string
  address: string
  remark?: string
  createdAt: string
  workOrderStatus?: string
  nurseId?: number
}

export interface AlertView {
  id: number
  elderId: number
  elderName: string
  type: string
  level: string
  content: string
  status: string
  handleBy?: number
  handleTime?: string
  handleNote?: string
  createdAt: string
}

export interface DashboardStats {
  elderCount: number
  familyCount: number
  providerCount: number
  orderCount: number
  todayOrderCount: number
  activeAlertCount: number
  redAlertCount: number
  totalRevenue: number
  pendingAuditCount: number
}

export const ORDER_STATUS_LABEL: Record<string, string> = {
  PENDING_PAY: '待支付',
  PENDING_ACCEPT: '待接单',
  IN_SERVICE: '服务中',
  PENDING_REVIEW: '待评价',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUNDED: '已退款'
}

export const WORK_ORDER_STATUS_LABEL: Record<string, string> = {
  DISPATCHED: '待接单',
  ACCEPTED: '已接单',
  IN_SERVICE: '服务中',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
}

export const ALERT_LEVEL_LABEL: Record<string, string> = {
  RED: '紧急',
  YELLOW: '关注',
  BLUE: '提示'
}

export const ALERT_STATUS_LABEL: Record<string, string> = {
  PENDING: '待处置',
  ACKED: '已确认',
  FOLLOWED: '已回访',
  ARCHIVED: '已归档'
}

export const ALERT_TYPE_LABEL: Record<string, string> = {
  SOS: '一键呼叫',
  FALL: '跌倒检测',
  GEOFENCE: '电子围栏',
  INACTIVITY: '长时间未活动',
  VITAL_ABNORMAL: '体征异常'
}
