<script setup lang="ts">
import type { ContentPost } from '~/types/content'
import { ContentApiError } from '~/types/content'

const route = useRoute()
const topic = computed(() => decodeURIComponent(String(route.params.topic || '')))
useHead({ title: () => `#${topic.value} · Content` })

const api = useContentApi()
const { hydrate, token, expired } = useIdentity()

const posts = ref<ContentPost[]>([])
const status = ref<'idle' | 'loading' | 'ok' | 'empty' | 'error' | 'auth'>('idle')
const errorCode = ref('')

async function load() {
  hydrate()
  if (!token.value || expired.value) {
    status.value = 'auth'
    posts.value = []
    return
  }
  status.value = 'loading'
  try {
    posts.value = await api.topicPosts(topic.value, 50)
    status.value = posts.value.length ? 'ok' : 'empty'
  }
  catch (e) {
    const err = e instanceof ContentApiError ? e : new ContentApiError(500, 'UNKNOWN')
    errorCode.value = err.code
    status.value = err.isAuth ? 'auth' : 'error'
    posts.value = []
  }
}

onMounted(() => load())
watch(topic, () => load())
</script>

<template>
  <MainContent back="/hot-topics">
    <template #title>
      <MainTitle icon="i-ri-hashtag">{{ topic }}</MainTitle>
    </template>

    <StateBlock v-if="status === 'loading'" kind="loading" message="加载话题帖…" />
    <StateBlock
      v-else-if="status === 'auth'"
      kind="auth"
      message="需要有效身份才能读取话题帖"
      @retry="load"
    />
    <StateBlock
      v-else-if="status === 'error'"
      kind="error"
      :message="`加载失败（${errorCode}）`"
      @retry="load"
    />
    <StateBlock v-else-if="status === 'empty'" kind="empty" message="该话题暂无对你可见的帖子" />

    <template v-else>
      <p class="px4 py3 text-sm text-secondary border-b border-base">
        最多 50 条（后端无话题游标分页）。作者显示为 authorId。
      </p>
      <PostCard v-for="p in posts" :key="p.postId" :post="p" :topic="topic" />
    </template>
  </MainContent>
</template>
