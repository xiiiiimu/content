import type {
  CommentResult,
  ContentPost,
  FeedPage,
  FollowResult,
  LikeResult,
  MaterialDetail,
  MaterialDiscoverPage,
  MaterialSigned,
  MaterialUnlock,
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
    comment(postId: number, body: string, idempotencyKey: string, topic?: string, opts?: { parentId?: number; versionId?: number; offsetMs?: number }) {
      return request<CommentResult>(`/api/posts/${postId}/comments`, {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify({
          body,
          topic,
          parentId: opts?.parentId,
          versionId: opts?.versionId,
          offsetMs: opts?.offsetMs,
        }),
      })
    },
    dislike(postId: number) {
      return request<{ disliked: boolean; dislikeCount: number }>(`/api/posts/${postId}/dislikes`, { method: 'PUT' })
    },
    report(postId: number, reason: string, detail?: string, context?: Record<string, unknown>) {
      return request<{ reportId: number; reportCount: number }>(`/api/posts/${postId}/reports`, {
        method: 'POST',
        body: JSON.stringify({ reason, detail, context }),
      })
    },
    discoverMaterials(opts: { q?: string; tag?: string; beforeMs?: number; beforeId?: number; limit?: number } = {}) {
      const q = new URLSearchParams({ limit: String(opts.limit ?? 20) })
      if (opts.q) q.set('q', opts.q)
      if (opts.tag) q.set('tag', opts.tag)
      if (opts.beforeMs != null && opts.beforeId != null) {
        q.set('beforeMs', String(opts.beforeMs))
        q.set('beforeId', String(opts.beforeId))
      }
      return request<MaterialDiscoverPage>(`/api/materials/discover?${q}`)
    },
    material(postId: number) {
      return request<MaterialDetail>(`/api/materials/${postId}`)
    },
    signMaterialPreview(postId: number, versionId?: number, ttlMillis = 60_000) {
      return request<MaterialSigned>(`/api/materials/${postId}/preview/sign`, {
        method: 'POST',
        body: JSON.stringify({ versionId, ttlMillis }),
      })
    },
    unlockMaterial(postId: number, password: string) {
      return request<MaterialUnlock>(`/api/materials/${postId}/unlock`, {
        method: 'POST',
        body: JSON.stringify({ password }),
      })
    },
    signMaterialOriginal(postId: number, grant: string, versionId?: number, ttlMillis = 60_000) {
      return request<MaterialSigned>(`/api/materials/${postId}/original/sign`, {
        method: 'POST',
        body: JSON.stringify({ grant, versionId, ttlMillis }),
      })
    },
    acceptComment(postId: number, commentId: number) {
      return request<CommentResult>(`/api/posts/${postId}/comments/${commentId}/accept`, { method: 'POST' })
    },
    favorite(postId: number) {
      return request<{ favorited: boolean; favoriteCount: number }>(`/api/posts/${postId}/favorite`, { method: 'POST' })
    },
    unfavorite(postId: number) {
      return request<{ favorited: boolean; favoriteCount: number }>(`/api/posts/${postId}/favorite`, { method: 'DELETE' })
    },
    subscribeFeedback(postId: number, commentId: number) {
      return request<{ ok: boolean; message: string }>(`/api/materials/${postId}/feedback/${commentId}/subscribe`, { method: 'POST' })
    },
    cancelFeedbackSubscribe(postId: number, commentId: number) {
      return request<{ ok: boolean }>(`/api/materials/${postId}/feedback/${commentId}/subscribe`, { method: 'DELETE' })
    },
    resolveFeedback(postId: number, commentId: number, versionId: number, offsetMs?: number) {
      return request<{ ok: boolean }>(`/api/materials/${postId}/feedback/${commentId}/resolve`, {
        method: 'POST',
        body: JSON.stringify({ versionId, offsetMs }),
      })
    },
    notifications(limit = 50) {
      return request<Array<{ id: number; kind: string; postId: number; commentId: number; refVersionId?: number }>>(`/api/notifications?limit=${limit}`)
    },
    notificationTarget(id: number) {
      return request<{ postId: number; commentId: number; refVersionId?: number }>(`/api/notifications/${id}/target`)
    },
    assistantTask(id: number) {
      return request<{ taskId: number; state: string; intent?: string; replyCommentId?: number }>(`/api/assistant/tasks/${id}`)
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
