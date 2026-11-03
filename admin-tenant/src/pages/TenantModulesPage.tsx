import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTenantAuth } from '../auth/AuthProvider'
import { tenantApi, type LicenseStatus, type ModuleAssignment, type TenantModule, type TenantStoreOption } from '../api/tenant'

const MODULE_NAMES: Record<string, string> = {
  membership: '会员运营', institution: '机构康养', community: '社区居家'
}
const LICENSE_LABELS: Record<string, string> = { FORMAL: '正式授权', TRIAL: '体验授权', PLATFORM_GRANT: '平台赠送' }

export default function TenantModulesPage() {
  const { principal } = useTenantAuth()
  const permissionCodes = principal?.accessContext.permissionCodes ?? []
  const canManage = permissionCodes.includes('core.module.manage')
  const canAssign = permissionCodes.includes('core.module.assignment.manage')
  const [modules, setModules] = useState<TenantModule[]>([])
  const [stores, setStores] = useState<TenantStoreOption[]>([])
  const [license, setLicense] = useState<LicenseStatus | null>(null)
  const [assignments, setAssignments] = useState<Record<string, ModuleAssignment[]>>({})
  const [selection, setSelection] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const refresh = useCallback(async () => {
    setError('')
    try {
      const [nextModules, nextStores, nextLicense] = await Promise.all([
        tenantApi.modules(), canAssign ? tenantApi.stores() : Promise.resolve([]), tenantApi.license()
      ])
      const pairs = await Promise.all(nextModules.map(async module => [module.moduleCode, await tenantApi.assignments(module.moduleCode)] as const))
      setModules(nextModules)
      setStores(nextStores)
      setLicense(nextLicense)
      setAssignments(Object.fromEntries(pairs))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '加载模块信息失败')
    }
  }, [canAssign])

  useEffect(() => { void refresh() }, [refresh])
  const activeStores = useMemo(() => stores.filter(store => store.status === 'ACTIVE'), [stores])

  async function runAction(action: () => Promise<unknown>) {
    setBusy(true)
    setError('')
    try { await action(); await refresh() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '操作失败'); await refresh() }
    finally { setBusy(false) }
  }

  return (
    <section className={`tenant-modules-page${modules.length === 0 && !error ? ' has-no-modules' : ''}`}>
      <header className="tenant-page-heading"><div><p className="tenant-eyebrow">ENTERPRISE SETTINGS</p><h1>模块授权</h1></div>
        <button className="secondary-action" onClick={() => void refresh()} disabled={busy}>刷新</button></header>
      {license && <dl className="license-strip">
        <div><dt>实例状态</dt><dd>{license.instanceStatus}</dd></div>
        <div><dt>授权版本</dt><dd>{license.version}</dd></div>
        <div><dt>最近同步</dt><dd>{license.lastSuccessfulSyncAt ? new Date(license.lastSuccessfulSyncAt).toLocaleString() : '尚未同步'}</dd></div>
      </dl>}
      {error && <p className="tenant-error" role="alert">{error}</p>}
      {modules.length === 0 && !error ? <p className="module-empty-message">当前没有可用模块授权</p> : <div className="module-list">
        {modules.map(module => {
          const items = assignments[module.moduleCode] ?? []
          const active = items.filter(item => item.status === 'ACTIVE')
          const available = activeStores.filter(store => !active.some(item => item.storePublicId === store.publicId))
          const name = MODULE_NAMES[module.moduleCode] ?? module.moduleCode
          return <article className="module-row" key={module.moduleCode}>
            <div className="module-row-main">
              <div className="module-title"><div><h2>{name}</h2><code>{module.moduleCode}</code></div>
                <span className={module.effectiveState === 'ACTIVE' ? 'state-tag is-active' : 'state-tag'}>{module.effectiveState}</span>
              </div>
              <dl className="module-facts">
                <div><dt>程序安装</dt><dd>{module.installed ? `已安装 ${module.installedVersion}` : '已授权，待安装'}</dd></div>
                <div><dt>授权来源</dt><dd>{LICENSE_LABELS[module.licenseType] ?? module.licenseType}</dd></div>
                <div><dt>有效期</dt><dd>{module.perpetual ? '永久' : module.expiresAt ? new Date(module.expiresAt).toLocaleDateString() : '未设置'}</dd></div>
                <div><dt>门店名额</dt><dd>{module.assignedStores} / {module.storeQuota}</dd></div>
                <div><dt>总部状态</dt><dd>{module.enabled ? '已启用' : '未启用'}</dd></div>
              </dl>
              <div className="module-actions">
                {canManage && <button className="secondary-action" disabled={busy || (!module.enabled && module.licenseStatus !== 'ACTIVE')}
                  onClick={() => void runAction(() => tenantApi.activation(module.moduleCode, !module.enabled))}>
                  {module.enabled ? '停用模块' : '启用模块'}
                </button>}
                {canAssign && module.enabled && <div className="assignment-control">
                  <select aria-label={`为${name}选择门店`} value={selection[module.moduleCode] ?? ''}
                    onChange={event => setSelection(current => ({ ...current, [module.moduleCode]: event.target.value }))}>
                    <option value="">选择营业门店</option>
                    {available.map(store => <option key={store.publicId} value={store.publicId}>{store.name} · {store.storeNo}</option>)}
                  </select>
                  <button className="tenant-primary-action compact" disabled={busy || module.assignedStores >= module.storeQuota || !selection[module.moduleCode]}
                    onClick={() => void runAction(() => tenantApi.assign(module.moduleCode, selection[module.moduleCode]))}>分配名额</button>
                </div>}
              </div>
            </div>
            {items.length > 0 && <div className="assignment-list"><h3>门店分配</h3>
              {items.map(item => <div className="assignment-item" key={item.publicId}>
                <span>{item.storeName} <small>{item.storePublicId}</small></span>
                <span className={item.status === 'ACTIVE' ? 'assignment-active' : 'assignment-revoked'}>{item.status === 'ACTIVE' ? '使用中' : '已撤销'}</span>
                {canAssign && item.status === 'ACTIVE' && <button className="text-danger" disabled={busy}
                  onClick={() => void runAction(() => tenantApi.revoke(module.moduleCode, item.storePublicId))}>撤销</button>}
              </div>)}
            </div>}
          </article>
        })}
      </div>}
    </section>
  )
}
