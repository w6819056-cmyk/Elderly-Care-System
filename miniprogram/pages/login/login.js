const { api } = require('../../utils/request')

Page({
  data: {
    phone: '13800000001',
    password: '888888',
    loading: false,
    demoHints: [
      { label: '老人端', phone: '13800000001' },
      { label: '家属端', phone: '13800000002' }
    ]
  },

  onPhoneInput(e) {
    this.setData({ phone: e.detail.value })
  },

  onPasswordInput(e) {
    this.setData({ password: e.detail.value })
  },

  useDemo(e) {
    this.setData({ phone: e.currentTarget.dataset.phone })
  },

  async login() {
    const { phone, password } = this.data
    if (!phone || !password) {
      wx.showToast({ title: '请输入手机号和密码', icon: 'none' })
      return
    }
    this.setData({ loading: true })
    try {
      const data = await api.post('/api/auth/login', { phone, password })
      const app = getApp()
      app.globalData.token = data.token
      app.globalData.user = data.user
      wx.setStorageSync('ec_token', data.token)
      wx.setStorageSync('ec_user', data.user)

      if (data.user.role === 'ELDER') {
        wx.switchTab({ url: '/pages/elder/elder' })
      } else if (data.user.role === 'FAMILY') {
        wx.switchTab({ url: '/pages/family/family' })
      } else {
        wx.showToast({ title: '请使用管理端网页登录', icon: 'none', duration: 2500 })
      }
    } catch (err) {
      // 错误提示已在 request 中统一处理
    } finally {
      this.setData({ loading: false })
    }
  },

  async register() {
    const { phone, password } = this.data
    if (!phone || !password) {
      wx.showToast({ title: '请输入手机号和密码', icon: 'none' })
      return
    }
    try {
      const data = await api.post('/api/auth/register', {
        phone,
        password,
        nickname: '老人' + phone.slice(-4),
        role: 'ELDER'
      })
      const app = getApp()
      app.globalData.token = data.token
      app.globalData.user = data.user
      wx.setStorageSync('ec_token', data.token)
      wx.setStorageSync('ec_user', data.user)
      wx.switchTab({ url: '/pages/elder/elder' })
    } catch (err) {
      // 已提示
    }
  }
})
