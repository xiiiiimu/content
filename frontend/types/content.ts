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
  parentId?: number | null
  materialVersionId?: number | null
  offsetMs?: number | null
  acceptedAtMs?: number | null
}

export interface PostStats {
  postId: number
  likes: number
  comments: number
  dislikes?: number
  reports?: number
}

export interface MaterialDiscoverItem {
  postId: number
  authorId: number
  publishedAtMs: number
  title: string
  description: string
  tags: string[]
  shareMode: string
  coverAvailable: boolean
  coverUrl: string | null
  coverExpiresAtMs: number | null
}

export interface MaterialDiscoverPage {
  items: MaterialDiscoverItem[]
  nextBeforeMs: number | null
  nextBeforeId: number | null
}

export interface MaterialVersionView {
  versionId: number
  revision: number
  state: string
  durationMs: number | null
  isDefault: boolean
}

export interface MaterialDetail {
  postId: number
  title: string
  description: string
  shareMode: string
  licenseDownload: boolean
  licenseCommercial: boolean
  defaultVersionId: number | null
  tags: string[]
  versions: MaterialVersionView[]
}

export interface MaterialSigned {
  url: string
  expiresAtMs: number
  purpose: string
}

export interface MaterialUnlock {
  grant: string
  expiresAtMs: number
  shareEpoch: number
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
