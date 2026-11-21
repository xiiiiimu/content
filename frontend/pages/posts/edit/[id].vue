<script setup lang="ts">
import type { PostDraft } from '~/types/content'
import { ContentApiError } from '~/types/content'

const route = useRoute()
const postId = computed(() => Number(route.params.id))
const api = useContentApi()
const { hydrate, token, expired, readerId } = useIdentity()

const status = ref<'loading' | 'ok' | 'error' | 'auth'>('loading')
const errorCode = ref('')
const body = ref('')
const version = ref(0)
const authorId = ref<number | null>(null)
// GET /api/posts/{id} does not return the topic, so blank means "keep the stored topic" (PATCH without "topic").
const topic = ref('')
const clearTopic = ref(false)
const busy = ref(false)
const saved = ref<PostDraft | null>(null)

const topicCheck = computed(() => clearTopic.value ? { ok: true as const, value: null } : checkTopic(topic.value))
const isAuthor = computed(() => authorId.value != null && authorId.value === readerId.value)
const canSave = computed(() => !busy.value && !!body.value.trim() && topicCheck.value.ok && isAuthor.value)

async function load() {
  hydrate()
  if (!token.value || expired.value) { status.value = 'auth'; return }
  status.value = 'loading'
  try {
    const p = await api.post(postId.value)
    body.value = p.body
    version.value = p.version
    authorId.value = p.authorId
    status.value = 'ok'
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'LOAD_FAILED'
    status.value = e instanceof ContentApiError && e.status === 401 ? 'auth' : 'error'
  }
}

async function save() {
  if (!canSave.value) return
  busy.value = true
  errorCode.value = ''
  try {
    const d = await api.editPost(postId.value, editPayload(body.value, version.value, topic.value, clearTopic.value))
    saved.value = d
    version.value = d.version
    topic.value = ''
    clearTopic.value = false
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : (e instanceof Error ? e.message : 'EDIT_FAILED')
  }
  finally { busy.value = false }
}

onMounted(() => load())
watch(postId, () => load())
</script>

<template>
  <MainContent :back="`/posts/${postId}`">
    <template #title>
      <MainTitle>编辑帖子 {{ postId }}</MainTitle>
    </template>

    <StateBlock v-if="status === 'loading'" kind="loading" message="加载帖子…" />
    <StateBlock v-else-if="status === 'auth'" kind="auth" message="需要有效身份" @retry="load" />
    <StateBlock v-else-if="status === 'error'" kind="error" :message="`无法显示（${errorCode}）`" @retry="load" />

    <form v-else class="px4 py4 flex flex-col gap3" data-testid="post-edit" @submit.prevent="save">
      <p v-if="!isAuthor" class="text-sm text-secondary">只有作者可以编辑。</p>
      <label class="flex flex-col gap1">
        <span class="text-sm text-secondary">正文</span>
        <textarea v-model="body" class="border border-base rounded p2 bg-base" rows="6" maxlength="100000" />
      </label>
      <label class="flex flex-col gap1">
        <span class="text-sm text-secondary">话题（可选，最多 {{ MAX_TOPIC_LENGTH }} 字；留空保持原话题）</span>
        <input
          v-model="topic"
          class="input"
          data-testid="topic-input"
          :disabled="clearTopic"
          placeholder="留空保持原话题"
        >
        <span v-if="!topicCheck.ok" class="text-xs text-$c-danger" role="alert">话题最多 {{ MAX_TOPIC_LENGTH }} 字，且不能包含控制字符</span>
      </label>
      <label class="flex items-center gap2 text-sm">
        <input v-model="clearTopic" type="checkbox" data-testid="topic-clear">
        清除话题
      </label>
      <button type="submit" class="btn-solid self-start" :disabled="!canSave">保存</button>
      <p v-if="saved" class="text-sm text-secondary" data-testid="edit-saved">已保存 · 版本 {{ saved.version }} · 话题：{{ saved.topic ?? '（无）' }}</p>
      <p v-if="errorCode" class="text-sm text-$c-danger" role="alert">{{ errorCode }}</p>
    </form>
  </MainContent>
</template>
