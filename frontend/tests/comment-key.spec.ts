import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { createCommentKey } from '~/composables/commentKey'

function sequence() {
  let n = 0
  return () => `k${++n}`
}

describe('comment idempotency key (D4)', () => {
  it('retries of one comment reuse the key; the next comment gets a new key', async () => {
    const holder = createCommentKey('ui', sequence())
    const opened = holder.current
    const sent: string[] = []
    const failing = async (key: string) => { sent.push(key); throw new Error('network') }
    await expect(holder.send(failing)).rejects.toThrow('network')
    await expect(holder.send(failing)).rejects.toThrow('network')
    await holder.send(async (key) => { sent.push(key); return 'ok' })
    expect(sent).toEqual([opened, opened, opened])
    const second = holder.current
    expect(second).not.toBe(opened)
    const next: string[] = []
    await holder.send(async (key) => { next.push(key) })
    expect(next).toEqual([second])
  })

  it('reset gives a new key when the box now targets another post', async () => {
    const holder = createCommentKey('web', sequence())
    const first = holder.current
    await expect(holder.send(async () => { throw new Error('network') })).rejects.toThrow()
    holder.reset()
    expect(holder.current).not.toBe(first)
  })

  it('the key is created once when the box opens, not on every read', () => {
    const holder = createCommentKey('ui', sequence())
    expect(holder.current).toBe(holder.current)
  })

  it('default keys are random UUIDs within the 128-char backend limit', () => {
    const a = createCommentKey('assistant')
    const b = createCommentKey('assistant')
    expect(a.current).toMatch(/^assistant-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(a.current).not.toBe(b.current)
    expect(a.current.length).toBeLessThanOrEqual(128)
  })

  it('pages no longer build idempotency keys from Date.now()', () => {
    for (const page of ['pages/posts/[id].vue', 'pages/hot-topics/[topic]/[post].vue']) {
      const src = readFileSync(resolve(__dirname, '..', page), 'utf8')
      expect(src, page).not.toMatch(/-\$\{Date\.now\(\)\}`/)
      expect(src, page).toContain('createCommentKey(')
    }
  })
})
