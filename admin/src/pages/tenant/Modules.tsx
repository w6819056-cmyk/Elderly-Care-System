import { useCallback, useEffect, useMemo, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import {
  clearTenantSession, getTenantPrincipal, getTenantToken, LicenseStatus, ModuleAssignment,
  TenantModule, TenantStore, tenantModulesApi
} from '../../api/tenantModules'

const MODULE_NAMES: Record<string, string> = {
  membership: '会员运营', institution: '机构康养', community: '社区居家'
}
const LICENSE_LABEL: Record<string, string> = { FORMAL: '正式授权', TRIAL: '体验授权', PLATFORM_GRANT: '平台赠送' }

export default function TenantModules() {
  const navigate = useNavigate()
  const principal = getTenantPrincipal()
  const [modules, setModules] = useState<TenantModule[]>([])
  const [stores, setStores] = useState<TenantStore[]>([])
  const [license, setLicense] = useState<LicenseStatus | null>(null)
  const [assignments, setAssignments] = useState<Record<string, ModuleAssignment[]>>({})
  const [selection, setSelection] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const canView = principal?.accessContext.permissionCodes.includes('core.module.view') ?? false
  const canManage = principal?.accessContext.permissionCodes.includes('core.module.manage') ?? false
  const canAssign = principal?.accessContext.permissionCodes.includes('core.module.assignment.manage') ?? false

  const refresh = useCallback(async () => {
    setError('')
    try {
      const [nextModules, nextStores, nextLicense] = await Promise.all([
        tenantModulesApi.modules(), canAssign ? tenantModulesApi.stores() : Promise.resolve([]), tenantModulesApi.license()
      ])
      setModules(nextModules)
      setStores(nextStores)
      setLicense(nextLicense)
      const pairs = await Promise.all(nextModules.map(async module => [module.moduleCode, await tenantModulesApi.assignments(module.moduleCode)] as const))
      setAssignments(Object.fromEntries(pairs))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '加载模块信息失败')
    }
  }, [canAssign])

  useEffect(() => { void refresh() }, [refresh])
  const activeStores = useMemo(() => stores.filter(store => store.status === 'ACTIVE'), [stores])

  if (!getTenantToken() || !principal) return <Navigate to="/hq/login" replace />
  if (principal.staffType !== 'HQ') return <main className="tenant-page"><p className="tenant-error">该账号不能进入企业总部工作区</p></main>
  if (!canView) return <main className="tenant-page"><p className="tenant-error">当前账号无模块查看权限</p></main>

  async function act(action: () => Promise<unknown>) {
    setBusy(true)
    setError('')
    try { await action(); await refresh() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '操作失败'); await refresh() }
    finally { setBusy(false) }
  }

  function logout() {
    clearTenantSession()
    navigate('/hq/login', { replace: true })
  }

  return (
    <main className="tenant-page">
      <header className="tenant-header">
        <div><p className="tenant-eyebrow">总部控制台</p><h1>模块授权</h1></div>
        <div className="tenant-user"><span>{principal.username}</span><button onClick={logout}>退出</button></div>
      </header>
      {license && <section className="tenant-license-strip" aria-label="授权同步状态">
        <div><span>实例状态</span><strong>{license.instanceStatus}</strong></div>
        <div><span>授权版本</span><strong>{license.version}</strong></div>
        <div><span>最近同步</span><strong>{license.lastSuccessfulSyncAt ? new Date(license.lastSuccessfulSyncAt).toLocaleString() : '尚未同步'}</strong></div>
      </section>}
      {error && <div className="tenant-error tenant-notice" role="alert">{error}</div>}
      <div className="tenant-list-head"><h2>已签发授权</h2><button onClick={() => void refresh()} disabled={busy}>刷新</button></div>
      {modules.length === 0 ? <p className="tenant-empty">当前没有可用模块授权</p> : <div className="tenant-module-list">
        {modules.map(module => {
          const moduleAssignments = assignments[module.moduleCode] ?? []
          const activeAssignments = moduleAssignments.filter(item => item.status === 'ACTIVE')
          const availableStores = activeStores.filter(store => !activeAssignments.some(item => item.storePublicId === store.publicId))
          return <section className="tenant-module" key={module.moduleCode}>
            <div className="tenant-module-main">
              <div className="tenant-module-title"><div><h3>{MODULE_NAMES[module.moduleCode] ?? module.moduleCode}</h3><span className="tenant-code">{module.moduleCode}</span></div>
                <span className={`tenant-state ${module.effectiveState === 'ACTIVE' ? 'is-active' : 'is-muted'}`}>{module.effectiveState}</span>
              </div>
              <dl className="tenant-facts">
                <div><dt>授权来源</dt><dd>{LICENSE_LABEL[module.licenseType] ?? module.licenseType}</dd></div>
                <div><dt>有效期</dt><dd>{module.perpetual ? '永久' : module.expiresAt ? new Date(module.expiresAt).toLocaleDateString() : '未设置'}</dd></div>
                <div><dt>门店名额</dt><dd>{module.assignedStores} / {module.storeQuota}</dd></div>
                <div><dt>总部状态</dt><dd>{module.enabled ? '已启用' : '未启用'}</dd></div>
              </dl>
              <div className="tenant-module-actions">
                {canManage && <button disabled={busy || (!module.enabled && module.licenseStatus !== 'ACTIVE')}
                  onClick={() => void act(() => tenantModulesApi.activation(module.moduleCode, !module.enabled))}>
                  {module.enabled ? '停用模块' : '启用模块'}
                </button>}
                {canAssign && module.enabled && <div className="tenant-assign-control">
                  <select aria-label={`为${MODULE_NAMES[module.moduleCode] ?? module.moduleCode}选择门店`}
                    value={selection[module.moduleCode] ?? ''}
                    onChange={event => setSelection(current => ({ ...current, [module.moduleCode]: event.target.value }))}>
                    <option value="">选择营业门店</option>
                    {availableStores.map(store => <option key={store.publicId} value={store.publicId}>{store.name} · {store.storeNo}</option>)}
                  </select>
                  <button className="primary" disabled={busy || module.assignedStores >= module.storeQuota || !selection[module.moduleCode]}
                    onClick={() => void act(() => tenantModulesApi.assign(module.moduleCode, selection[module.moduleCode]))}>分配名额</button>
                </div>}
              </div>
            </div>
            {moduleAssignments.length > 0 && <div className="tenant-assignment-list">
              <h4>门店分配</h4>
              {moduleAssignments.map(item => <div className="tenant-assignment" key={item.publicId}>
                <span>{item.storeName} <small>{item.storePublicId}</small></span>
                <span className={item.status === 'ACTIVE' ? 'tenant-status-active' : 'tenant-status-revoked'}>{item.status === 'ACTIVE' ? '使用中' : '已撤销'}</span>
                {canAssign && item.status === 'ACTIVE' && <button className="tenant-link-danger" disabled={busy}
                  onClick={() => void act(() => tenantModulesApi.revoke(module.moduleCode, item.storePublicId))}>撤销</button>}
              </div>)}
            </div>}
          </section>
        })}
      </div>}
    </main>
  )
}
