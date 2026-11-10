import type {
  CommentResult,
  ContentPost,
  FeedPage,
  FollowResult,
  LikeResult,
  PageState,
  PostStats,
  RiskEvent,
  SignedMedia,
  TrendingTopic,
} from '~/types/content'
import { ContentApiError } from '~/types/content'

function apiBase(): string {
  const config = useRuntimeConfig()
  const base = (config.public.contentApiBase as string) || ''
  return base.replace(/\/+$/, '')
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const { token, hydrate } = useIdentity()
  hydrate()
  const headers = new Headers(init.headers || {})
  if (token.value) headers.set('Authorization', `Bearer ${token.value}`)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')

  const res = await fetch(`${apiBase()}${path}`, { ...init, headers })
  if (res.status === 204) return undefined as T
  const text = await res.text()
  let body: unknown = null
  if (text) {
    try { body = JSON.parse(text) }
    catch { body = { code: text } }
  }
  if (!res.ok) {
    const code = (body as { code?: string } | null)?.code || `HTTP_${res.status}`
    throw new ContentApiError(res.status, code)
  }
  return body as T
}

export function useContentApi() {
  return {
    trending(window: '1h' | '24h' = '1h', metric: 'net_events' | 'active_interactions' = 'net_events') {
      const q = new URLSearchParams({ window, metric })
      return request<TrendingTopic[]>(`/api/topics/trending?${q}`)
    },
    topicPosts(topic: string, limit = 50) {
      const q = new URLSearchParams({ limit: String(limit) })
      return request<ContentPost[]>(`/api/topics/${encodeURIComponent(topic)}/posts?${q}`)
    },
    post(id: number) {
      return request<ContentPost>(`/api/posts/${id}`)
    },
    stats(postId: number) {
      return request<PostStats>(`/api/posts/${postId}/stats`)
    },
    like(postId: number, topic?: string) {
      const q = topic ? `?topic=${encodeURIComponent(topic)}` : ''
      return request<LikeResult>(`/api/posts/${postId}/likes${q}`, { method: 'PUT' })
    },
    unlike(postId: number, topic?: string) {
      const q = topic ? `?topic=${encodeURIComponent(topic)}` : ''
      return request<LikeResult>(`/api/posts/${postId}/likes${q}`, { method: 'DELETE' })
    },
    comment(postId: number, body: string, idempotencyKey: string, topic?: string) {
      return request<CommentResult>(`/api/posts/${postId}/comments`, {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify({ body, topic }),
      })
    },
    follow(authorId: number) {
      return request<FollowResult>(`/api/follows/${authorId}`, { method: 'PUT' })
    },
    unfollow(authorId: number) {
      return request<FollowResult>(`/api/follows/${authorId}`, { method: 'DELETE' })
    },
    followingFeed(limit = 20, cursor?: string | null) {
      const q = new URLSearchParams({ limit: String(limit) })
      if (cursor) q.set('cursor', cursor)
      return request<FeedPage>(`/api/feed/following?${q}`)
    },
    signMedia(postId: number, ttlMillis = 60_000) {
      return request<SignedMedia>(`/api/posts/${postId}/media/sign`, {
        method: 'POST',
        body: JSON.stringify({ ttlMillis }),
      })
    },
    risks(opts: {
      window?: '1h' | '24h'
      topic?: string
      limit?: number
      beforeMs?: number
      beforeEventId?: string
    } = {}) {
      const q = new URLSearchParams({ window: opts.window || '1h', limit: String(opts.limit ?? 50) })
      if (opts.topic) q.set('topic', opts.topic)
      if (opts.beforeMs != null && opts.beforeEventId) {
        q.set('beforeMs', String(opts.beforeMs))
        q.set('beforeEventId', opts.beforeEventId)
      }
      return request<RiskEvent[]>(`/api/trendguard/risks?${q}`)
    },
  }
}

export type { PageState }
