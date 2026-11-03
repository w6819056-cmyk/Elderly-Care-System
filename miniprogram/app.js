App({
  globalData: {
    // 真机调试时改为后端可访问地址；开发者工具中勾选「不校验合法域名」
    baseUrl: 'http://localhost:8082',
    token: '',
    user: null
  },

  onLaunch() {
    const token = wx.getStorageSync('ec_token')
    const user = wx.getStorageSync('ec_user')
    if (token && user) {
      this.globalData.token = token
      this.globalData.user = user
    }
  }
})
