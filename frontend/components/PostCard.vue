<script setup lang="ts">
import type { ContentPost, FeedItem } from '~/types/content'

const props = defineProps<{
  post: ContentPost | FeedItem
  topic?: string
}>()

const postId = computed(() => props.post.postId)
const body = computed(() => props.post.body)
const publishedAtMs = computed(() => props.post.publishedAtMs)
const authorId = computed(() => ('authorId' in props.post ? props.post.authorId : null))

const detailTo = computed(() => {
  if (props.topic) return `/hot-topics/${encodeURIComponent(props.topic)}/${postId.value}`
  return `/posts/${postId.value}`
})

function formatTime(ms: number) {
  try {
    return new Date(ms).toLocaleString()
  }
  catch {
    return String(ms)
  }
}
</script>

<template>
  <NuxtLink :to="detailTo" class="card-row" data-testid="post-card">
    <div class="flex items-baseline justify-between gap2 text-sm text-secondary">
      <span v-if="authorId != null">作者 {{ authorId }}</span>
      <span v-else>帖子 {{ postId }}</span>
      <time>{{ formatTime(publishedAtMs) }}</time>
    </div>
    <p class="mt2 whitespace-pre-wrap break-words leading-relaxed">
      {{ body || '（无正文）' }}
    </p>
  </NuxtLink>
</template>
