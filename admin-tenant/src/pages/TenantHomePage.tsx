import { useTenantAuth } from '../auth/AuthProvider'

export default function TenantHomePage({ workspace }: { workspace: 'HQ' | 'STORE' }) {
  const { principal } = useTenantAuth()
  const isHq = workspace === 'HQ'
  return (
    <section className="tenant-home">
      <header><div><p className="tenant-eyebrow">{isHq ? 'ENTERPRISE' : 'STORE'}</p><h1>{isHq ? '总部工作台' : '门店工作台'}</h1></div>
        <span className="tenant-online"><i />员工身份已验证</span></header>
      <div className="tenant-rule" />
      <section className="tenant-identity">
        <div><span>当前员工</span><strong>{principal?.username}</strong></div>
        <div><span>工作区</span><strong>{isHq ? '企业总部' : '门店员工'}</strong></div>
        <div><span>可用权限</span><strong>{principal?.accessContext.permissionCodes.length ?? 0}</strong></div>
      </section>
      <p className="tenant-home-note">当前工作区根据企业员工权限和数据范围加载。各经营功能按企业授权逐步接入。</p>
    </section>
  )
}
