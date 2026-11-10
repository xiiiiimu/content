<script setup lang="ts">
import type { ContentPost, PostStats, SignedMedia } from '~/types/content'
import { ContentApiError } from '~/types/content'

const route = useRoute()
const postId = computed(() => Number(route.params.id))
const api = useContentApi()
const { hydrate, token, expired } = useIdentity()

const post = ref<ContentPost | null>(null)
const stats = ref<PostStats | null>(null)
const media = ref<SignedMedia | null>(null)
const mediaError = ref('')
const status = ref<'loading' | 'ok' | 'error' | 'auth'>('loading')
const errorCode = ref('')
const busy = ref(false)

useHead({ title: () => `帖子 ${postId.value} · Content` })

async function load() {
  hydrate()
  if (!token.value || expired.value) {
    status.value = 'auth'
    return
  }
  status.value = 'loading'
  try {
    post.value = await api.post(postId.value)
    try { stats.value = await api.stats(postId.value) }
    catch { stats.value = null }
    try { media.value = await api.signMedia(postId.value) }
    catch (e) {
      media.value = null
      mediaError.value = e instanceof ContentApiError ? e.code : 'MEDIA_UNAVAILABLE'
    }
    status.value = 'ok'
  }
  catch (e) {
    const err = e instanceof ContentApiError ? e : new ContentApiError(500, 'UNKNOWN')
    errorCode.value = err.code
    status.value = err.isAuth ? 'auth' : 'error'
  }
}

async function doLike() {
  if (!post.value || busy.value) return
  busy.value = true
  try {
    const r = await api.like(post.value.postId)
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
    const r = await api.unlike(post.value.postId)
    stats.value = { postId: post.value.postId, likes: r.likeCount, comments: stats.value?.comments ?? 0 }
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'UNLIKE_FAILED'
  }
  finally { busy.value = false }
}

onMounted(() => load())
watch(postId, () => load())
</script>

<template>
  <MainContent back="/feed">
    <template #title>
      <MainTitle>帖子 {{ postId }}</MainTitle>
    </template>
    <StateBlock v-if="status === 'loading'" kind="loading" message="加载帖子…" />
    <StateBlock v-else-if="status === 'auth'" kind="auth" message="需要有效身份" @retry="load" />
    <StateBlock v-else-if="status === 'error'" kind="error" :message="`无法显示（${errorCode}）`" @retry="load" />
    <article v-else-if="post" class="px4 py4" data-testid="post-detail">
      <div class="text-sm text-secondary">作者 {{ post.authorId }} · {{ new Date(post.publishedAtMs).toLocaleString() }} · {{ post.status }}</div>
      <p class="mt4 whitespace-pre-wrap break-words text-lg leading-relaxed">{{ post.body || '（无正文）' }}</p>
      <div v-if="media" class="mt4">
        <img :src="media.url" alt="媒体" class="max-w-full rounded-lg border border-base">
      </div>
      <p v-else-if="mediaError" class="mt4 text-sm text-secondary">媒体不可用（{{ mediaError }}）</p>
      <div class="mt6 flex gap2 items-center border-t border-base pt4">
        <span class="text-sm text-secondary">赞 {{ stats?.likes ?? '—' }} · 评 {{ stats?.comments ?? '—' }}</span>
        <button type="button" class="btn-text" :disabled="busy" @click="doLike">点赞</button>
        <button type="button" class="btn-text" :disabled="busy" @click="doUnlike">取消赞</button>
      </div>
    </article>
  </MainContent>
</template>
