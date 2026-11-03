import EnterpriseApplicationsPanel from './EnterpriseApplicationsPanel'
import './commerce-workspace.css'
import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Buildings, Key, Pulse, Pause, Play } from '@phosphor-icons/react'
import { request } from '../api/client'
import { usePlatformAuth } from '../auth/AuthProvider'

interface Enterprise {
  publicId: string
  orgNo: string
  name: string
  contactName: string
  contactPhone: string
  status: 'ACTIVE' | 'SUSPENDED' | 'CLOSED'
  createdAt: string
}

interface Instance {
  publicId: string
  installationId: string
  tenantDatabaseId: string
  status: string
  licenseVersion: number
  lastHeartbeatAt: string | null
}

interface PageResult<T> { items: T[]; total: number; page: number; pageSize: number }
interface ActivationCode { publicId: string; activationCode: string; expiresAt: string; maxAttempts: number }
interface ModuleEntitlement { id: number; moduleCode: string; moduleName: string; licenseType: string; status: string; startsAt: string; expiresAt: string | null; perpetual: boolean; storeQuota: number; orderBacked: boolean }

const statusLabel: Record<Enterprise['status'], string> = { ACTIVE: '正常', SUSPENDED: '已暂停', CLOSED: '已关闭' }

export default function PlatformEnterprisesPage() {
  const { principal } = usePlatformAuth()
  const permissions = principal?.permissionCodes ?? []
  const canView = permissions.includes('platform.enterprise.view')
  const canManage = permissions.includes('platform.enterprise.manage')
  const canLicense = permissions.includes('platform.license.manage')
  const [result, setResult] = useState<PageResult<Enterprise> | null>(null)
  const [instances, setInstances] = useState<Record<string, Instance[]>>({})
  const [expanded, setExpanded] = useState<string | null>(null)
  const [activation, setActivation] = useState<{ enterprise: Enterprise; value: ActivationCode } | null>(null)
  const [editingEnterprise, setEditingEnterprise] = useState<Enterprise | null>(null)
  const [moduleEnterprise, setModuleEnterprise] = useState<Enterprise | null>(null)
  const [enterpriseModules, setEnterpriseModules] = useState<ModuleEntitlement[]>([])
  const [editingModule, setEditingModule] = useState<ModuleEntitlement | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    if (!canView) return
    setError('')
    try {
      setResult(await request<PageResult<Enterprise>>('/api/platform/v1/enterprises?page=1&pageSize=20'))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '企业列表加载失败')
    }
  }, [canView])

  useEffect(() => { void load() }, [load])

  if (!canView) return <section className="platform-page enterprise-page"><h1>无权查看企业管理</h1></section>

  async function toggleStatus(enterprise: Enterprise) {
    if (!canManage || enterprise.status === 'CLOSED') return
    setBusy(true)
    try {
      await request(`/api/platform/v1/enterprises/${enterprise.publicId}/status`, {
        method: 'PATCH', body: JSON.stringify({ status: enterprise.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' })
      })
      await load()
    } catch (reason) { setError(reason instanceof Error ? reason.message : '状态更新失败') } finally { setBusy(false) }
  }

  async function loadInstances(enterprise: Enterprise) {
    if (expanded === enterprise.publicId) { setExpanded(null); return }
    setExpanded(enterprise.publicId)
    if (instances[enterprise.publicId]) return
    try {
      const rows = await request<Instance[]>(`/api/platform/v1/instances?enterpriseId=${encodeURIComponent(enterprise.publicId)}`)
      setInstances(current => ({ ...current, [enterprise.publicId]: rows }))
    } catch (reason) { setError(reason instanceof Error ? reason.message : '实例加载失败') }
  }

  async function createActivation(enterprise: Enterprise) {
    setBusy(true)
    try {
      const value = await request<ActivationCode>(`/api/platform/v1/enterprises/${enterprise.publicId}/activation-codes`, {
        method: 'POST', body: JSON.stringify({ ttlHours: 72, maxAttempts: 5 })
      })
      setActivation({ enterprise, value })
    } catch (reason) { setError(reason instanceof Error ? reason.message : '激活码生成失败') } finally { setBusy(false) }
  }


  async function saveEnterprise(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!editingEnterprise || !canManage) return
    const data=new FormData(event.currentTarget);setBusy(true)
    try { const updated=await request<Enterprise>(`/api/platform/v1/enterprises/${editingEnterprise.publicId}`,{method:'PUT',body:JSON.stringify({name:data.get('name'),contactName:data.get('contactName'),contactPhone:data.get('contactPhone')})});setResult(current=>current?{...current,items:current.items.map(row=>row.publicId===updated.publicId?updated:row)}:current);setEditingEnterprise(null) }
    catch(reason){setError(reason instanceof Error?reason.message:'企业资料保存失败')}finally{setBusy(false)}
  }

  async function loadEnterpriseModules(enterprise:Enterprise) {
    setModuleEnterprise(enterprise);setBusy(true)
    try { setEnterpriseModules(await request<ModuleEntitlement[]>(`/api/platform/v1/enterprises/${enterprise.publicId}/modules`)) }
    catch(reason){setError(reason instanceof Error?reason.message:'企业模块授权加载失败')}finally{setBusy(false)}
  }

  async function saveModule(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();if(!moduleEnterprise||!editingModule||!canManage)return
    const data=new FormData(event.currentTarget);const expiry=String(data.get('expiresAt')??'').trim();setBusy(true)
    try {const updated=await request<ModuleEntitlement>(`/api/platform/v1/enterprises/${moduleEnterprise.publicId}/modules/${editingModule.id}`,{method:'PATCH',body:JSON.stringify({expiresAt:editingModule.perpetual?null:new Date(expiry).toISOString(),storeQuota:Number(data.get('storeQuota')),status:data.get('status')})});setEnterpriseModules(rows=>rows.map(row=>row.id===updated.id?updated:row));setEditingModule(null)}
    catch(reason){setError(reason instanceof Error?reason.message:'模块授权更新失败')}finally{setBusy(false)}
  }

  return <section className="platform-page enterprise-page">
    <header className="page-title"><div><p className="eyebrow">客户与授权 · 控制面</p><h1>企业管理</h1><p className="page-subtitle">审核企业自助登记，管理已开通企业、实例与模块授权。</p></div><div className="page-actions"><button className="enterprise-action" onClick={() => void load()} disabled={busy}>刷新</button></div></header>
    {error && <p className="form-error" role="alert">{error}</p>}
    <EnterpriseApplicationsPanel onReviewed={() => void load()} /><div className="enterprise-summary"><span><Buildings size={23} />企业总数 <strong>{result?.total ?? '—'}</strong></span><span><Pulse size={23} />本页实例已绑定 <strong>{Object.values(instances).flat().length}</strong></span><span><Key size={23} />授权操作 <strong>{canLicense ? '可用' : '仅查看'}</strong></span></div>
    <section className="enterprise-list" aria-label="企业列表">
      {!result && <p role="status">正在加载企业信息…</p>}
      {result?.items?.length === 0 && <p className="empty-state">当前没有已开通企业，企业自助提交登记并审核通过后将显示在这里。</p>}
      {result?.items?.map(enterprise => <article className="enterprise-card" key={enterprise.publicId}>
        <div className="enterprise-card-main"><div className="enterprise-mark">{enterprise.name.slice(0, 1)}</div><div><div className="enterprise-name-row"><h2>{enterprise.name}</h2><span className={`status status-${enterprise.status.toLowerCase()}`}>{statusLabel[enterprise.status]}</span></div><p>{enterprise.orgNo} · 联系人 {enterprise.contactName} · {enterprise.contactPhone}</p><small>创建于 {new Date(enterprise.createdAt).toLocaleDateString('zh-CN')}</small></div></div>
        <div className="enterprise-card-actions"><button className="enterprise-action" onClick={() => void loadInstances(enterprise)}>{expanded === enterprise.publicId ? '收起实例' : '查看实例'}</button><button className="enterprise-action" onClick={()=>void loadEnterpriseModules(enterprise)}>模块授权</button>{canManage&&<button className="enterprise-action" onClick={()=>setEditingEnterprise(enterprise)}>编辑资料</button>}{canLicense && <button className="enterprise-action" onClick={() => void createActivation(enterprise)} disabled={busy || enterprise.status === 'CLOSED'}><Key size={16} />生成激活码</button>}{canManage && enterprise.status !== 'CLOSED' && <button className="enterprise-icon-action" aria-label={enterprise.status === 'ACTIVE' ? '暂停企业' : '恢复企业'} onClick={() => void toggleStatus(enterprise)} disabled={busy}>{enterprise.status === 'ACTIVE' ? <Pause size={17} /> : <Play size={17} />}</button>}</div>
        {expanded === enterprise.publicId && <div className="instance-panel"><h3>部署实例</h3>{!instances[enterprise.publicId]?.length ? <p>暂无已激活实例</p> : instances[enterprise.publicId].map(instance => <div className="instance-row" key={instance.publicId}><span><strong>{instance.installationId}</strong><small>{instance.tenantDatabaseId}</small></span><span>授权版本 v{instance.licenseVersion}</span><span className="status status-active">{instance.status}</span><span>{instance.lastHeartbeatAt ? `最近心跳 ${new Date(instance.lastHeartbeatAt).toLocaleString('zh-CN')}` : '尚未心跳'}</span></div>)}</div>}
      </article>)}
    </section>
    {editingEnterprise&&<div className="modal-backdrop"><section className="modal-card" role="dialog" aria-modal="true" aria-label="编辑企业资料"><header><div><p className="eyebrow">企业信息</p><h2>编辑企业资料</h2></div><button type="button" aria-label="关闭" onClick={()=>setEditingEnterprise(null)}>×</button></header><form onSubmit={event=>void saveEnterprise(event)}><label>企业编号<input value={editingEnterprise.orgNo} disabled /></label><label>企业名称<input name="name" aria-label="企业名称" defaultValue={editingEnterprise.name} required maxLength={200}/></label><label>联系人<input name="contactName" aria-label="联系人" defaultValue={editingEnterprise.contactName} required maxLength={100}/></label><label>联系电话<input name="contactPhone" aria-label="联系电话" defaultValue={editingEnterprise.contactPhone} required maxLength={40}/></label><footer><button type="button" onClick={()=>setEditingEnterprise(null)}>取消</button><button className="primary-action" disabled={busy}>保存资料</button></footer></form></section></div>}
    {moduleEnterprise&&<div className="modal-backdrop"><section className="modal-card" role="dialog" aria-modal="true" aria-label={`${moduleEnterprise.name}模块授权`}><header><div><p className="eyebrow">企业模块授权</p><h2>{moduleEnterprise.name} · 模块信息</h2></div><button type="button" aria-label="关闭" onClick={()=>setModuleEnterprise(null)}>×</button></header>{!enterpriseModules.length?<p>该企业当前没有模块授权。</p>:enterpriseModules.map(module=><article key={module.id} className="enterprise-module-row"><div><strong>{module.moduleName}</strong><p>{module.moduleCode} · {module.licenseType==='FORMAL'?'正式授权':module.licenseType} · {module.perpetual?'永久':`到期 ${module.expiresAt?new Date(module.expiresAt).toLocaleDateString('zh-CN'):'—'}`} · {module.storeQuota} 家门店</p><small>{module.orderBacked?'来源：销售订单':'来源：平台授权'}</small></div><span className={`status status-${module.status.toLowerCase()}`}>{module.status}</span>{canManage&&<button className="enterprise-action" onClick={()=>setEditingModule(module)}>修改</button>}</article>)}</section></div>}
    {editingModule&&moduleEnterprise&&<div className="modal-backdrop"><section className="modal-card" role="dialog" aria-modal="true" aria-label="修改模块授权"><header><div><p className="eyebrow">{editingModule.moduleCode}</p><h2>修改 {editingModule.moduleName}</h2></div><button type="button" aria-label="关闭" onClick={()=>setEditingModule(null)}>×</button></header><form onSubmit={event=>void saveModule(event)}><label>门店名额<input name="storeQuota" type="number" min="1" defaultValue={editingModule.storeQuota} required/></label><label>授权状态<select name="status" defaultValue={editingModule.status==='SUSPENDED'?'SUSPENDED':'ACTIVE'}><option value="ACTIVE">正常</option><option value="SUSPENDED">暂停</option></select></label>{!editingModule.perpetual&&<label>授权到期时间<input name="expiresAt" type="datetime-local" defaultValue={editingModule.expiresAt?.slice(0,16)} required/></label>}<footer><button type="button" onClick={()=>setEditingModule(null)}>取消</button><button className="primary-action" disabled={busy}>保存模块授权</button></footer></form></section></div>}

    {activation && <div className="modal-backdrop"><section className="modal-card activation-modal" role="dialog" aria-modal="true" aria-labelledby="activation-title"><p className="eyebrow">一次性凭据</p><h2 id="activation-title">{activation.enterprise.name} 激活码</h2><p>请将激活码安全交给客户安装程序。关闭后平台不会再次展示明文。</p><code>{activation.value.activationCode}</code><small>有效期至 {new Date(activation.value.expiresAt).toLocaleString('zh-CN')} · 最多尝试 {activation.value.maxAttempts} 次</small><button className="primary-action" onClick={() => setActivation(null)}>我已记录</button></section></div>}
  </section>
}
