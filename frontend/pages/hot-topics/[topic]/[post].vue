<script setup lang="ts">
import type { ContentPost, PostStats, SignedMedia } from '~/types/content'
import { ContentApiError } from '~/types/content'

const route = useRoute()
const topic = computed(() => decodeURIComponent(String(route.params.topic || '')))
const postId = computed(() => Number(route.params.post))

const api = useContentApi()
const { hydrate, token, expired } = useIdentity()

const post = ref<ContentPost | null>(null)
const stats = ref<PostStats | null>(null)
const media = ref<SignedMedia | null>(null)
const mediaError = ref('')
const status = ref<'loading' | 'ok' | 'error' | 'auth'>('loading')
const errorCode = ref('')
const busy = ref(false)
const commentBody = ref('')
const followState = ref<boolean | null>(null)

useHead({ title: () => `帖子 ${postId.value} · Content` })

async function load() {
  hydrate()
  if (!token.value || expired.value) {
    status.value = 'auth'
    post.value = null
    return
  }
  status.value = 'loading'
  try {
    post.value = await api.post(postId.value)
    try { stats.value = await api.stats(postId.value) }
    catch { stats.value = null }
    media.value = null
    mediaError.value = ''
    try { media.value = await api.signMedia(postId.value) }
    catch (e) {
      mediaError.value = e instanceof ContentApiError ? e.code : 'MEDIA_UNAVAILABLE'
    }
    status.value = 'ok'
  }
  catch (e) {
    const err = e instanceof ContentApiError ? e : new ContentApiError(500, 'UNKNOWN')
    errorCode.value = err.code
    status.value = err.isAuth ? 'auth' : 'error'
    post.value = null
  }
}

async function doLike() {
  if (!post.value || busy.value) return
  busy.value = true
  try {
    const r = await api.like(post.value.postId, topic.value)
    stats.value = { postId: post.value.postId, likes: r.likeCount, comments: stats.value?.comments ?? 0 }
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'LIKE_FAILED'
  }
  finally { busy.value = false }
}

async function doUnlike() {
  if (!post.value || busy.value) return
  busy.value = true
  try {
    const r = await api.unlike(post.value.postId, topic.value)
    stats.value = { postId: post.value.postId, likes: r.likeCount, comments: stats.value?.comments ?? 0 }
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'UNLIKE_FAILED'
  }
  finally { busy.value = false }
}

async function sendComment() {
  if (!post.value || !commentBody.value.trim() || busy.value) return
  busy.value = true
  try {
    const key = `web-${post.value.postId}-${Date.now()}`
    const r = await api.comment(post.value.postId, commentBody.value.trim(), key, topic.value)
    stats.value = { postId: post.value.postId, likes: stats.value?.likes ?? 0, comments: r.commentCount }
    commentBody.value = ''
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'COMMENT_FAILED'
  }
  finally { busy.value = false }
}

async function doFollow() {
  if (!post.value || busy.value) return
  busy.value = true
  try {
    const r = await api.follow(post.value.authorId)
    followState.value = r.following
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'FOLLOW_FAILED'
  }
  finally { busy.value = false }
}

async function doUnfollow() {
  if (!post.value || busy.value) return
  busy.value = true
  try {
    const r = await api.unfollow(post.value.authorId)
    followState.value = r.following
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'UNFOLLOW_FAILED'
  }
  finally { busy.value = false }
}

function formatTime(ms: number) {
  return new Date(ms).toLocaleString()
}

onMounted(() => load())
watch(postId, () => load())
</script>

<template>
  <MainContent :back="`/hot-topics/${encodeURIComponent(topic)}`">
    <template #title>
      <MainTitle>帖子 {{ postId }}</MainTitle>
    </template>

    <StateBlock v-if="status === 'loading'" kind="loading" message="加载帖子…" />
    <StateBlock
      v-else-if="status === 'auth'"
      kind="auth"
      message="需要有效身份"
      @retry="load"
    />
    <StateBlock
      v-else-if="status === 'error'"
      kind="error"
      :message="`无法显示（${errorCode}）`"
      @retry="load"
    />

    <article v-else-if="post" class="px4 py4" data-testid="post-detail">
      <div class="flex flex-wrap items-center gap2 text-sm text-secondary">
        <span>作者 {{ post.authorId }}</span>
        <span>·</span>
        <time>{{ formatTime(post.publishedAtMs) }}</time>
        <span>·</span>
        <span>{{ post.status }}</span>
      </div>
      <p class="mt4 whitespace-pre-wrap break-words text-lg leading-relaxed">
        {{ post.body || '（无正文）' }}
      </p>

      <div v-if="media" class="mt4">
        <img :src="media.url" alt="媒体" class="max-w-full rounded-lg border border-base" @error="mediaError = 'LOAD_FAILED'">
        <p class="text-xs text-secondary mt1">签名链接至 {{ new Date(media.expiresAtMs).toLocaleTimeString() }} 过期</p>
      </div>
      <p v-else-if="mediaError" class="mt4 text-sm text-secondary">
        媒体不可用（{{ mediaError }}）
      </p>

      <div class="mt6 flex flex-wrap gap2 items-center border-t border-base pt4">
        <span class="text-sm text-secondary">
          赞 {{ stats?.likes ?? '—' }} · 评 {{ stats?.comments ?? '—' }}
        </span>
        <button type="button" class="btn-text" :disabled="busy" @click="doLike">点赞</button>
        <button type="button" class="btn-text" :disabled="busy" @click="doUnlike">取消赞</button>
        <button type="button" class="btn-text" :disabled="busy" @click="doFollow">关注作者</button>
        <button type="button" class="btn-text" :disabled="busy" @click="doUnfollow">取关</button>
        <span v-if="followState != null" class="text-xs text-secondary">following={{ followState }}</span>
      </div>

      <form class="mt4 flex flex-col gap2" @submit.prevent="sendComment">
        <label class="text-sm text-secondary">发表评论（无评论列表 API，仅更新计数）</label>
        <textarea v-model="commentBody" class="border border-base rounded p2 bg-base" rows="3" maxlength="4000" />
        <button type="submit" class="btn-solid self-start" :disabled="busy || !commentBody.trim()">发送</button>
      </form>

      <p v-if="errorCode && status === 'ok'" class="mt3 text-sm text-$c-danger" role="alert">{{ errorCode }}</p>
    </article>
  </MainContent>
</template>
