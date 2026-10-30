const { api } = require('../../utils/request')
const { vitalText, ALERT_LEVEL_LABEL, ALERT_TYPE_LABEL, alertTone } = require('../../utils/format')

Page({
  data: {
    user: null,
    elderId: null,
    elderName: '',
    vitals: [],
    medicationCount: 0,
    pendingBindings: 0,
    bigText: false,
    highContrast: false,
    sosCountdown: 0,
    loading: false
  },

  onShow() {
    const app = getApp()
    const user = app.globalData.user
    if (!user || user.role !== 'ELDER') {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    const bigText = wx.getStorageSync('ec_big_text') || false
    const highContrast = wx.getStorageSync('ec_high_contrast') || false
    this.setData({ user, elderId: user.elderId, bigText, highContrast })
    this.loadData()
  },

  async loadData() {
    const { elderId } = this.data
    if (!elderId) return
    this.setData({ loading: true })
    try {
      // 首页只取最新体征与用药概况，避免拉取全量历史
      const [archive, vitals, medications, bindings] = await Promise.all([
        api.get(`/api/health/elders/${elderId}/archive`, { silent: true }).catch(() => null),
        api.get(`/api/health/elders/${elderId}/vitals?limit=5`, { silent: true }),
        api.get(`/api/health/elders/${elderId}/medications`, { silent: true }),
        api.get('/api/family/bindings/pending', { silent: true }).catch(() => [])
      ])
      const pending = (bindings || []).filter((b) => b.status === 'PENDING')
      this.setData({
        elderName: archive && archive.bloodType ? `血型 ${archive.bloodType}` : '档案待完善',
        vitals: (vitals || []).map((v) => ({ ...v, text: vitalText(v) })),
        medicationCount: (medications || []).length,
        pendingBindings: pending.length
      })
    } catch (err) {
      // 单接口失败不阻塞首页
    } finally {
      this.setData({ loading: false })
    }
  },

  /** SOS：二次确认后触发，10 秒内推送家属与平台（SA-01/SA-04） */
  triggerSos() {
    wx.showModal({
      title: '紧急呼叫',
      content: '将立即通知家属与平台客服，确定要呼叫吗？',
      confirmText: '立即呼叫',
      confirmColor: '#d92d20',
      success: async (res) => {
        if (!res.confirm) return
        this.setData({ sosCountdown: 10 })
        this.startCountdown()
        try {
          const alert = await api.post('/api/alerts/sos', { note: '老人端一键呼叫' })
          wx.showToast({ title: `已通知家属（${ALERT_LEVEL_LABEL[alert.level]}）`, icon: 'success', duration: 2500 })
        } catch (err) {
          wx.showToast({ title: '呼叫失败，请直接拨打 120', icon: 'none', duration: 3000 })
        }
      }
    })
  },

  startCountdown() {
    this.timer = setInterval(() => {
      const next = this.data.sosCountdown - 1
      if (next <= 0) {
        clearInterval(this.timer)
        this.setData({ sosCountdown: 0 })
      } else {
        this.setData({ sosCountdown: next })
      }
    }, 1000)
  },

  onUnload() {
    if (this.timer) clearInterval(this.timer)
  },

  toggleBigText(e) {
    const bigText = e.detail.value
    wx.setStorageSync('ec_big_text', bigText)
    this.setData({ bigText })
  },

  toggleContrast(e) {
    const highContrast = e.detail.value
    wx.setStorageSync('ec_high_contrast', highContrast)
    this.setData({ highContrast })
  },

  goVitals() {
    wx.switchTab({ url: '/pages/vitals/vitals' })
  },

  goOrders() {
    wx.switchTab({ url: '/pages/orders/orders' })
  },

  async reviewBinding(e) {
    const { id, accept } = e.currentTarget.dataset
    try {
      await api.post(`/api/family/bindings/${id}/decide?accept=${accept === 'true'}`, {})
      wx.showToast({ title: accept === 'true' ? '已同意绑定' : '已拒绝', icon: 'success' })
      this.loadData()
    } catch (err) {
      // 已提示
    }
  }
})
