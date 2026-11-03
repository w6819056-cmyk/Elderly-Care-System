import {useEffect,useState} from 'react'
import {request} from '../api/client'
import {usePlatformAuth} from '../auth/AuthProvider'
interface Row{publicId:string;orgNo:string;name:string;contactName:string;contactPhone:string;status:string;reason:string}
export default function EnterpriseApplicationsPanel({onReviewed}:{onReviewed:()=>void}){
 const {principal}=usePlatformAuth();const canManage=principal?.permissionCodes.includes('platform.enterprise.manage')
 const [rows,setRows]=useState<Row[]>([]),[target,setTarget]=useState<{row:Row;approved:boolean}|null>(null),[reason,setReason]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(false)
 async function load(){try{setRows(await request<Row[]>('/api/platform/v1/enterprise-applications'))}catch(e){setError(e instanceof Error?e.message:'申请加载失败')}}
 useEffect(()=>{void load()},[])
 async function review(){if(!target)return;setBusy(true);setError('');try{await request('/api/platform/v1/enterprise-applications/'+target.row.publicId+'/review',{method:'POST',body:JSON.stringify({approved:target.approved,reason})});setTarget(null);setReason('');await load();onReviewed()}catch(e){setError(e instanceof Error?e.message:'审核失败')}finally{setBusy(false)}}
 return <section className="commerce-panel"><div className="commerce-section-heading"><h2>企业登记审核</h2><button onClick={()=>void load()}>刷新申请</button></div>{error&&<p role="alert" className="form-error">{error}</p>}{!rows.length?<p>暂无企业登记申请。</p>:<div className="commerce-grid">{rows.map(row=><article key={row.publicId}><small>{({PENDING:'待审核',APPROVED:'已通过',REJECTED:'已驳回'} as Record<string,string>)[row.status]}</small><h3>{row.name}</h3><p>{row.orgNo} · {row.contactName} · {row.contactPhone}</p>{row.reason&&<p>审核说明：{row.reason}</p>}{canManage&&row.status==='PENDING'&&<div><button onClick={()=>{setTarget({row,approved:true});setReason('')}}>通过</button><button onClick={()=>{setTarget({row,approved:false});setReason('')}}>驳回</button></div>}</article>)}</div>}{target&&<form onSubmit={e=>{e.preventDefault();void review()}}><label>{target.approved?'通过':'驳回'} {target.row.name}：审核说明<textarea required maxLength={500} value={reason} onChange={e=>setReason(e.target.value)}/></label><footer><button type="button" onClick={()=>setTarget(null)}>取消</button><button disabled={busy||!reason.trim()}>确认审核</button></footer></form>}</section>
}
