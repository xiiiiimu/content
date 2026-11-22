/** Mirrors PostAuthoringService.normalizeTopic on the backend (hardening D4). */
export const MAX_TOPIC_LENGTH = 128
export type TopicCheck = { ok: true, value: string | null } | { ok: false, code: 'INVALID_TOPIC' }

// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001F\u007F-\u009F]/

/** Optional topic: trimmed, blank means none, at most 128 characters (UTF-16 units, as Java String.length), no control characters. */
export function checkTopic(input: string | null | undefined): TopicCheck {
  if (input == null) return { ok: true, value: null }
  const t = input.trim()
  if (!t) return { ok: true, value: null }
  if (t.length > MAX_TOPIC_LENGTH || CONTROL.test(t)) return { ok: false, code: 'INVALID_TOPIC' }
  return { ok: true, value: t }
}

/** POST /api/posts body: the topic is sent only when set. Throws on an invalid topic. */
export function createPayload(body: string, topic: string): { body: string, topic?: string } {
  const c = checkTopic(topic)
  if (!c.ok) throw new Error(c.code)
  return c.value == null ? { body } : { body, topic: c.value }
}

/**
 * PATCH /api/posts/{id} body. The backend keeps the stored topic when "topic" is absent, replaces it with a string and
 * clears it with null, so: blank input keeps it, a value replaces it, `clear` sends null. Throws on an invalid topic.
 */
export function editPayload(body: string, expectedVersion: number, topic: string, clear: boolean): { body: string, expectedVersion: number, topic?: string | null } {
  if (clear) return { body, expectedVersion, topic: null }
  const c = checkTopic(topic)
  if (!c.ok) throw new Error(c.code)
  return c.value == null ? { body, expectedVersion } : { body, expectedVersion, topic: c.value }
}

/**
 * PATCH body for an edit form pre-filled with the post's current topic (GET /api/posts/{id} returns it).
 * Unchanged keeps the stored topic (no "topic"), a different value replaces it, a blank input clears it (null).
 * Throws on an invalid topic.
 */
export function editPayloadFromLoaded(body: string, expectedVersion: number, loaded: string | null, input: string): { body: string, expectedVersion: number, topic?: string | null } {
  const c = checkTopic(input)
  if (!c.ok) throw new Error(c.code)
  if (c.value === (loaded ?? null)) return { body, expectedVersion }
  return { body, expectedVersion, topic: c.value }
}
