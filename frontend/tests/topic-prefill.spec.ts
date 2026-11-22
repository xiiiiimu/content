import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { editPayloadFromLoaded } from '~/composables/topicInput'

describe('edit page pre-filled with the current topic', () => {
  it('unchanged topic is not sent, so the stored topic is kept', () => {
    expect(editPayloadFromLoaded('b', 2, '音乐', '音乐')).toEqual({ body: 'b', expectedVersion: 2 })
    expect(editPayloadFromLoaded('b', 2, '音乐', '  音乐 ')).toEqual({ body: 'b', expectedVersion: 2 })
    expect(editPayloadFromLoaded('b', 2, null, '')).toEqual({ body: 'b', expectedVersion: 2 })
  })

  it('a changed topic replaces it and a blank input clears it', () => {
    expect(editPayloadFromLoaded('b', 2, '音乐', '城市')).toEqual({ body: 'b', expectedVersion: 2, topic: '城市' })
    expect(editPayloadFromLoaded('b', 2, null, '城市')).toEqual({ body: 'b', expectedVersion: 2, topic: '城市' })
    expect(editPayloadFromLoaded('b', 2, '音乐', '')).toEqual({ body: 'b', expectedVersion: 2, topic: null })
    expect(editPayloadFromLoaded('b', 2, '音乐', '   ')).toEqual({ body: 'b', expectedVersion: 2, topic: null })
  })

  it('rejects an invalid topic', () => {
    expect(() => editPayloadFromLoaded('b', 2, '音乐', 'a'.repeat(129))).toThrow('INVALID_TOPIC')
  })

  it('the edit page fills the input from the loaded post and builds the PATCH from it', () => {
    const src = readFileSync(resolve(__dirname, '../pages/posts/edit/[id].vue'), 'utf8')
    expect(src).toMatch(/topic\.value\s*=\s*p\.topic\s*\?\?\s*''/)
    expect(src).toContain('editPayloadFromLoaded(')
  })
})
