const { api } = require('../../utils/request')
const {
  vitalText,
  ALERT_LEVEL_LABEL,
  ALERT_TYPE_LABEL,
  ALERT_STATUS_LABEL,
  ORDER_STATUS_LABEL,
  alertTone
} = require('../../utils/format')

Page({
  data: {
    role: '',
    elders: [],
    currentElderId: null,
    snapshot: null,
    bindElderId: '',
    bindRelation: '儿子',
    myBindings: [],
    refreshing: false
  },

  onShow() {
    const app = getApp()
    const user = app.globalData.user
    if (!user) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.setData({ role: user.role })
    if (user.role === 'FAMILY') {
      this.loadElders()
    } else {
      this.loadMyBindings()
    }
  },

  async loadElders() {
    try {
      const elders = await api.get('/api/family/elders', { silent: true })
      this.setData({ elders: elders || [] })
      if (!elders || elders.length === 0) {
        this.setData({ snapshot: null })
        return
      }
      const current =
        this.data.currentElderId && elders.some((e) => e.id === this.data.currentElderId)
          ? this.data.currentElderId
          : elders[0].id
      this.setData({ currentElderId: current })
      this.loadSnapshot()
    } catch (err) {
      // 已提示
    }
  },

  async loadSnapshot() {
    const { currentElderId } = this.data
    if (!currentElderId) return
    this.setData({ refreshing: true })
    try {
      const snap = await api.get(`/api/family/elders/${currentElderId}/snapshot`, { silent: true })
      this.setData({
        snapshot: {
          ...snap,
          vitals: (snap.latestVitals || []).map((v) => ({ ...v, text: vitalText(v) })),
          alerts: (snap.activeAlerts || []).map((a) => ({
            ...a,
            levelLabel: ALERT_LEVEL_LABEL[a.level] || a.level,
            typeLabel: ALERT_TYPE_LABEL[a.type] || a.type,
            statusLabel: ALERT_STATUS_LABEL[a.status] || a.status,
            tone: alertTone(a.level)
          })),
          orders: (snap.activeOrders || []).map((o) => ({
            ...o,
            statusLabel: ORDER_STATUS_LABEL[o.status] || o.status,
            amountText: Number(o.amount).toFixed(2)
          }))
        }
      })
    } catch (err) {
      // 已提示
    } finally {
      this.setData({ refreshing: false })
    }
  },

  switchElder(e) {
    this.setData({ currentElderId: e.currentTarget.dataset.id })
    this.loadSnapshot()
  },

  refresh() {
    this.loadSnapshot()
  },

  onBindElderId(e) {
    this.setData({ bindElderId: e.detail.value })
  },
  onBindRelation(e) {
    this.setData({ bindRelation: e.detail.value })
  },

  async submitBind() {
    const { bindElderId, bindRelation } = this.data
    if (!bindElderId) {
      wx.showToast({ title: '请输入老人 ID', icon: 'none' })
      return
    }
    try {
      await api.post('/api/family/bindings', { elderId: bindElderId, relation: bindRelation })
      wx.showModal({
        title: '已提交申请',
        content: '请让老人在老人端「家人」页确认后生效',
        showCancel: false
      })
      this.setData({ bindElderId: '' })
    } catch (err) {
      // 已提示
    }
  },

  async loadMyBindings() {
    try {
      const bindings = await api.get('/api/family/bindings/pending', { silent: true })
      this.setData({
        myBindings: (bindings || []).filter((b) => b.status === 'PENDING')
      })
    } catch (err) {
      // 已提示
    }
  },

  async decide(e) {
    const { id, accept } = e.currentTarget.dataset
    try {
      await api.post(`/api/family/bindings/${id}/decide?accept=${accept === 'true'}`, {})
      wx.showToast({ title: accept === 'true' ? '已同意' : '已拒绝', icon: 'success' })
      this.loadMyBindings()
    } catch (err) {
      // 已提示
    }
  },

  async handleAlert(e) {
    const { id } = e.currentTarget.dataset
    wx.showModal({
      title: '确认已知晓该告警',
      editable: true,
      placeholderText: '补充说明（可选）',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await api.post(`/api/alerts/${id}/handle`, { action: 'ACK', note: res.content || '家属已确认' })
          wx.showToast({ title: '已确认', icon: 'success' })
          this.loadSnapshot()
        } catch (err) {
          // 已提示
        }
      }
    })
  }
})
