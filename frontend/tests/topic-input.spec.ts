import { describe, expect, it } from 'vitest'
import { checkTopic, createPayload, editPayload, MAX_TOPIC_LENGTH } from '~/composables/topicInput'

describe('topic input (D4, mirrors PostAuthoringService.normalizeTopic)', () => {
  it('is optional: blank means no topic', () => {
    expect(checkTopic('')).toEqual({ ok: true, value: null })
    expect(checkTopic('   ')).toEqual({ ok: true, value: null })
    expect(checkTopic(undefined)).toEqual({ ok: true, value: null })
  })

  it('trims and accepts up to 128 characters, rejects longer or control characters', () => {
    expect(MAX_TOPIC_LENGTH).toBe(128)
    expect(checkTopic('  雨夜素材 ')).toEqual({ ok: true, value: '雨夜素材' })
    expect(checkTopic('a'.repeat(128))).toEqual({ ok: true, value: 'a'.repeat(128) })
    expect(checkTopic(` ${'a'.repeat(128)} `)).toEqual({ ok: true, value: 'a'.repeat(128) })
    expect(checkTopic('a'.repeat(129))).toEqual({ ok: false, code: 'INVALID_TOPIC' })
    expect(checkTopic('a\tb')).toEqual({ ok: false, code: 'INVALID_TOPIC' })
  })

  it('create sends topic only when set', () => {
    expect(createPayload('hello', '')).toEqual({ body: 'hello' })
    expect(createPayload('hello', ' 城市 ')).toEqual({ body: 'hello', topic: '城市' })
  })

  it('edit keeps the stored topic when blank, replaces it when set, and clears it with null', () => {
    expect(editPayload('b', 3, '', false)).toEqual({ body: 'b', expectedVersion: 3 })
    expect('topic' in editPayload('b', 3, '', false)).toBe(false)
    expect(editPayload('b', 3, '新话题', false)).toEqual({ body: 'b', expectedVersion: 3, topic: '新话题' })
    expect(editPayload('b', 3, '', true)).toEqual({ body: 'b', expectedVersion: 3, topic: null })
    expect(editPayload('b', 3, 'ignored', true)).toEqual({ body: 'b', expectedVersion: 3, topic: null })
  })
})
