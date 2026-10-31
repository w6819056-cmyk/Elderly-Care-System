import { usePlatformAuth } from '../auth/AuthProvider'

export default function PlatformHomePage() {
  const { principal } = usePlatformAuth()
  return (
    <section className="platform-home">
      <header><div><p className="eyebrow">PLATFORM</p><h1>平台工作台</h1></div><span className="service-indicator"><i />已验证平台身份</span></header>
      <div className="home-rule" />
      <section className="identity-section">
        <div><span className="field-label">当前账号</span><strong>{principal?.username}</strong></div>
        <div><span className="field-label">平台角色</span><strong>{principal?.roleCodes.length ? principal.roleCodes.join('、') : '未分配角色'}</strong></div>
        <div><span className="field-label">可用权限</span><strong>{principal?.permissionCodes.length ?? 0}</strong></div>
      </section>
      <p className="home-note">平台系统仅管理企业授权、版本发布和平台运营数据。企业业务数据保留在客户部署环境。</p>
    </section>
  )
}
