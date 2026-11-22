<script setup lang="ts">
import { ContentApiError } from '~/types/content'

const api = useContentApi()
const { hydrate, token, expired } = useIdentity()

const body = ref('')
const topic = ref('')
const busy = ref(false)
const errorCode = ref('')
const draft = ref<{ postId: number, version: number } | null>(null)
// Publish retries reuse one key, so the backend replays the publication instead of rejecting a stale version.
const publishKey = createCommentKey('publish')

const topicCheck = computed(() => checkTopic(topic.value))
const canSubmit = computed(() => !busy.value && !!body.value.trim() && topicCheck.value.ok)

onMounted(() => hydrate())

async function submit() {
  if (!canSubmit.value) return
  if (!token.value || expired.value) { errorCode.value = 'UNAUTHENTICATED'; return }
  busy.value = true
  errorCode.value = ''
  try {
    // A failed publish keeps the created draft, so a retry publishes it again instead of creating another draft.
    if (!draft.value) {
      const d = await api.createPost(createPayload(body.value, topic.value))
      draft.value = { postId: d.postId, version: d.version }
    }
    const { postId, version } = draft.value
    await publishKey.send(key => api.publishPost(postId, key, version))
    await navigateTo(`/posts/${postId}`)
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : (e instanceof Error ? e.message : 'PUBLISH_FAILED')
  }
  finally { busy.value = false }
}
</script>

<template>
  <MainContent back="/hot-topics">
    <template #title>
      <MainTitle>发帖</MainTitle>
    </template>

    <form class="px4 py4 flex flex-col gap3" data-testid="post-compose" @submit.prevent="submit">
      <label class="flex flex-col gap1">
        <span class="text-sm text-secondary">正文</span>
        <textarea v-model="body" class="border border-base rounded p2 bg-base" rows="6" maxlength="100000" :disabled="!!draft" />
      </label>
      <label class="flex flex-col gap1">
        <span class="text-sm text-secondary">话题（可选，最多 {{ MAX_TOPIC_LENGTH }} 字）</span>
        <input
          v-model="topic"
          class="input"
          data-testid="topic-input"
          :disabled="!!draft"
          placeholder="例如：雨夜素材"
        >
        <span v-if="!topicCheck.ok" class="text-xs text-$c-danger" role="alert">话题最多 {{ MAX_TOPIC_LENGTH }} 字，且不能包含控制字符</span>
      </label>
      <p v-if="draft" class="text-xs text-secondary">草稿 {{ draft.postId }} 已创建，重试只会再次发布它。</p>
      <button type="submit" class="btn-solid self-start" :disabled="!canSubmit">{{ draft ? '重试发布' : '发布' }}</button>
      <p v-if="errorCode" class="text-sm text-$c-danger" role="alert">{{ errorCode }}</p>
    </form>
  </MainContent>
</template>
