/** RFC 4122 v4 UUID. crypto.randomUUID is missing outside secure contexts, so fall back to getRandomValues. */
export function randomUuid(): string {
  const c = globalThis.crypto
  if (typeof c?.randomUUID === 'function') return c.randomUUID()
  const b = c.getRandomValues(new Uint8Array(16))
  b[6] = (b[6]! & 0x0F) | 0x40
  b[8] = (b[8]! & 0x3F) | 0x80
  const h = Array.from(b, x => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

/**
 * Idempotency key for one comment box (hardening D4). The key is created once when the box is opened, every retry
 * of a failed send reuses it (so the backend replays instead of duplicating), and only a successful send rotates it
 * for the next comment. Note: the backend replays by key alone, so editing the text after a failed send whose
 * response was lost returns the original comment.
 */
export function createCommentKey(prefix: string, gen: () => string = randomUuid) {
  let key = `${prefix}-${gen()}`
  return {
    get current() { return key },
    async send<T>(fn: (key: string) => Promise<T>): Promise<T> {
      const used = key
      const result = await fn(used)
      if (key === used) key = `${prefix}-${gen()}`
      return result
    },
    /** New key for a different target (e.g. the page now shows another post): keys are unique per user, not per post. */
    reset() { key = `${prefix}-${gen()}` },
  }
}
