const VITAL_LABEL = {
  BLOOD_PRESSURE: '血压',
  HEART_RATE: '心率',
  BLOOD_SUGAR: '血糖',
  SPO2: '血氧',
  TEMPERATURE: '体温'
}

const ALERT_LEVEL_LABEL = { RED: '紧急', YELLOW: '关注', BLUE: '提示' }
const ALERT_TYPE_LABEL = {
  SOS: '一键呼叫',
  FALL: '跌倒检测',
  GEOFENCE: '电子围栏',
  INACTIVITY: '久未活动',
  VITAL_ABNORMAL: '体征异常'
}
const ALERT_STATUS_LABEL = {
  PENDING: '待处置',
  ACKED: '已确认',
  FOLLOWED: '已回访',
  ARCHIVED: '已归档'
}
const ORDER_STATUS_LABEL = {
  PENDING_PAY: '待支付',
  PENDING_ACCEPT: '待接单',
  IN_SERVICE: '服务中',
  PENDING_REVIEW: '待评价',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUNDED: '已退款'
}

function vitalText(v) {
  if (!v) return '—'
  const label = VITAL_LABEL[v.type] || v.type
  const value = v.value2 != null ? `${v.value1}/${v.value2}` : `${v.value1}`
  return `${label} ${value}${v.unit || ''}`
}

function alertTone(level) {
  if (level === 'RED') return 'red'
  if (level === 'YELLOW') return 'yellow'
  return 'blue'
}

module.exports = {
  VITAL_LABEL,
  ALERT_LEVEL_LABEL,
  ALERT_TYPE_LABEL,
  ALERT_STATUS_LABEL,
  ORDER_STATUS_LABEL,
  vitalText,
  alertTone
}
