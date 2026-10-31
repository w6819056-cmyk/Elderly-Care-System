const TOKEN_KEY = 'tenant.access_token'
const PRINCIPAL_KEY = 'tenant.principal'
const EXPIRY_KEY = 'tenant.expires_at'

export const TENANT_SESSION_EXPIRED_EVENT = 'tenant-session-expired'

export interface TenantStaffPrincipal {
  staffId: number
  staffPublicId: string
  username: string
  staffType: 'HQ' | 'STORE'
  homeStoreId: number | null
  accessContext: {
    permissionCodes: string[]
    dataScope?: { type: string; storeIds: number[] }
  }
}

export function getTenantToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getTenantPrincipal(): TenantStaffPrincipal | null {
  const raw = localStorage.getItem(PRINCIPAL_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as TenantStaffPrincipal
  } catch {
    clearTenantSession()
    return null
  }
}

export function saveTenantSession(token: string, expiresAt: string, principal: TenantStaffPrincipal): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(EXPIRY_KEY, expiresAt)
  localStorage.setItem(PRINCIPAL_KEY, JSON.stringify(principal))
}

export function clearTenantSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(EXPIRY_KEY)
  localStorage.removeItem(PRINCIPAL_KEY)
}
