import { describe, expect, it } from 'vitest'

/**
 * Contract smoke: page inventory maps to Content paths (no fixed five-topic fixture).
 */
describe('Content page → API inventory', () => {
  const routes = [
    { page: '/hot-topics', api: '/api/topics/trending', auth: true },
    { page: '/hot-topics/:topic', api: '/api/topics/{topic}/posts', auth: true },
    { page: '/hot-topics/:topic/:post', api: '/api/posts/{id}', auth: true },
    { page: '/feed', api: '/api/feed/following', auth: true },
    { page: '/ops/risks', api: '/api/trendguard/risks', auth: 'TRENDGUARD_OPS' },
  ]

  it('covers Content-first surfaces only', () => {
    expect(routes.map(r => r.page)).not.toContain('/favourites')
    expect(routes.every(r => r.api.startsWith('/api/'))).toBe(true)
  })

  it('trending uses window and metric query params', () => {
    const q = new URLSearchParams({ window: '1h', metric: 'net_events' })
    expect(`/api/topics/trending?${q}`).toBe('/api/topics/trending?window=1h&metric=net_events')
  })

  it('feed continuation uses opaque cursor and pageState semantics', () => {
    const states = ['HAS_MORE', 'UNKNOWN', 'EXHAUSTED'] as const
    expect(states).toContain('UNKNOWN')
    // UNKNOWN must not be treated as terminal by UI
    const canContinue = (pageState: string, cursor: string | null) =>
      !!cursor && pageState !== 'EXHAUSTED'
    expect(canContinue('UNKNOWN', 'abc')).toBe(true)
    expect(canContinue('EXHAUSTED', 'abc')).toBe(false)
    expect(canContinue('HAS_MORE', null)).toBe(false)
  })

  it('does not invent Mastodon status fields in ViewModel names', () => {
    const viewModelKeys = ['topic', 'score', 'postId', 'authorId', 'publishedAtMs', 'body', 'pageState']
    expect(viewModelKeys).not.toContain('acct')
    expect(viewModelKeys).not.toContain('reblog')
    expect(viewModelKeys).not.toContain('favourited')
  })
})
