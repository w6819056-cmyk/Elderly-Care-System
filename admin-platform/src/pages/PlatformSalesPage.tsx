import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowClockwise, CheckCircle, CurrencyCny, Package, ShieldCheck } from '@phosphor-icons/react'
import { request } from '../api/client'
import { usePlatformAuth } from '../auth/AuthProvider'

interface Enterprise { publicId: string; name: string; status: string }
interface EnterpriseResult { items: Enterprise[]; total?: number }
interface Price { billingMode: 'PERIOD' | 'PERPETUAL'; durationDays: number; amount: string; currency: string; storeQuota: number; trialDays: number | null }
interface Module { moduleCode: string; name: string; description: string; saleStatus: string; prices: Price[] }
interface ModuleResult { items: Module[] }
interface PlanItem { moduleCode: string; moduleName?: string; billingMode: string; durationDays: number; storeQuota: number; standardAmount?: number | null; entitlementResult?: string | null }
interface Plan { planCode: string; name: string; description: string; amount: number; minimumAmount: number; currency: string; saleStatus: string; items: PlanItem[] }
interface Quote { enterpriseId: string; planCode: string | null; currency: string; originalAmount: number; deductionAmount: number; payableAmount: number; items: PlanItem[] }
interface Order { publicId: string; orderNo: string; enterpriseId: string; status?: string; payableAmount: number; currency?: string; createdAt?: string; planCode?: string | null; items: PlanItem[]; payments: Array<{ channel: string; amount: number; status: string; channelTransactionNo?: string; paidAt?: string | null }>; refunds?: Array<{ amount: number; reason: string; status: string }> }
interface OrderPage { items: Order[]; total: number; page: number; pageSize: number }
interface PurchaseCommand { enterpriseId: string; planCode: string | null; items: Array<{ moduleCode: string; billingMode: string; durationDays: number; storeQuota: number }>; period: { billingMode: string; durationDays: number; storeQuota: number } | null }

const orderStatus: Record<string, string> = {
  PENDING_PAYMENT: '待支付', PAID: '已支付', FULFILLING: '履约中', FULFILLED: '已履约', FULFILLMENT_FAILED: '履约失败', REFUNDED: '已退款', CANCELLED: '已取消', MANUAL_REVIEW: '人工复核', REFUND_PENDING: '退款处理中'
}
const emptyPrice = (): Price => ({ billingMode: 'PERIOD', durationDays: 365, amount: '', currency: 'CNY', storeQuota: 1, trialDays: null })
function money(value: number | undefined, currency = 'CNY') { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency }).format(value ?? 0) }
function priceKey(moduleCode: string, price: Price) { return `${moduleCode}|${price.billingMode}|${price.durationDays}|${price.storeQuota}` }
function priceLabel(price: Price) { return `${price.billingMode === 'PERPETUAL' ? '永久' : `${price.durationDays} 天`} · ${price.storeQuota} 家门店 · ${money(Number(price.amount), price.currency)}` }

export default function PlatformSalesPage({ section }: { section?: 'modules' | 'plans' | 'orders' }) {
  const { principal } = usePlatformAuth()
  const permissions = principal?.permissionCodes ?? []
  const canSell = permissions.includes('platform.enterprise.view') && permissions.includes('platform.plan.view') && permissions.includes('platform.order.view')
  const canViewModules = permissions.includes('platform.module.view')
  const canManageModules = permissions.includes('platform.module.manage')
  const canViewPlans = permissions.includes('platform.plan.view')
  const canManagePlans = permissions.includes('platform.plan.manage')
  const canManageOrders = permissions.includes('platform.order.manage')
  const canView = canSell || canViewModules || canViewPlans
  const [workspace, setWorkspace] = useState<'purchase' | 'products' | 'orders'>(section === 'orders' ? 'orders' : section ? 'products' : canSell ? 'purchase' : 'products')
  const [enterprises, setEnterprises] = useState<Enterprise[]>([])
  const [plans, setPlans] = useState<Plan[]>([])
  const [catalogPlans, setCatalogPlans] = useState<Plan[]>([])
  const [modules, setModules] = useState<Module[]>([])
  const [planProducts, setPlanProducts] = useState<Array<{id:number;moduleCode:string;name:string;description:string;billing_mode:'PERIOD'|'PERPETUAL';duration_days:number;amount:number;store_quota:number;sale_status:string}>>([])
  const [priceDrafts, setPriceDrafts] = useState<Record<string, Price[]>>({})
  const [enterpriseId, setEnterpriseId] = useState('')
  const [purchaseMode, setPurchaseMode] = useState<'plan' | 'module'>('plan')
  const [planCode, setPlanCode] = useState('')
  const [moduleChoice, setModuleChoice] = useState('')
  const [quote, setQuote] = useState<Quote | null>(null)
  const [order, setOrder] = useState<Order | null>(null)
  const [orders, setOrders] = useState<Order[]>([])
  const [orderStatusFilter, setOrderStatusFilter] = useState('')
  const [orderEnterpriseFilter, setOrderEnterpriseFilter] = useState('')
  const [selectedOrder, setSelectedOrder] = useState<Order | null>(null)
  const [editingOrder, setEditingOrder] = useState<Order | null>(null)
  const [moduleAuthorizationAction, setModuleAuthorizationAction] = useState<{ order: Order; item: PlanItem; restore: boolean } | null>(null)
  const [quoteCommand, setQuoteCommand] = useState<PurchaseCommand | null>(null)
  const [editingPlanCode, setEditingPlanCode] = useState<string | null>(null)
  const [planDraft, setPlanDraft] = useState({ code: '', name: '', description: '', amount: '', minimumAmount: '' })
  const [planItemChoice, setPlanItemChoice] = useState('')
  const [planItems, setPlanItems] = useState<PlanItem[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const productsLoaded = useRef(false)

  useEffect(() => {
    if (!canSell) return
    request<EnterpriseResult>('/api/platform/v1/enterprises?page=1&pageSize=100')
      .then(result => setEnterprises(result.items ?? []))
      .catch(reason => setError(reason instanceof Error ? reason.message : '企业加载失败'))
  }, [canSell])

  useEffect(() => {
    if (!enterpriseId) { setPlans([]); setPlanCode(''); return }
    setLoading(true)
    request<Plan[]>(`/api/platform/v1/plans?enterpriseId=${encodeURIComponent(enterpriseId)}`)
      .then(result => { setPlans(result); setPlanCode(result[0]?.planCode ?? '') })
      .catch(reason => setError(reason instanceof Error ? reason.message : '可购套餐加载失败'))
      .finally(() => setLoading(false))
  }, [enterpriseId])

  useEffect(() => {
    if (workspace !== 'products' || productsLoaded.current) return
    productsLoaded.current = true
    const loads: Promise<unknown>[] = []
    if (canViewModules) loads.push(request<ModuleResult>('/api/platform/v1/modules?page=1&pageSize=100').then(result => {
      const rows = result.items ?? []
      setModules(rows)
      setPriceDrafts(Object.fromEntries(rows.map(module => [module.moduleCode, module.prices?.map(price => ({ ...price, trialDays: price.trialDays ?? null })) ?? []])))
    }))
    if (canViewPlans) loads.push(request<Plan[]>('/api/platform/v1/plans/catalog').then(setCatalogPlans))
    if (loads.length) {
      setLoading(true); setError('')
      void Promise.all(loads).catch(reason => {
        productsLoaded.current = false
        setError(reason instanceof Error ? reason.message : '商品目录加载失败')
      }).finally(() => setLoading(false))
    }
  }, [workspace, canViewModules, canViewPlans])

  useEffect(() => { if (section === 'orders') void loadOrders() }, [section])

  useEffect(() => { if (section === 'plans') request<typeof planProducts>('/api/platform/v1/commerce/products').then(setPlanProducts).catch(e => setError(e.message)) }, [section])

  const selectablePrices = useMemo(() => section === 'plans' ? planProducts.filter(p => p.sale_status === 'ON_SALE').map(p => ({module: {moduleCode:p.moduleCode,name:p.name,description:p.description,saleStatus:p.sale_status,prices:[]} as Module,price:{billingMode:p.billing_mode,durationDays:p.duration_days,amount:String(p.amount),currency:'CNY',storeQuota:p.store_quota,trialDays:null} as Price})) : modules.filter(module => module.saleStatus === 'ON_SALE').flatMap(module => (priceDrafts[module.moduleCode] ?? module.prices ?? []).map(price => ({ module, price }))), [modules, priceDrafts, section, planProducts])
  const selectedEnterprise = enterprises.find(item => item.publicId === enterpriseId)
  const selectedPlan = plans.find(item => item.planCode === planCode && item.saleStatus === 'ON_SALE')
  const selectedModulePrice = selectablePrices.find(({ module, price }) => priceKey(module.moduleCode, price) === moduleChoice)
  const chosenPlanItem = selectablePrices.find(({ module, price }) => priceKey(module.moduleCode, price) === planItemChoice)

  if (!canView) return <section className="platform-page sales-page"><h1>无权查看销售授权或商品配置</h1></section>

  function clearPurchaseResult() { setQuote(null); setOrder(null); setQuoteCommand(null) }
  async function refreshModuleCatalog() {
    setModuleChoice('')
    clearPurchaseResult()
    try {
      const result = await request<ModuleResult>('/api/platform/v1/modules?page=1&pageSize=100')
      setModules(result.items ?? [])
    } catch (reason) { setError(reason instanceof Error ? reason.message : '模块目录加载失败') }
  }
  function makePurchaseCommand(): PurchaseCommand | null {
    if (!enterpriseId) return null
    if (purchaseMode === 'plan') return selectedPlan ? { enterpriseId, planCode: selectedPlan.planCode, items: [], period: null } : null
    if (!selectedModulePrice) return null
    const { module, price } = selectedModulePrice
    const item = { moduleCode: module.moduleCode, billingMode: price.billingMode, durationDays: price.durationDays, storeQuota: price.storeQuota }
    return { enterpriseId, planCode: null, items: [item], period: { billingMode: price.billingMode, durationDays: price.durationDays, storeQuota: price.storeQuota } }
  }

  async function getQuote() {
    const command = makePurchaseCommand()
    if (!command) return
    setBusy(true); setError(''); setNotice(''); setOrder(null)
    try { setQuote(await request<Quote>('/api/platform/v1/orders/quote', { method: 'POST', body: JSON.stringify(command) })); setQuoteCommand(command) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '报价失败') }
    finally { setBusy(false) }
  }

  async function createOrder() {
    if (!quote || !quoteCommand || !canManageOrders) return
    setBusy(true); setError('')
    try { const created = await request<Order>('/api/platform/v1/orders', { method: 'POST', headers: { 'Idempotency-Key': `web-${Date.now()}` }, body: JSON.stringify(quoteCommand) }); setOrder(created); setOrders(rows => [created, ...rows]) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '订单创建失败') }
    finally { setBusy(false) }
  }

  async function loadOrders() {
    if (!canSell) return
    const query = new URLSearchParams({ page: '1', pageSize: '100' })
    if (orderStatusFilter) query.set('status', orderStatusFilter)
    if (orderEnterpriseFilter) query.set('enterpriseId', orderEnterpriseFilter)
    setLoading(true); setError('')
    try { const result = await request<OrderPage>(`/api/platform/v1/orders?${query}`); setOrders(result.items ?? []) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '销售订单加载失败') }
    finally { setLoading(false) }
  }

  async function saveOrderEdit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!editingOrder || !canManageOrders) return
    const data = new FormData(event.currentTarget)
    const choice = String(data.get('price') ?? '')
    const selected = selectablePrices.find(({ module, price }) => priceKey(module.moduleCode, price) === choice)
    if (!selected) { setError('请选择有效的在售模块价格方案'); return }
    const item = { moduleCode: selected.module.moduleCode, billingMode: selected.price.billingMode, durationDays: selected.price.durationDays, storeQuota: selected.price.storeQuota }
    setBusy(true); setError('')
    try {
      const updated = await request<Order>(`/api/platform/v1/orders/${editingOrder.publicId}`, { method: 'PUT', body: JSON.stringify({ enterpriseId: editingOrder.enterpriseId, planCode: null, items: [item], period: { billingMode: item.billingMode, durationDays: item.durationDays, storeQuota: item.storeQuota } }) })
      setOrders(rows => rows.map(row => row.publicId === updated.publicId ? updated : row)); setEditingOrder(null); setNotice(`订单 ${updated.orderNo} 已按当前商品价格重新计算。`)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '订单修改失败') }
    finally { setBusy(false) }
  }

  async function submitModuleAuthorizationAction(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();if(!moduleAuthorizationAction||!canManageOrders)return
    const form=new FormData(event.currentTarget);const {order:target,item,restore}=moduleAuthorizationAction
    setBusy(true);setError('')
    try {const updated=await request<Order>(`/api/platform/v1/orders/${target.publicId}/items/${encodeURIComponent(item.moduleCode)}/${restore?'restore':'revoke'}`,{method:'POST',body:JSON.stringify({reason:form.get('reason')})});setOrders(rows=>rows.map(row=>row.publicId===updated.publicId?updated:row));setSelectedOrder(updated);setModuleAuthorizationAction(null);setNotice(restore?`${item.moduleName??item.moduleCode} 授权已恢复。`:`${item.moduleName??item.moduleCode} 授权已撤销，订单及付款记录保留。`)}
    catch(reason){setError(reason instanceof Error?reason.message:'模块授权操作失败')}
    finally{setBusy(false)}
  }

  async function payAndFulfill() {
    if (!order || !canManageOrders) return
    setBusy(true); setError('')
    try { setOrder(await request<Order>(`/api/platform/v1/orders/${order.publicId}/pay-simulated`, { method: 'POST', body: JSON.stringify({ channel: 'MOCK', channelTransactionNo: `MOCK-${Date.now()}` }) })) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '模拟支付失败') }
    finally { setBusy(false) }
  }

  async function retryFulfillment() {
    if (!order || !canManageOrders) return
    setBusy(true); setError('')
    try { setOrder(await request<Order>(`/api/platform/v1/orders/${order.publicId}/retry-fulfillment`, { method: 'POST' })) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '履约重试失败') }
    finally { setBusy(false) }
  }

  async function savePrices(moduleCode: string) {
    if (!canManageModules) return
    const prices = priceDrafts[moduleCode] ?? []
    setBusy(true); setError(''); setNotice('')
    try {
      const saved = await request<Price[]>(`/api/platform/v1/modules/${encodeURIComponent(moduleCode)}/prices`, { method: 'PUT', body: JSON.stringify({ prices }) })
      setModules(rows => rows.map(module => module.moduleCode === moduleCode ? { ...module, prices: saved } : module))
      setPriceDrafts(rows => ({ ...rows, [moduleCode]: saved }))
      setNotice(`${modules.find(module => module.moduleCode === moduleCode)?.name ?? moduleCode} 的价格方案已保存。`)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '价格方案保存失败') }
    finally { setBusy(false) }
  }

  async function toggleModule(module: Module) {
    if (!canManageModules) return
    const status = module.saleStatus === 'ON_SALE' ? 'OFF_SALE' : 'ON_SALE'
    setBusy(true); setError(''); setNotice('')
    try {
      const updated = await request<Module>(`/api/platform/v1/modules/${encodeURIComponent(module.moduleCode)}/status`, { method: 'PUT', body: JSON.stringify({ status }) })
      setModules(rows => rows.map(row => row.moduleCode === updated.moduleCode ? { ...row, ...updated } : row))
      setNotice(status === 'ON_SALE' ? `模块“${module.name}”已上架，可用于单模块销售和套餐。` : `模块“${module.name}”已停售；已有授权和历史订单不受影响。`)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '模块状态更新失败') }
    finally { setBusy(false) }
  }

  async function createPlan(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canManagePlans || planItems.length === 0) return
    setBusy(true); setError(''); setNotice('')
    try {
      const items = planItems.map(({ moduleCode, billingMode, durationDays, storeQuota }) => ({ moduleCode, billingMode, durationDays, storeQuota }))
      const created = await request<Plan>('/api/platform/v1/plans'+(editingPlanCode?'/'+editingPlanCode:''), { method: editingPlanCode?'PUT':'POST', body: JSON.stringify({ ...planDraft, minimumAmount: planDraft.minimumAmount || planDraft.amount, items }) })
      setPlans(rows => [created, ...rows.filter(p=>p.planCode!==created.planCode)]); setCatalogPlans(rows => [created, ...rows.filter(p=>p.planCode!==created.planCode)])
      setNotice(`套餐“${created.name}”已创建并上架。`)
      setPlanDraft({ code: '', name: '', description: '', amount: '', minimumAmount: '' }); setPlanItems([]); setEditingPlanCode(null)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '套餐创建失败') }
    finally { setBusy(false) }
  }

  async function togglePlan(plan: Plan) {
    if (!canManagePlans) return
    const status = plan.saleStatus === 'ON_SALE' ? 'OFF_SALE' : 'ON_SALE'
    setBusy(true); setError(''); setNotice('')
    try {
      const updated = await request<Plan>(`/api/platform/v1/plans/${encodeURIComponent(plan.planCode)}/status`, { method: 'PUT', body: JSON.stringify({ status }) })
      setPlans(rows => rows.map(row => row.planCode === updated.planCode ? updated : row)); setCatalogPlans(rows => rows.map(row => row.planCode === updated.planCode ? updated : row))
      setNotice(status === 'ON_SALE' ? `套餐“${plan.name}”已上架。` : `套餐“${plan.name}”已停售；历史订单与授权不受影响。`)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '套餐状态更新失败') }
    finally { setBusy(false) }
  }

  const renderPriceRows = (module: Module) => {
    const prices = priceDrafts[module.moduleCode] ?? []
    return <div className="product-prices">
      {!prices.length && <p className="sales-empty-inline">还没有价格方案。添加至少一个方案后，该模块才可加入套餐或单独购买。</p>}
      {prices.map((price, index) => <div className="price-editor-row" key={`${module.moduleCode}-${index}`}>
        <label>计费方式<select aria-label={`${module.name}计费方式 ${index + 1}`} value={price.billingMode} onChange={event => updatePrice(module.moduleCode, index, { billingMode: event.target.value as Price['billingMode'], durationDays: event.target.value === 'PERPETUAL' ? 0 : Math.max(1, price.durationDays || 365) })}><option value="PERIOD">按期</option><option value="PERPETUAL">永久</option></select></label>
        <label>期限（天）<input aria-label={`${module.name}期限 ${index + 1}`} type="number" min="1" value={price.durationDays || ''} disabled={price.billingMode === 'PERPETUAL'} onChange={event => updatePrice(module.moduleCode, index, { durationDays: Number(event.target.value) })} /></label>
        <label>价格（元）<input aria-label={`${module.name}价格 ${index + 1}`} inputMode="decimal" value={price.amount} onChange={event => updatePrice(module.moduleCode, index, { amount: event.target.value })} /></label>
        <label>门店名额<input aria-label={`${module.name}门店名额 ${index + 1}`} type="number" min="1" value={price.storeQuota} onChange={event => updatePrice(module.moduleCode, index, { storeQuota: Number(event.target.value) })} /></label>
        <button className="icon-action" type="button" aria-label={`移除${module.name}价格方案 ${index + 1}`} onClick={() => setPriceDrafts(rows => ({ ...rows, [module.moduleCode]: prices.filter((_, rowIndex) => rowIndex !== index) }))}>移除</button>
      </div>)}
      {canManageModules && <div className="product-actions"><button type="button" className="secondary-action" onClick={() => setPriceDrafts(rows => ({ ...rows, [module.moduleCode]: [...prices, emptyPrice()] }))}>添加价格方案</button><button type="button" className="primary-action" disabled={busy} onClick={() => void savePrices(module.moduleCode)}>保存价格方案</button></div>}
    </div>
  }

  function updatePrice(moduleCode: string, index: number, patch: Partial<Price>) {
    setPriceDrafts(rows => ({ ...rows, [moduleCode]: (rows[moduleCode] ?? []).map((price, rowIndex) => rowIndex === index ? { ...price, ...patch } : price) }))
  }

  return <section className="platform-page sales-page">
    <header className="page-title sales-title"><div><p className="eyebrow">授权商业 · 交付控制</p><h1>销售授权</h1><p className="page-subtitle">配置模块商品和销售套餐，再为企业报价、下单并履约。</p></div><div className="sales-signal"><ShieldCheck size={21} />正式授权链路</div></header>
    <div style={section ? { display: 'none' } : undefined} className="sales-tabs" role="tablist" aria-label="销售授权工作区">
      {canSell && <button role="tab" aria-selected={workspace === 'purchase'} className={workspace === 'purchase' ? 'active' : ''} onClick={() => { setWorkspace('purchase'); setError('') }}>企业授权</button>}
      {(canViewModules || canViewPlans) && <button role="tab" aria-selected={workspace === 'products'} className={workspace === 'products' ? 'active' : ''} onClick={() => { setWorkspace('products'); setError('') }}>商品配置</button>}
      {canSell && <button role="tab" aria-selected={workspace === 'orders'} className={workspace === 'orders' ? 'active' : ''} onClick={() => { setWorkspace('orders'); setError(''); void loadOrders() }}>销售订单</button>}
    </div>
    {error && <p className="form-error" role="alert">{error}</p>}{notice && <p className="sales-notice" role="status">{notice}</p>}

    {workspace === 'orders' ? <section className="sales-card order-list-workspace"><div className="section-heading"><div><p className="eyebrow">企业购买记录</p><h2>销售订单</h2></div><div className="order-filters"><select aria-label="按企业筛选" value={orderEnterpriseFilter} onChange={event => setOrderEnterpriseFilter(event.target.value)}><option value="">全部企业</option>{enterprises.map(item => <option key={item.publicId} value={item.publicId}>{item.name}</option>)}</select><select aria-label="按状态筛选" value={orderStatusFilter} onChange={event => setOrderStatusFilter(event.target.value)}><option value="">全部状态</option>{Object.entries(orderStatus).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select><button type="button" className="secondary-action" onClick={() => void loadOrders()} disabled={loading}>筛选</button></div></div>{loading ? <p role="status">正在加载订单…</p> : !orders.length ? <div className="sales-empty">当前没有符合条件的订单</div> : <div className="sales-order-list">{orders.map(row => <article key={row.publicId} className="sales-order-row"><div><strong>{row.orderNo}</strong><small>{enterprises.find(item => item.publicId === row.enterpriseId)?.name ?? row.enterpriseId} · {row.items.map(item => item.moduleName ?? item.moduleCode).join('、') || row.planCode || '套餐订单'}</small></div><span className={`order-status order-${(row.status ?? '').toLowerCase()}`}>{orderStatus[row.status ?? ''] ?? row.status}</span><strong>{money(row.payableAmount, row.currency)}</strong><div className="sales-order-actions"><button type="button" className="secondary-action" onClick={() => setSelectedOrder(row)}>查看</button>{row.status === 'PENDING_PAYMENT' && canManageOrders && <button type="button" className="secondary-action" disabled={busy} onClick={async () => { setBusy(true); setError(''); try { await request('/api/platform/v1/orders/'+row.publicId+'/cancel',{method:'POST'}); setNotice('订单已取消，预留优惠券已释放。'); await loadOrders() } catch(e) {setError(e instanceof Error?e.message:'取消失败')} finally {setBusy(false)} }}>取消订单</button>}{row.status === 'PENDING_PAYMENT' && canManageOrders && <button type="button" className="secondary-action" onClick={() => { setEditingOrder(row); if (!modules.length) void request<ModuleResult>('/api/platform/v1/modules?page=1&pageSize=100').then(result => setModules(result.items ?? [])) }}>修改</button>}</div></article>)}</div>}</section> : workspace === 'products' ? <div className="product-workspace">
      {canViewModules && section !== 'plans' && <section className="sales-card product-section"><div className="section-heading"><div><p className="eyebrow">商品目录</p><h2>单模块定价</h2><p>价格方案保存后，需将模块上架，才能用于单独购买和套餐组合。</p></div><span className="section-count">{loading ? '读取中' : `${modules.length} 个模块`}</span></div>
        {modules.length === 0 && !loading ? <div className="sales-empty">当前没有模块目录</div> : <div className="product-module-list">{modules.map(module => <article className="product-module" key={module.moduleCode}><header><div><h3>{module.name}</h3><p>{module.moduleCode} · {module.description}</p></div><div className="catalog-plan-actions"><span className={`catalog-status status-${module.saleStatus.toLowerCase()}`}>{module.saleStatus === 'ON_SALE' ? '在售' : module.saleStatus === 'DRAFT' ? '草稿' : '停售'}</span>{canManageModules && <button type="button" className="secondary-action" disabled={busy || (module.saleStatus === 'DRAFT' && !(priceDrafts[module.moduleCode] ?? module.prices ?? []).length)} onClick={() => void toggleModule(module)}>{module.saleStatus === 'ON_SALE' ? '停售' : module.saleStatus === 'DRAFT' ? '上架销售' : '重新上架'}</button>}</div></header>{module.saleStatus === 'DRAFT' && (priceDrafts[module.moduleCode] ?? module.prices ?? []).length > 0 && <p className="sales-empty-inline">价格已配置；上架后即可用于企业单模块购买和套餐组合。</p>}{canViewModules && renderPriceRows(module)}</article>)}</div>}
      </section>}
      {canViewPlans && section !== 'modules' && <section className="sales-card product-section"><div className="section-heading"><div><p className="eyebrow">组合商品</p><h2>套餐组合</h2><p>从模块定价选择已有商品组成套餐；停售只影响新的购买。</p></div><span className="section-count">{catalogPlans.length} 个套餐</span></div>
        {canManagePlans && <form className="plan-builder" onSubmit={event => void createPlan(event)}><div className="plan-builder-title"><h3>{editingPlanCode?'修改销售套餐':'创建销售套餐'}</h3><span>{editingPlanCode?'历史订单保持原购买快照':'提交后立即上架'}</span></div><div className="plan-builder-fields"><label>套餐编码<input aria-label="套餐编码" disabled={!!editingPlanCode} required pattern="[a-z][a-z0-9_]{0,39}" value={planDraft.code} onChange={event => setPlanDraft(row => ({ ...row, code: event.target.value }))} placeholder="如 care_basic" /></label><label>套餐名称<input aria-label="套餐名称" required maxLength={100} value={planDraft.name} onChange={event => setPlanDraft(row => ({ ...row, name: event.target.value }))} /></label><label>套餐价格（元）<input aria-label="套餐价格" required inputMode="decimal" value={planDraft.amount} onChange={event => setPlanDraft(row => ({ ...row, amount: event.target.value, minimumAmount: row.minimumAmount || event.target.value }))} /></label><label>最低成交价（元）<input aria-label="最低成交价" inputMode="decimal" value={planDraft.minimumAmount} onChange={event => setPlanDraft(row => ({ ...row, minimumAmount: event.target.value }))} /></label><label className="plan-description-field">套餐说明<input aria-label="套餐说明" required maxLength={1000} value={planDraft.description} onChange={event => setPlanDraft(row => ({ ...row, description: event.target.value }))} /></label></div>
          <div className="plan-add-row"><label>套餐模块<select aria-label="套餐模块" value={planItemChoice} onChange={event => setPlanItemChoice(event.target.value)}><option value="">选择模块与授权方案</option>{selectablePrices.map(({ module, price }) => <option key={priceKey(module.moduleCode, price)} value={priceKey(module.moduleCode, price)}>{module.name} · {priceLabel(price)}</option>)}</select></label><button type="button" className="secondary-action" disabled={!chosenPlanItem || planItems.some(item => item.moduleCode === chosenPlanItem.module.moduleCode)} onClick={() => chosenPlanItem && setPlanItems(rows => [...rows, { moduleCode: chosenPlanItem.module.moduleCode, moduleName: chosenPlanItem.module.name, billingMode: chosenPlanItem.price.billingMode, durationDays: chosenPlanItem.price.durationDays, storeQuota: chosenPlanItem.price.storeQuota }])}>添加到套餐</button></div>
          {!!planItems.length && <div className="plan-item-list">{planItems.map(item => <div key={item.moduleCode}><span>{item.moduleName} · {item.billingMode === 'PERPETUAL' ? '永久' : `${item.durationDays} 天`} · {item.storeQuota} 家门店</span><button type="button" className="text-action" onClick={() => setPlanItems(rows => rows.filter(row => row.moduleCode !== item.moduleCode))}>移除</button></div>)}</div>}
          <button className="primary-action" disabled={busy || planItems.length === 0}>{editingPlanCode?'保存套餐修改':'创建套餐'}</button>{editingPlanCode&&<button type="button" className="secondary-action" onClick={()=>{setEditingPlanCode(null);setPlanDraft({code:'',name:'',description:'',amount:'',minimumAmount:''});setPlanItems([])}}>取消修改</button>}
        </form>}
        {catalogPlans.length > 0 && <div className="catalog-plan-list">{catalogPlans.map(plan => <article className="catalog-plan" key={plan.planCode}><div><h3>{plan.name}</h3><p>{plan.planCode} · {plan.items.map(item => `${item.moduleName ?? item.moduleCode}（${item.billingMode === 'PERPETUAL' ? '永久' : `${item.durationDays} 天`}）`).join('、')}</p><strong>{money(plan.amount, plan.currency)}</strong></div><div className="catalog-plan-actions"><span className={`catalog-status status-${plan.saleStatus.toLowerCase()}`}>{plan.saleStatus === 'ON_SALE' ? '在售' : '已停售'}</span>{canManagePlans && <button type="button" className="secondary-action" disabled={busy} onClick={()=>{setEditingPlanCode(plan.planCode);setPlanDraft({code:plan.planCode,name:plan.name,description:plan.description,amount:String(plan.amount),minimumAmount:String(plan.minimumAmount)});setPlanItems(plan.items)}}>修改套餐</button>}{canManagePlans && <button type="button" className="secondary-action" disabled={busy} onClick={() => void togglePlan(plan)}>{plan.saleStatus === 'ON_SALE' ? '停售' : '重新上架'}</button>}</div></article>)}</div>}
        {!catalogPlans.length && !canManagePlans && <div className="sales-empty">当前没有已配置套餐</div>}
      </section>}
    </div> : <>
      <div className="sales-layout">
        <section className="sales-card sales-config"><div className="card-heading"><div className="card-icon"><Package size={23} /></div><div><p className="eyebrow">第一步</p><h2>选择企业与商品</h2></div></div>
          <label htmlFor="enterprise">企业<select id="enterprise" aria-label="企业" value={enterpriseId} onChange={event => { setEnterpriseId(event.target.value); clearPurchaseResult() }}><option value="">{loading ? '正在加载…' : '请选择企业'}</option>{enterprises.map(item => <option key={item.publicId} value={item.publicId}>{item.name} · {item.status === 'ACTIVE' ? '正常' : item.status}</option>)}</select></label>
          <fieldset className="purchase-mode"><legend>购买类型</legend><label><input type="radio" name="purchase-mode" value="plan" checked={purchaseMode === 'plan'} onChange={() => { setPurchaseMode('plan'); clearPurchaseResult() }} />套餐</label><label><input type="radio" name="purchase-mode" value="module" checked={purchaseMode === 'module'} onChange={() => { setPurchaseMode('module'); void refreshModuleCatalog() }} />单模块</label></fieldset>
          {purchaseMode === 'plan' ? <label htmlFor="plan">套餐<select id="plan" aria-label="套餐" value={planCode} onChange={event => { setPlanCode(event.target.value); clearPurchaseResult() }} disabled={!enterpriseId || !plans.length}><option value="">{enterpriseId && !plans.length ? '暂无可售套餐' : '请选择套餐'}</option>{plans.map(plan => <option key={plan.planCode} value={plan.planCode}>{plan.name} · {money(plan.amount, plan.currency)}</option>)}</select></label> : <label htmlFor="module-choice">购买模块<select id="module-choice" aria-label="购买模块" value={moduleChoice} onChange={event => { setModuleChoice(event.target.value); clearPurchaseResult() }}><option value="">{selectablePrices.length ? '请选择模块与授权方案' : '暂无可购买模块，请先在商品配置中设置价格并上架'}</option>{selectablePrices.map(({ module, price }) => <option key={priceKey(module.moduleCode, price)} value={priceKey(module.moduleCode, price)}>{module.name} · {priceLabel(price)}</option>)}</select></label>}
          {selectedPlan && purchaseMode === 'plan' && <div className="plan-preview"><div><strong>{selectedPlan.name}</strong><span>{selectedPlan.description}</span></div><b>{money(selectedPlan.amount, selectedPlan.currency)}</b></div>}
          {selectedModulePrice && purchaseMode === 'module' && <div className="plan-preview"><div><strong>{selectedModulePrice.module.name}</strong><span>{priceLabel(selectedModulePrice.price)} · 服务端将校验依赖与已有授权</span></div><b>{money(Number(selectedModulePrice.price.amount), selectedModulePrice.price.currency)}</b></div>}
          <button className="primary-action" onClick={() => void getQuote()} disabled={busy || !enterpriseId || (purchaseMode === 'plan' ? !selectedPlan : !selectedModulePrice)}><CurrencyCny size={18} />获取报价</button>
        </section>
        <section className="sales-card sales-result"><div className="card-heading"><div className="card-icon card-icon-warm"><CurrencyCny size={23} /></div><div><p className="eyebrow">第二步</p><h2>报价与履约</h2></div></div>
          {!quote && !order && <div className="sales-empty">选择企业和商品后，服务端报价会显示在这里。</div>}
          {quote && <div className="quote-panel"><div className="quote-total"><span>本次应付</span><strong>{money(quote.payableAmount, quote.currency)}</strong></div><dl><div><dt>标准金额</dt><dd>{money(quote.originalAmount, quote.currency)}</dd></div><div><dt>已有授权抵扣</dt><dd>- {money(quote.deductionAmount, quote.currency)}</dd></div><div><dt>授权企业</dt><dd>{selectedEnterprise?.name}</dd></div><div><dt>购买内容</dt><dd>{quote.planCode ? selectedPlan?.name ?? quote.planCode : quote.items.map(item => item.moduleName ?? item.moduleCode).join('、')}</dd></div></dl>{canManageOrders && !order && <button className="primary-action" onClick={() => void createOrder()} disabled={busy}>创建订单</button>}</div>}
          {order && <div className="order-panel"><div className="order-head"><div><span className="eyebrow">销售订单</span><strong>{order.orderNo}</strong></div><span className={`order-status order-${(order.status ?? 'unknown').toLowerCase()}`}>{orderStatus[order.status ?? ''] ?? order.status ?? '状态未知'}</span></div><p>企业：{selectedEnterprise?.name ?? order.enterpriseId} · 金额：{money(order.payableAmount)}</p>{order.status === 'PENDING_PAYMENT' && canManageOrders && <button className="primary-action" onClick={() => void payAndFulfill()} disabled={busy}>模拟支付并履约</button>}{order.status === 'FULFILLMENT_FAILED' && canManageOrders && <button className="secondary-action" onClick={() => void retryFulfillment()} disabled={busy}><ArrowClockwise size={17} />重试履约</button>}{order.status === 'FULFILLED' && <p className="success-line"><CheckCircle size={19} />订单已履约，企业下一步需同步许可证并确认安装。</p>}</div>}
        </section>
      </div>
      <footer className="sales-footnote">当前支付渠道为 MOCK 模拟支付，仅用于联调；接入微信、支付宝或银联前不会展示为真实收款。</footer>
    </>}
    {selectedOrder && <div className="modal-backdrop"><section className="modal-card" role="dialog" aria-modal="true" aria-labelledby="order-detail-title"><header><div><p className="eyebrow">订单信息</p><h2 id="order-detail-title">{selectedOrder.orderNo}</h2></div><button type="button" aria-label="关闭" onClick={() => setSelectedOrder(null)}>×</button></header><p>企业：{enterprises.find(item => item.publicId === selectedOrder.enterpriseId)?.name ?? selectedOrder.enterpriseId}</p><p>状态：{orderStatus[selectedOrder.status ?? ''] ?? selectedOrder.status} · 金额：{money(selectedOrder.payableAmount, selectedOrder.currency)}</p><h3>购买模块</h3>{selectedOrder.items.map((item,index)=><div className="order-module-item" key={`${item.moduleCode}-${index}`}><p>{item.moduleName ?? item.moduleCode} · {item.billingMode === 'PERPETUAL' ? '永久' : `${item.durationDays} 天`} · {item.storeQuota} 家门店 · 授权{item.entitlementResult==='REVOKED'?'已撤销':item.entitlementResult==='APPLIED'||item.entitlementResult==='RENEWED'||item.entitlementResult==='UPGRADED_PERPETUAL'?'有效':'未生效'}</p>{canManageOrders&&selectedOrder.status==='FULFILLED'&&['APPLIED','RENEWED','UPGRADED_PERPETUAL','REVOKED'].includes(item.entitlementResult??'')&&<button type="button" className="secondary-action" onClick={()=>setModuleAuthorizationAction({order:selectedOrder,item,restore:item.entitlementResult==='REVOKED'})}>{item.entitlementResult==='REVOKED'?'恢复授权':'撤销授权'}</button>}</div>)}<p className="order-authorization-note">撤销仅暂停该订单提供的模块授权，付款和订单历史保留；问题处理后可恢复，或企业重新购买。</p><h3>支付记录</h3>{selectedOrder.payments.length ? selectedOrder.payments.map(payment=><p key={payment.channelTransactionNo}>{payment.channel} · {payment.status} · {money(payment.amount, selectedOrder.currency)}</p>) : <p>暂无支付记录</p>}<h3>退款记录</h3>{selectedOrder.refunds?.length ? selectedOrder.refunds.map((refund,index)=><p key={index}>{refund.status} · {money(refund.amount, selectedOrder.currency)} · {refund.reason}</p>) : <p>暂无退款记录</p>}</section></div>}
    {moduleAuthorizationAction&&<div className="modal-backdrop"><section className="modal-card" role="dialog" aria-modal="true" aria-label={moduleAuthorizationAction.restore?'恢复模块授权':'撤销模块授权'}><header><div><p className="eyebrow">{moduleAuthorizationAction.order.orderNo}</p><h2>{moduleAuthorizationAction.restore?'恢复模块授权':'撤销模块授权'}</h2></div><button type="button" aria-label="关闭" onClick={()=>setModuleAuthorizationAction(null)}>×</button></header><p>{moduleAuthorizationAction.item.moduleName??moduleAuthorizationAction.item.moduleCode} · 订单付款与历史记录不会被修改。</p><form onSubmit={event=>void submitModuleAuthorizationAction(event)}><label>操作原因<textarea name="reason" aria-label="操作原因" required maxLength={500} rows={3}/></label><footer><button type="button" className="secondary-action" onClick={()=>setModuleAuthorizationAction(null)}>返回</button><button type="submit" className="primary-action" disabled={busy}>{busy?'提交中…':moduleAuthorizationAction.restore?'确认恢复':'确认撤销'}</button></footer></form></section></div>}
    {editingOrder && <div className="modal-backdrop"><section className="modal-card" role="dialog" aria-modal="true" aria-labelledby="edit-order-title"><header><div><p className="eyebrow">仅限待支付订单</p><h2 id="edit-order-title">修改 {editingOrder.orderNo}</h2></div><button type="button" aria-label="关闭" onClick={() => setEditingOrder(null)}>×</button></header><p>修改后会按当前在售价格重新计算订单金额，并保留原订单编号。</p><form onSubmit={event => void saveOrderEdit(event)}><label>模块与授权方案<select name="price" required defaultValue="">{selectablePrices.map(({module,price})=><option key={priceKey(module.moduleCode,price)} value={priceKey(module.moduleCode,price)}>{module.name} · {priceLabel(price)}</option>)}</select></label><footer><button type="button" className="secondary-action" onClick={() => setEditingOrder(null)}>取消</button><button type="submit" className="primary-action" disabled={busy}>{busy?'保存中…':'重新计价并保存'}</button></footer></form></section></div>}
  </section>
}
