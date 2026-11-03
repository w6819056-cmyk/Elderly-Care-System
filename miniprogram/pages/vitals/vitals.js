const { api } = require('../../utils/request')
const { VITAL_LABEL, vitalText } = require('../../utils/format')

const TYPES = Object.keys(VITAL_LABEL).map((k) => ({ key: k, label: VITAL_LABEL[k] }))

Page({
  data: {
    elderId: null,
    types: TYPES,
    activeType: 'BLOOD_PRESSURE',
    value1: '',
    value2: '',
    records: [],
    medications: [],
    drugName: '',
    dosage: '',
    frequency: '每日一次',
    remindTime: '08:00',
    submitting: false
  },

  onShow() {
    const app = getApp()
    const user = app.globalData.user
    if (!user) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    // 家属可查看已绑定老人，默认取第一个
    if (user.role === 'ELDER') {
      this.setData({ elderId: user.elderId })
      this.loadAll()
    } else {
      this.loadFamilyElder()
    }
  },

  async loadFamilyElder() {
    try {
      const elders = await api.get('/api/family/elders')
      if (!elders || elders.length === 0) {
        wx.showToast({ title: '尚未绑定老人', icon: 'none' })
        return
      }
      this.setData({ elderId: elders[0].id })
      this.loadAll()
    } catch (err) {
      // 已提示
    }
  },

  async loadAll() {
    const { elderId, activeType } = this.data
    try {
      const [records, medications] = await Promise.all([
        api.get(`/api/health/elders/${elderId}/vitals?type=${activeType}&limit=30`, { silent: true }),
        api.get(`/api/health/elders/${elderId}/medications`, { silent: true })
      ])
      this.setData({
        records: (records || []).map((r) => ({ ...r, text: vitalText(r) })),
        medications: medications || []
      })
    } catch (err) {
      // 已提示
    }
  },

  switchType(e) {
    this.setData({ activeType: e.currentTarget.dataset.key, value1: '', value2: '' })
    this.loadAll()
  },

  onValue1(e) {
    this.setData({ value1: e.detail.value })
  },

  onValue2(e) {
    this.setData({ value2: e.detail.value })
  },

  async submitVital() {
    const { elderId, activeType, value1, value2 } = this.data
    if (!value1) {
      wx.showToast({ title: '请输入数值', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      const payload = { type: activeType, value1: Number(value1) }
      if (activeType === 'BLOOD_PRESSURE' && value2) {
        payload.value2 = Number(value2)
      }
      const result = await api.post(`/api/health/elders/${elderId}/vitals`, payload)
      wx.showToast({
        title: result.abnormal ? '已记录，数值异常已通知家属' : '已记录',
        icon: result.abnormal ? 'none' : 'success',
        duration: 2500
      })
      this.setData({ value1: '', value2: '' })
      this.loadAll()
    } catch (err) {
      // 已提示
    } finally {
      this.setData({ submitting: false })
    }
  },

  onDrugName(e) {
    this.setData({ drugName: e.detail.value })
  },
  onDosage(e) {
    this.setData({ dosage: e.detail.value })
  },
  onFrequency(e) {
    this.setData({ frequency: e.detail.value })
  },
  onRemindTime(e) {
    this.setData({ remindTime: e.detail.value })
  },

  async addMedication() {
    const { elderId, drugName, dosage, frequency, remindTime } = this.data
    if (!drugName || !dosage) {
      wx.showToast({ title: '请填写药品名与剂量', icon: 'none' })
      return
    }
    try {
      await api.post(`/api/health/elders/${elderId}/medications`, { drugName, dosage, frequency, remindTime })
      wx.showToast({ title: '已添加用药提醒', icon: 'success' })
      this.setData({ drugName: '', dosage: '' })
      this.loadAll()
    } catch (err) {
      // 已提示
    }
  },

  async stopMedication(e) {
    const { id } = e.currentTarget.dataset
    const { elderId } = this.data
    try {
      await api.del(`/api/health/elders/${elderId}/medications/${id}`)
      wx.showToast({ title: '已停用', icon: 'success' })
      this.loadAll()
    } catch (err) {
      // 已提示
    }
  }
})
