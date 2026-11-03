import { request } from './client'
import type { TenantStaffPrincipal } from './session'

export interface TenantLoginResult {
  accessToken: string
  tokenType: string
  expiresAt: string
  principal: TenantStaffPrincipal
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
  installed: boolean
  installedVersion: string | null
}

export interface TenantStoreOption {
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

export interface TenantModuleMenu {
  moduleCode: string
  key: string
  label: string
  route: string
  icon: string
  state: string
}

export const tenantApi = {
  hqLogin: (account: string, password: string) => request<TenantLoginResult>('/api/tenant/v1/auth/login', {
    method: 'POST', body: JSON.stringify({ account, password })
  }),
  me: () => request<TenantStaffPrincipal>('/api/tenant/v1/auth/me'),
  menus: () => request<TenantModuleMenu[]>('/api/tenant/v1/modules/menus'),
  modules: () => request<TenantModule[]>('/api/tenant/v1/hq/modules'),
  stores: () => request<TenantStoreOption[]>('/api/tenant/v1/hq/module-stores'),
  license: () => request<LicenseStatus>('/api/tenant/v1/hq/license/status'),
  assignments: (moduleCode: string) => request<ModuleAssignment[]>(
    `/api/tenant/v1/hq/modules/${encodeURIComponent(moduleCode)}/assignments`
  ),
  activation: (moduleCode: string, enabled: boolean) => request<void>(
    `/api/tenant/v1/hq/modules/${encodeURIComponent(moduleCode)}/activation`, {
      method: 'PUT', body: JSON.stringify({ enabled })
    }
  ),
  assign: (moduleCode: string, storePublicId: string) => request<ModuleAssignment>(
    `/api/tenant/v1/hq/modules/${encodeURIComponent(moduleCode)}/assignments`, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ storePublicId })
    }
  ),
  revoke: (moduleCode: string, storePublicId: string) => request<void>(
    `/api/tenant/v1/hq/modules/${encodeURIComponent(moduleCode)}/assignments/${encodeURIComponent(storePublicId)}`,
    { method: 'DELETE' }
  )
}
