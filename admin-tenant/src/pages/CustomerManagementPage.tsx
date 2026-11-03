import {useCallback,useEffect,useRef,useState,type FormEvent} from 'react'
import {useTenantAuth} from '../auth/AuthProvider'
import {managedCustomers,type CustomerList,type CustomerDetail} from '../api/customerManagement'
import {StoreDialog} from './TenantStoresPage'
import './customer-management.css'

const statuses:Record<string,string>={PENDING:'待确认',CONFIRMED:'已确认',COMPLETED:'已接待',CANCELLED:'已取消'}
const date=(value:string)=>new Date(value).toLocaleDateString('zh-CN')
export default function CustomerManagementPage(){
 const {principal}=useTenantAuth()
 const [input,setInput]=useState(''),[query,setQuery]=useState(''),[page,setPage]=useState(1),[attempt,setAttempt]=useState(0)
 const [data,setData]=useState<CustomerList|null>(null),[loading,setLoading]=useState(true),[error,setError]=useState('')
 const [selected,setSelected]=useState<string|null>(null),[detail,setDetail]=useState<CustomerDetail|null>(null),[detailError,setDetailError]=useState(''),[detailAttempt,setDetailAttempt]=useState(0)
 const searchRef=useRef<HTMLInputElement>(null)
 const close=useCallback(()=>setSelected(null),[])
 useEffect(()=>{let live=true;setLoading(true);setError('');setData(null)
  void managedCustomers.list(query,page).then(value=>{if(live)setData(value)}).catch(e=>{if(live)setError(e instanceof Error?e.message:'客户读取失败')}).finally(()=>{if(live)setLoading(false)})
  return()=>{live=false}
 },[query,page,attempt])
 useEffect(()=>{if(!selected)return;let live=true;setDetail(null);setDetailError('')
  void managedCustomers.detail(selected).then(value=>{if(live)setDetail(value)}).catch(e=>{if(live)setDetailError(e instanceof Error?e.message:'详情读取失败')})
  return()=>{live=false}
 },[selected,detailAttempt])
 function search(event:FormEvent){event.preventDefault();setQuery(input.trim());setPage(1);setAttempt(v=>v+1)}
 const totalPages=Math.max(1,Math.ceil((data?.total??0)/20))
 return <section className="stores-page customers-page">
  <header className="tenant-page-heading"><div><h1>客户管理</h1><p>{principal?.staffType==='HQ'?'了解客户与门店的联系，查看管理范围内的客户资料。':'查看与本店有预约联系的客户，跟进咨询与到店安排。'}</p></div><button className="secondary-action" disabled={loading} onClick={()=>setAttempt(v=>v+1)}>刷新</button></header>
  <form className="store-toolbar customer-search" role="search" onSubmit={search}><input ref={searchRef} type="search" aria-label="搜索客户" placeholder="搜索昵称、账号或预约联系电话" maxLength={80} value={input} onChange={e=>setInput(e.target.value)}/><button className="tenant-primary-action" type="submit" disabled={loading}>搜索</button>{query&&<button className="secondary-action" type="button" onClick={()=>{setInput('');setQuery('');setPage(1);searchRef.current?.focus()}}>清空</button>}<span aria-live="polite">{data?`共 ${data.total} 位客户`:'客户资料'}</span></form>
  <div className="store-display-card customer-display">
   {error?<div className="customer-empty"><p className="tenant-error" role="alert">{error}</p><button className="secondary-action" onClick={()=>setAttempt(v=>v+1)}>重新加载</button></div>:loading?<p className="store-empty" role="status">正在读取客户资料…</p>:!data?.items.length?<p className="store-empty">{query?'没有找到匹配的客户':'当前没有可见客户'}</p>:<div className="customer-card-grid">{data.items.map(customer=><article className="customer-person-card" key={customer.publicId}>
    <header><span className="customer-avatar" aria-hidden="true">{(customer.displayName||customer.username).slice(0,1)}</span><span className="customer-kind">注册用户</span></header>
    <h2>{customer.displayName}</h2><p className="customer-account">账号 · {customer.username}</p>
    <dl><div><dt>注册日期</dt><dd>{date(customer.createdAt)}</dd></div><div><dt>预约记录</dt><dd>{customer.bookingCount} 次</dd></div></dl>
    <div className="customer-store-tags">{customer.stores.length?customer.stores.map(s=><span key={s.publicId}>{s.name}</span>):<span>尚未关联门店</span>}</div>
    <footer><span>客户资料</span><button className="staff-details-toggle" onClick={()=>setSelected(customer.publicId)}>查看详情</button></footer>
   </article>)}</div>}
   <footer className="customer-list-footer"><small>仅展示当前账号有权查看的客户及门店记录</small><div className="store-buttons"><button className="secondary-action" disabled={loading||page<=1} onClick={()=>setPage(v=>v-1)}>上一页</button><span aria-live="polite">{page} / {totalPages}</span><button className="secondary-action" disabled={loading||!data||page>=totalPages} onClick={()=>setPage(v=>v+1)}>下一页</button></div></footer>
  </div>
  {selected&&<StoreDialog title="客户详情" close={close}>{detailError?<><p className="tenant-error" role="alert">{detailError}</p><button className="secondary-action" onClick={()=>setDetailAttempt(v=>v+1)}>重试详情</button></>:!detail?<p role="status">正在读取详情…</p>:<div className="customer-detail"><header><span className="customer-avatar" aria-hidden="true">{detail.customer.displayName.slice(0,1)}</span><div><h3>{detail.customer.displayName}</h3><p>账号：{detail.customer.username}</p><p>注册于 {date(detail.customer.createdAt)}</p></div></header><h3>预约记录 <small>最近 {detail.bookings.length} 条 / 共 {detail.customer.bookingCount} 条</small></h3><p className="customer-detail-hint">联系电话来自本次预约，不代表账号已验证手机号。</p>{detail.bookings.length?<ol className="customer-bookings">{detail.bookings.map(b=><li key={b.publicId}><div><strong>{b.storeName}</strong><span>{statuses[b.status]??'状态未知'}</span></div><p>{b.preferredDate} · {b.visitType==='VISIT'?'到店参观':'预约咨询'}</p><p>{b.visitorName} · <a href={`tel:${b.phone}`}>{b.phone}</a></p>{b.remark&&<p className="customer-booking-note">{b.remark}</p>}</li>)}</ol>:<p>当前没有预约记录</p>}</div>}</StoreDialog>}
 </section>
}
