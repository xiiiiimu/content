/** Content API ViewModels — no Mastodon Status forging. */

export type PageState = 'HAS_MORE' | 'UNKNOWN' | 'EXHAUSTED'

export interface TrendingTopic {
  topic: string
  score: number
}

export interface ContentPost {
  postId: number
  authorId: number
  publishedAtMs: number
  body: string
  version: number
  status: string
}

export interface FeedItem {
  postId: number
  publishedAtMs: number
  body: string
}

export interface FeedPage {
  items: FeedItem[]
  nextCursor: string | null
  pageState: PageState
  scanned: number
}

export interface LikeResult {
  liked: boolean
  likeCount: number
}

export interface CommentResult {
  commentId: number
  commentCount: number
  replay: boolean
}

export interface PostStats {
  postId: number
  likes: number
  comments: number
}

export interface FollowResult {
  following: boolean
  epoch: number
  graphVersion: number
}

export interface SignedMedia {
  url: string
  expiresAtMs: number
}

export interface RiskEvent {
  eventId: string
  schemaVersion: number
  topic: string
  kind: string
  occurredAtMs: number
  postId: number | null
  detail: string
}

export interface ApiErrorBody {
  code?: string
}

export class ContentApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message?: string,
  ) {
    super(message || code)
    this.name = 'ContentApiError'
  }

  get isAuth(): boolean {
    return this.status === 401 || this.code === 'AUTHENTICATION_REQUIRED' || this.code === 'UNAUTHENTICATED'
  }

  get isForbidden(): boolean {
    return this.status === 403 || this.code === 'FORBIDDEN'
  }
}
