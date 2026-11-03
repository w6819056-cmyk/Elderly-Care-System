const TOKEN_KEY = 'platform.access_token'
const PRINCIPAL_KEY = 'platform.principal'
const EXPIRY_KEY = 'platform.expires_at'

export interface PlatformPrincipal {
  userId: number
  publicId: string
  username: string
  roleCodes: string[]
  permissionCodes: string[]
}

export function getPlatformToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getPlatformPrincipal(): PlatformPrincipal | null {
  const raw = localStorage.getItem(PRINCIPAL_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as PlatformPrincipal
  } catch {
    clearPlatformSession()
    return null
  }
}

export function savePlatformSession(token: string, expiresAt: string, principal: PlatformPrincipal): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(EXPIRY_KEY, expiresAt)
  localStorage.setItem(PRINCIPAL_KEY, JSON.stringify(principal))
}

export function clearPlatformSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(EXPIRY_KEY)
  localStorage.removeItem(PRINCIPAL_KEY)
}
