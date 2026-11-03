const TOKEN_KEY = 'ec_tenant_staff_token'
const PRINCIPAL_KEY = 'ec_tenant_staff_principal'

export interface TenantPrincipal {
  staffId: number
  staffPublicId: string
  username: string
  staffType: 'HQ' | 'STORE'
  accessContext: { permissionCodes: string[] }
}

export interface TenantModule {
  moduleCode: string
  licenseType: string
  licenseStatus: string
  effectiveState: string
  startsAt: string
  expiresAt: string | null
  perpetual: boolean
  licenseVersion: number
  storeQuota: number
  assignedStores: number
  enabled: boolean
}

export interface TenantStore {
  publicId: string
  storeNo: string
  name: string
  status: string
}

export interface ModuleAssignment {
  publicId: string
  moduleCode: string
  storePublicId: string
  storeName: string
  status: string
  idempotencyKey: string | null
}

export interface LicenseStatus {
  licenseId: string
  version: number
  lastSuccessfulSyncAt: string
  instanceStatus: string
  modules: unknown[]
}

interface Envelope<T> { code: string; message: string; data: T }

export function getTenantToken() { return localStorage.getItem(TOKEN_KEY) }
export function getTenantPrincipal(): TenantPrincipal | null {
  const raw = localStorage.getItem(PRINCIPAL_KEY)
  return raw ? JSON.parse(raw) as TenantPrincipal : null
}
export function clearTenantSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(PRINCIPAL_KEY)
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(getTenantToken() ? { Authorization: `Bearer ${getTenantToken()}` } : {}),
      ...init.headers
    }
  })
  const body = await response.json() as Envelope<T>
  if (!response.ok || body.code !== 'OK') {
    if (response.status === 401) clearTenantSession()
    throw new Error(body.message || `请求失败 (${response.status})`)
  }
  return body.data
}

export async function tenantLogin(account: string, password: string) {
  const result = await request<{ accessToken: string; principal: TenantPrincipal }>(
    '/api/tenant/v1/auth/login', { method: 'POST', body: JSON.stringify({ account, password }) }
  )
  localStorage.setItem(TOKEN_KEY, result.accessToken)
  localStorage.setItem(PRINCIPAL_KEY, JSON.stringify(result.principal))
  return result.principal
}

export const tenantModulesApi = {
  modules: () => request<TenantModule[]>('/api/tenant/v1/hq/modules'),
  stores: () => request<TenantStore[]>('/api/tenant/v1/hq/module-stores'),
  license: () => request<LicenseStatus>('/api/tenant/v1/hq/license/status'),
  assignments: (code: string) => request<ModuleAssignment[]>(`/api/tenant/v1/hq/modules/${encodeURIComponent(code)}/assignments`),
  activation: (code: string, enabled: boolean) => request<void>(`/api/tenant/v1/hq/modules/${encodeURIComponent(code)}/activation`, {
    method: 'PUT', body: JSON.stringify({ enabled })
  }),
  assign: (code: string, storePublicId: string) => request<ModuleAssignment>(
    `/api/tenant/v1/hq/modules/${encodeURIComponent(code)}/assignments`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ storePublicId })
    }
  ),
  revoke: (code: string, storePublicId: string) => request<void>(
    `/api/tenant/v1/hq/modules/${encodeURIComponent(code)}/assignments/${encodeURIComponent(storePublicId)}`,
    { method: 'DELETE' }
  )
}
