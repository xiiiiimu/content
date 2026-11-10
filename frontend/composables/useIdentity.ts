const STORAGE_KEY = 'content.identity.token'

function decodePayload(token: string): { reader?: number, expiresAt?: number, role?: string } | null {
  try {
    const parts = token.split('.')
    if (parts.length < 2) return null
    const json = atob(parts[1]!.replace(/-/g, '+').replace(/_/g, '/'))
    return JSON.parse(json) as { reader?: number, expiresAt?: number, role?: string }
  }
  catch {
    return null
  }
}

/** Browser session for OS-minted HMAC Bearer. Signing keys never live here. */
export function useIdentity() {
  const token = useState<string | null>('content-identity-token', () => null)
  const hydrated = useState('content-identity-hydrated', () => false)

  function hydrate() {
    if (import.meta.server || hydrated.value) return
    token.value = localStorage.getItem(STORAGE_KEY)
    hydrated.value = true
  }

  function setToken(value: string | null) {
    token.value = value && value.trim() ? value.trim() : null
    if (import.meta.client) {
      if (token.value) localStorage.setItem(STORAGE_KEY, token.value)
      else localStorage.removeItem(STORAGE_KEY)
    }
  }

  const claims = computed(() => (token.value ? decodePayload(token.value) : null))
  const readerId = computed(() => claims.value?.reader ?? null)
  const role = computed(() => claims.value?.role || (token.value ? 'READER' : null))
  const expiresAt = computed(() => claims.value?.expiresAt ?? null)
  const isOps = computed(() => role.value === 'TRENDGUARD_OPS')
  const expired = computed(() => {
    const exp = expiresAt.value
    return exp != null && exp <= Date.now()
  })

  return { token, hydrated, hydrate, setToken, claims, readerId, role, expiresAt, isOps, expired }
}
