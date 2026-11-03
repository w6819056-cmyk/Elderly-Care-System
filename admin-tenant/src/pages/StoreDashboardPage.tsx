import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTenantAuth } from '../auth/AuthProvider'
import { useTenantMenus } from '../auth/TenantMenuProvider'
import { buildTenantNavigation } from '../navigation'
import { request } from '../api/client'
import { visitApi, type VisitBooking } from '../api/storePortal'
import { siteApi, type SiteEditor } from '../api/siteContent'

const reviewLabels: Record<string,string> = { DRAFT:'草稿', PENDING:'等待总部审核', PUBLISHED:'已发布', REJECTED:'审核未通过' }
export default function StoreDashboardPage() {
 const { principal } = useTenantAuth()
 const menus = useTenantMenus()
 const permissions = principal?.accessContext.permissionCodes ?? []
 const visitsAllowed = permissions.includes('core.visit.view')
 const siteAllowed = permissions.includes('core.site.view')
 const [visits,setVisits] = useState<VisitBooking[]>([])
 const [site,setSite] = useState<SiteEditor|null>(null)
 const [store,setStore] = useState<{publicId:string;storeNo:string;name:string}|null>(null)
 const [loading,setLoading] = useState(true)
 const [errors,setErrors] = useState<string[]>([])
 const [attempt,setAttempt] = useState(0)
 const entries = principal ? buildTenantNavigation(principal,menus.menus).filter(item=>item.moduleCode) : []
 useEffect(()=>{
  let live=true; setLoading(true);setErrors([]);setVisits([]);setSite(null);setStore(null)
  const jobs:Promise<void>[]=[]
  const failed=(label:string)=>(e:unknown)=>{if(live)setErrors(current=>[...current,`${label}：${e instanceof Error?e.message:'读取失败'}`])}
  if(visitsAllowed)jobs.push(visitApi.list().then(data=>{if(live)setVisits(data)}).catch(failed('预约信息')))
  if(siteAllowed)jobs.push(request<{publicId:string;storeNo:string;name:string}>('/api/tenant/v1/site-content-store').then(async data=>{if(live)setStore(data);const content=await siteApi.edit(data.publicId);if(live)setSite(content)}).catch(failed('首页信息')))
  void Promise.all(jobs).then(()=>{if(live)setLoading(false)})
  return ()=>{live=false}
 },[visitsAllowed,siteAllowed,principal?.username,attempt])
 const waiting=visits.filter(v=>v.status==='PENDING')
 const confirmed=visits.filter(v=>v.status==='CONFIRMED')
 return <section className="store-dashboard">
  <header className="tenant-page-heading"><div><h1>{store?.name || '门店工作台'}</h1><p>你好，{principal?.username}。从待办开始，安排今天的接待与服务。</p></div><button className="secondary-action" disabled={loading} onClick={()=>setAttempt(v=>v+1)}>{loading?'正在读取…':'刷新工作台'}</button></header>
  {errors.map(error=><p className="tenant-error" role="alert" key={error}>{error}</p>)}
  <div className="store-dashboard-metrics">
   {visitsAllowed&&<><Link className="care-card" to="/store/visits"><span>待确认预约</span><strong>{loading||errors.some(e=>e.startsWith('预约'))?'—':waiting.length}</strong><small>联系访客，确认到访安排 →</small></Link><Link className="care-card" to="/store/visits"><span>待接待预约</span><strong>{loading||errors.some(e=>e.startsWith('预约'))?'—':confirmed.length}</strong><small>已确认，等待完成接待 →</small></Link></>}
   {siteAllowed&&<Link className="care-card" to="/store/site"><span>门店首页</span><strong className="store-dashboard-status">{loading?'读取中':site?reviewLabels[site.status]||'状态未知':'暂不可用'}</strong><small>{site?.published?'线上保留已发布内容':'尚无已发布内容'} →</small></Link>}
  </div>
  <div className="store-dashboard-columns"><section className="care-card"><h2>接待待办</h2>{!visitsAllowed?<p>当前账号没有预约查看权限，请联系企业管理员。</p>:loading?<p role="status">正在读取预约…</p>:errors.some(e=>e.startsWith('预约'))?<p>预约读取失败，请刷新重试。</p>:!waiting.length&&!confirmed.length?<p className="dashboard-empty">当前没有待处理的预约</p>:<><ul className="dashboard-task-list">{[...waiting,...confirmed].slice(0,6).map(v=><li key={v.publicId}><div><strong>{v.visitorName}</strong><span>{v.visitType==='VISIT'?'到店参观':'咨询了解'} · {v.preferredDate}</span></div><span className="quiet-status">{v.status==='PENDING'?'待确认':'待接待'}</span></li>)}</ul><Link className="card-link" to="/store/visits">查看并处理全部预约 →</Link></>}</section>
   <section className="care-card"><h2>常用操作</h2><div className="dashboard-shortcuts">{visitsAllowed&&<Link to="/store/visits">咨询与参观预约 <span>确认、接待与跟进 →</span></Link>}{siteAllowed&&<Link to="/store/site">维护门店首页 <span>编辑介绍、环境与联系方式 →</span></Link>}{store&&<Link to={`/stores/${encodeURIComponent(store.storeNo)}`} target="_blank" rel="noopener noreferrer">查看用户首页 <span>查看当前对外展示的内容 ↗</span></Link>}{!visitsAllowed&&!siteAllowed&&<p>当前账号尚未分配基础管理功能，请联系总部。</p>}</div>{site?.reviewNote&&<p className="store-notice">总部审核意见：{site.reviewNote}</p>}</section></div>
  <section className="care-card"><h2>可用业务模块</h2><p>仅显示本门店获授权且当前账号有权使用的功能。</p>{menus.status==='loading'?<p role="status">正在读取模块…</p>:menus.status==='error'?<><p className="tenant-error">{menus.error}</p><button className="secondary-action" onClick={menus.retry}>重试模块加载</button></>:entries.length?<div className="dashboard-module-links">{entries.map(item=><Link className="secondary-action" to={item.route} key={item.key}>{item.label} →</Link>)}</div>:<p className="dashboard-empty">当前没有可用业务模块，请联系总部确认授权与角色权限。</p>}</section>
 </section>
}
