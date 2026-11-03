import {useCallback,useEffect,useRef,useState,type FormEvent} from 'react'
import {request} from '../api/client'
import {usePlatformAuth} from '../auth/AuthProvider'

export interface PackageVersion {moduleCode:string;name:string;version:string;sha256:string;status:string;createdAt:string}
export interface ModulePackageGroup {moduleCode:string;name:string;versions:PackageVersion[];latestPublishedVersion:string|null}

function compareVersions(left:string,right:string){
 const parse=(value:string)=>value.split('-')[0].split('.').map(part=>Number(part)||0)
 const a=parse(left),b=parse(right)
 for(let index=0;index<3;index++){if((a[index]??0)!==(b[index]??0))return (a[index]??0)-(b[index]??0)}
 return left.localeCompare(right)
}

export function groupPackageRows(rows:PackageVersion[]):ModulePackageGroup[]{
 const groups=new Map<string,ModulePackageGroup>()
 for(const row of rows){
  const group=groups.get(row.moduleCode)??{moduleCode:row.moduleCode,name:row.name,versions:[],latestPublishedVersion:null}
  group.versions.push(row)
  if(row.status==='PUBLISHED'&&(!group.latestPublishedVersion||compareVersions(row.version,group.latestPublishedVersion)>0))group.latestPublishedVersion=row.version
  groups.set(row.moduleCode,group)
 }
 return [...groups.values()].map(group=>({...group,versions:[...group.versions].sort((a,b)=>compareVersions(b.version,a.version))}))
}

export default function ModuleCenterPage(){
 const {principal}=usePlatformAuth();const canView=principal?.permissionCodes.includes('platform.module.view'),canManage=principal?.permissionCodes.includes('platform.module.manage')
 const [rows,setRows]=useState<ModulePackageGroup[]>([]),[error,setError]=useState(''),[notice,setNotice]=useState(''),[loading,setLoading]=useState(false),[busy,setBusy]=useState(false),[file,setFile]=useState<File|null>(null),[showCreate,setShowCreate]=useState(false)
 const [editing,setEditing]=useState<PackageVersion|null>(null)
 const lock=useRef(false),fileInput=useRef<HTMLInputElement>(null)
 const load=useCallback(async()=>{if(!canView)return;setLoading(true);setError('');try{const packages=await request<PackageVersion[]>('/api/platform/v1/module-packages');setRows(groupPackageRows(packages))}catch(e){setError(e instanceof Error?e.message:'加载失败')}finally{setLoading(false)}},[canView])
 useEffect(()=>{void load()},[load])
 async function upload(e:FormEvent){e.preventDefault();if(!file||lock.current)return;if(file.size>64*1024*1024){setError('模块包不得超过64MiB');return}lock.current=true;setBusy(true);setError('');setNotice('');try{const result=await request<PackageVersion>('/api/platform/v1/module-packages',{method:'POST',body:file,headers:{'Content-Type':'application/octet-stream'}});setNotice(`${result.name} ${result.version} 已通过静态校验并收录，尚未安装到企业。`);setFile(null);if(fileInput.current)fileInput.current.value='';await load()}catch(e){setError(e instanceof Error?e.message:'上传失败')}finally{lock.current=false;setBusy(false)}}
 async function create(e:FormEvent<HTMLFormElement>){e.preventDefault();if(lock.current)return;const f=new FormData(e.currentTarget);lock.current=true;setBusy(true);setError('');try{await request('/api/platform/v1/modules',{method:'POST',body:JSON.stringify({code:String(f.get('code')).trim(),name:String(f.get('name')).trim(),description:String(f.get('description')).trim(),menuOrder:Number(f.get('order')),trialEnabled:false,dependencies:[],prices:[]})});setNotice('模块目录已创建，可以上传相同编号的签名模块包。');setShowCreate(false)}catch(e){setError(e instanceof Error?e.message:'创建失败')}finally{lock.current=false;setBusy(false)}}
 if(!canView)return <section className="package-center"><h1>无权查看模块中心</h1></section>
 return <section className="package-center"><header className="package-heading"><div><p>模块交付 · 版本档案</p><h1>模块中心</h1></div><button disabled={loading||busy} onClick={()=>void load()}>刷新</button></header><p>同一模块只显示一张卡片，卡片展示最新已发布版本；其他版本请展开版本详情查看。</p>
 {error&&<p className="form-error" role="alert">{error}</p>}{notice&&<p className="package-notice" role="status">{notice}</p>}
 {canManage&&<section className="package-upload"><div><h2>上传模块版本</h2><p>先创建销售目录，再上传对应编号的 .caremod 包。同一版本不能覆盖。</p></div><form onSubmit={e=>void upload(e)}><label htmlFor="module-package">选择模块包（最大64MiB）</label><input id="module-package" type="file" ref={fileInput} accept=".caremod,.zip" disabled={busy} onChange={e=>setFile(e.target.files?.[0]??null)}/><button disabled={busy||!file}>{busy?'正在处理…':'校验并收录'}</button></form><button disabled={busy} onClick={()=>setShowCreate(v=>!v)}>{showCreate?'收起目录表单':'新建模块目录'}</button>{showCreate&&<form className="package-create" onSubmit={e=>void create(e)}><label>模块编号<input name="code" required pattern="[a-z][a-z0-9-]{1,31}" maxLength={32} placeholder="如 membership" disabled={busy}/></label><label>模块名称<input name="name" required maxLength={100} disabled={busy}/></label><label>目录说明<input name="description" required maxLength={1000} disabled={busy}/></label><label>菜单顺序<input name="order" type="number" min={1} defaultValue={100} required disabled={busy}/></label><button disabled={busy}>创建草稿目录</button></form>}</section>}
 {editing&&<section className="package-upload"><h2>修改模块资料 · {editing.moduleCode}</h2><form onSubmit={async e=>{e.preventDefault();const f=new FormData(e.currentTarget);setBusy(true);setError('');try{await request('/api/platform/v1/storefront/'+editing.moduleCode+'/profile',{method:'PUT',body:JSON.stringify({name:f.get('name'),description:f.get('description')})});setEditing(null);setNotice('模块资料已修改；发布包的版本与校验摘要保持不变。');await load()}catch(e){setError(e instanceof Error?e.message:'修改失败')}finally{setBusy(false)}}}><label>模块名称<input name="name" required maxLength={100} defaultValue={editing.name}/></label><label>模块说明<input name="description" required maxLength={1000}/></label><button type="button" onClick={()=>setEditing(null)}>取消</button><button disabled={busy}>保存资料</button></form></section>}
 <section><h2>已收录模块 <small>{loading?'读取中':`${rows.length} 个`}</small></h2>{loading?<p role="status">正在读取模块版本…</p>:!error&&!rows.length?<p className="package-empty">当前没有已收录模块包</p>:<div className="package-grid">{rows.map(group=>{const latest=group.versions.find(version=>version.version===group.latestPublishedVersion)??group.versions[0];return <article className="package-item" key={group.moduleCode}><div className="package-heading"><h3>{group.name}</h3><span>{latest?.status==='PUBLISHED'?'已发布':'待发布'}</span></div><p>{group.moduleCode} · 当前版本 {group.latestPublishedVersion?`v${group.latestPublishedVersion}`:'暂无已发布版本'}</p><small>共收录 {group.versions.length} 个版本</small><details><summary>版本详情</summary><div className="package-version-list">{group.versions.map(version=><div className="package-version-row" key={`${version.moduleCode}:${version.version}`}><div><strong>v{version.version}</strong><span>{version.status==='PUBLISHED'?'已发布':'已收录'}</span><small>收录时间：{new Date(version.createdAt).toLocaleString('zh-CN')}</small></div><details><summary>文件校验摘要</summary><code>{version.sha256}</code></details>{canManage&&<div className="package-version-actions"><button disabled={busy} onClick={()=>setEditing(version)}>修改模块资料</button><button disabled={busy} onClick={()=>{fileInput.current?.click();setNotice('请选取 '+version.moduleCode+' 的新版本签名包，然后校验并收录。')}}>更新版本</button><button disabled={busy||version.status==='PUBLISHED'} onClick={async()=>{setBusy(true);setError('');try{await request('/api/platform/v1/module-packages/'+version.moduleCode+'/'+version.version+'/publish',{method:'POST'});setNotice('版本已发布，授权企业可获取。');await load()}catch(e){setError(e instanceof Error?e.message:'发布失败')}finally{setBusy(false)}}}>{version.status==='PUBLISHED'?'已发布':'发布版本'}</button></div>}</div>)}</div></details><p>新版本会归入当前模块，不会生成新的模块卡片。</p></article>})}</div>}</section></section>
}
