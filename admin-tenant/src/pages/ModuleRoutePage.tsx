import type { TenantModuleMenu } from '../api/tenant'

export default function ModuleRoutePage({ menu }: { menu: TenantModuleMenu }) {
  return (
    <section className="tenant-module-landing">
      <p className="tenant-eyebrow">{menu.moduleCode.toUpperCase()}</p>
      <h1>{menu.label}</h1>
      <p>模块授权有效。该模块的业务页面将在对应功能接入时提供。</p>
      <span className="tenant-module-state">{menu.state}</span>
    </section>
  )
}
