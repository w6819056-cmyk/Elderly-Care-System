const { api } = require('../../utils/request')
const { ORDER_STATUS_LABEL } = require('../../utils/format')

const CATEGORY_LABEL = {
  HOUSEKEEPING: '居家保洁',
  NURSING: '上门护理',
  MEAL: '助餐',
  ESCORT: '陪同就医',
  MEDICAL: '医疗服务'
}

Page({
  data: {
    tab: 'mall',
    items: [],
    orders: [],
    elderId: null,
    role: '',
    selectedItem: null,
    address: '',
    remark: '',
    appointTime: ''
  },

  onShow() {
    const app = getApp()
    const user = app.globalData.user
    if (!user) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.setData({ role: user.role, elderId: user.elderId || null })
    this.loadItems()
    if (user.role === 'ELDER') {
      this.loadOrders()
    } else {
      // 家属：代下单需指定老人，默认取第一个已绑定老人
      this.loadFamilyElder()
    }
  },

  async loadFamilyElder() {
    try {
      const elders = await api.get('/api/family/elders', { silent: true })
      if (elders && elders.length > 0) {
        this.setData({ elderId: elders[0].id, elderName: elders[0].name })
      } else {
        this.setData({ elderId: null })
      }
    } catch (err) {
      // 已提示
    }
  },

  switchTab(e) {
    const tab = e.currentTarget.dataset.tab
    this.setData({ tab })
    if (tab === 'orders') {
      this.loadOrders()
    }
  },

  async loadItems() {
    try {
      const items = await api.get('/api/public/services', { silent: true })
      this.setData({
        items: (items || []).map((i) => ({ ...i, categoryLabel: CATEGORY_LABEL[i.category] || i.category }))
      })
    } catch (err) {
      // 已提示
    }
  },

  async loadOrders() {
    try {
      const orders = await api.get('/api/my/orders', { silent: true })
      this.setData({
        orders: (orders || []).map((o) => ({
          ...o,
          statusLabel: ORDER_STATUS_LABEL[o.status] || o.status,
          amountText: Number(o.amount).toFixed(2)
        }))
      })
    } catch (err) {
      // 已提示
    }
  },

  chooseItem(e) {
    const item = this.data.items[e.currentTarget.dataset.index]
    this.setData({ selectedItem: item, address: '', remark: '', appointTime: '' })
  },

  noop() {},

  closeSheet() {
    this.setData({ selectedItem: null })
  },

  onAddress(e) {
    this.setData({ address: e.detail.value })
  },
  onRemark(e) {
    this.setData({ remark: e.detail.value })
  },
  onAppointTime(e) {
    this.setData({ appointTime: e.detail.value })
  },

  async submitOrder() {
    const { selectedItem, address, remark, appointTime, role, elderId } = this.data
    if (!address) {
      wx.showToast({ title: '请填写服务地址', icon: 'none' })
      return
    }
    if (role === 'FAMILY' && !elderId) {
      wx.showToast({ title: '尚未绑定老人，请先到「家人」页绑定', icon: 'none', duration: 2500 })
      return
    }
    const payload = { itemId: selectedItem.id, address, remark }
    if (appointTime) {
      payload.appointTime = appointTime.replace('T', ' ') + ':00'
    }
    const url = role === 'FAMILY' ? `/api/orders?elderId=${elderId}` : '/api/orders'
    try {
      const order = await api.post(url, payload)
      this.setData({ selectedItem: null })
      wx.showModal({
        title: '下单成功',
        content: `订单号 ${order.orderNo}，金额 ¥${Number(order.amount).toFixed(2)}，是否立即模拟支付？`,
        confirmText: '立即支付',
        success: async (res) => {
          if (!res.confirm) return
          try {
            await api.post(`/api/orders/${order.id}/pay`, {})
            wx.showToast({ title: '支付成功，等待接单', icon: 'success' })
            this.setData({ tab: 'orders' })
            this.loadOrders()
          } catch (err) {
            // 已提示
          }
        }
      })
      this.loadOrders()
    } catch (err) {
      // 已提示
    }
  },

  async reviewOrder(e) {
    const { id } = e.currentTarget.dataset
    wx.showModal({
      title: '服务评价',
      editable: true,
      placeholderText: '请填写评价内容（1-5 分默认 5 分）',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await api.post(`/api/orders/${id}/review`, { score: 5, content: res.content || '满意' })
          wx.showToast({ title: '评价已提交', icon: 'success' })
          this.loadOrders()
        } catch (err) {
          // 已提示
        }
      }
    })
  },

  async cancelOrder(e) {
    const { id } = e.currentTarget.dataset
    wx.showModal({
      title: '取消订单',
      content: '确定要取消该订单吗？已支付订单将进入退款流程。',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await api.post(`/api/orders/${id}/cancel`, { reason: '老人端主动取消' })
          wx.showToast({ title: '已取消', icon: 'success' })
          this.loadOrders()
        } catch (err) {
          // 已提示
        }
      }
    })
  }
})
