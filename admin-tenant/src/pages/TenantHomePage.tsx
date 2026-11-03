import { Link } from 'react-router-dom'
import { useTenantAuth } from '../auth/AuthProvider'
import { useTenantMenus } from '../auth/TenantMenuProvider'
import { buildTenantNavigation } from '../navigation'

export default function TenantHomePage({ workspace }: { workspace: 'HQ' | 'STORE' }) {
 const { principal } = useTenantAuth()
 const menus = useTenantMenus()
 const isHq = workspace === 'HQ'
 const canView = principal?.accessContext.permissionCodes.includes('core.module.view')
 const entries = principal ? buildTenantNavigation(principal, menus.menus).filter(item => item.moduleCode) : []
 return <section className="tenant-home">
  <header className="page-title"><div><h1>{isHq ? '总部工作台' : '门店工作台'}</h1><p>让每一份专业服务，都成为温暖的陪伴。</p></div><span className="quiet-status">员工身份已验证</span></header>
  <section className="care-welcome"><img src="/images/enterprise-welcome.png" alt="长者在明亮的休闲空间中相伴饮茶，AI 生成配图" /><div className="welcome-copy"><h2>你好，{principal?.username}</h2><p>{isHq ? '从这里连接旗下门店，管理已购模块，让优质养老服务在更多地方落地。' : '从这里开始今天的服务，用专业与耐心，守护长者的每一天。'}</p>{isHq && canView && <Link className="care-cta" to="/hq/modules">管理模块</Link>}</div></section>
  <div className="care-grid"><section className="care-card action-card"><h2>{isHq ? '管理已购模块' : '门店服务入口'}</h2><p>{isHq ? '查看授权、启用功能，并为旗下门店分配使用名额。' : '按照当前授权与员工权限，进入本门店开放的业务模块。'}</p>{isHq ? canView ? <Link className="card-link" to="/hq/modules">进入模块授权</Link> : <p>当前账号未分配模块查看权限，请联系企业管理员。</p> : entries.length ? entries.map(item => <Link className="card-link" key={item.key} to={item.route}>{item.label}</Link>) : <p>暂无开放模块，请联系总部确认模块授权与员工权限。</p>}</section>
  <section className="care-card account-card"><h2>当前账号</h2><strong>{principal?.username}</strong><span className="quiet-status">{isHq ? '企业总部' : '门店员工'}</span><dl><div><dt>可用权限</dt><dd>{principal?.accessContext.permissionCodes.length ?? 0} 项</dd></div><div><dt>工作区</dt><dd>{isHq ? '总部管理' : '所属门店'}</dd></div></dl></section>
  <figure className="care-photo"><img src="/images/enterprise-lounge.png" alt="面向湖景的康养公共休息空间，AI 生成配图" /><figcaption>老有所依，生活更美好</figcaption></figure></div>
  <section className="care-card care-empty"><div><h2>经营数据尚未接入</h2><p>对应业务模块接入后，这里将展示实际经营信息。当前可先使用已开放的管理功能。</p></div></section>
 </section>
}
