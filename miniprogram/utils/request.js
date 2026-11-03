const app = getApp()

/**
 * 统一请求封装。后端返回 { code, message, data }，code=0 视为成功。
 * 401 时清理会话并跳转登录页。
 */
function request(path, options = {}) {
  const { method = 'GET', data, silent = false } = options
  return new Promise((resolve, reject) => {
    wx.request({
      url: `${app.globalData.baseUrl}${path}`,
      method,
      data,
      header: {
        'Content-Type': 'application/json',
        ...(app.globalData.token ? { Authorization: `Bearer ${app.globalData.token}` } : {})
      },
      success(res) {
        const body = res.data || {}
        if (res.statusCode === 401) {
          wx.removeStorageSync('ec_token')
          wx.removeStorageSync('ec_user')
          app.globalData.token = ''
          app.globalData.user = null
          wx.reLaunch({ url: '/pages/login/login' })
          reject(new Error(body.message || '登录已过期'))
          return
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 0) {
          resolve(body.data)
          return
        }
        const message = body.message || `请求失败（${res.statusCode}）`
        if (!silent) {
          wx.showToast({ title: message, icon: 'none', duration: 2500 })
        }
        reject(new Error(message))
      },
      fail(err) {
        if (!silent) {
          wx.showToast({ title: '网络异常，请检查后端服务', icon: 'none', duration: 2500 })
        }
        reject(err)
      }
    })
  })
}

const api = {
  get: (path, options) => request(path, { ...options, method: 'GET' }),
  post: (path, data, options) => request(path, { ...options, method: 'POST', data }),
  put: (path, data, options) => request(path, { ...options, method: 'PUT', data }),
  del: (path, options) => request(path, { ...options, method: 'DELETE' })
}

module.exports = { request, api }
