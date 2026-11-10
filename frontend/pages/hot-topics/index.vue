<script setup lang="ts">
import type { TrendingTopic } from '~/types/content'
import { ContentApiError } from '~/types/content'

useHead({ title: '热点 · Content' })

const api = useContentApi()
const { hydrate, token, expired } = useIdentity()
onMounted(() => hydrate())

const windowOpt = ref<'1h' | '24h'>('1h')
const metric = ref<'net_events' | 'active_interactions'>('net_events')
const topics = ref<TrendingTopic[]>([])
const status = ref<'idle' | 'loading' | 'ok' | 'empty' | 'error' | 'auth'>('idle')
const errorCode = ref('')

async function load() {
  hydrate()
  if (!token.value || expired.value) {
    status.value = 'auth'
    errorCode.value = expired.value ? 'EXPIRED' : 'UNAUTHENTICATED'
    topics.value = []
    return
  }
  status.value = 'loading'
  errorCode.value = ''
  try {
    const list = await api.trending(windowOpt.value, metric.value)
    topics.value = list
    status.value = list.length ? 'ok' : 'empty'
  }
  catch (e) {
    const err = e instanceof ContentApiError ? e : new ContentApiError(500, 'UNKNOWN')
    errorCode.value = err.code
    status.value = err.isAuth ? 'auth' : 'error'
    topics.value = []
  }
}

watch([windowOpt, metric], () => load())
onMounted(() => load())
</script>

<template>
  <MainContent>
    <template #title>
      <MainTitle icon="i-ri-fire-line">热点</MainTitle>
    </template>

    <div class="flex flex-wrap gap2 px4 py3 border-b border-base text-sm">
      <label class="flex items-center gap1">
        窗口
        <select v-model="windowOpt" class="border border-base rounded px2 py1 bg-base">
          <option value="1h">1h</option>
          <option value="24h">24h</option>
        </select>
      </label>
      <label class="flex items-center gap1">
        指标
        <select v-model="metric" class="border border-base rounded px2 py1 bg-base">
          <option value="net_events">net_events</option>
          <option value="active_interactions">active_interactions</option>
        </select>
      </label>
    </div>

    <StateBlock v-if="status === 'loading'" kind="loading" message="加载热点…" />
    <StateBlock
      v-else-if="status === 'auth'"
      kind="auth"
      :message="errorCode === 'EXPIRED' ? '身份已过期，请在左侧重新粘贴 token' : '需要 Bearer 身份才能读取热点'"
      @retry="load"
    />
    <StateBlock
      v-else-if="status === 'error'"
      kind="error"
      :message="`加载失败（${errorCode}）`"
      @retry="load"
    />
    <StateBlock v-else-if="status === 'empty'" kind="empty" message="当前窗口暂无热点话题" />

    <template v-else>
      <p class="px4 py3 text-sm text-secondary border-b border-base">
        数据来自 Content <code>/api/topics/trending</code>；列表不含参与者名单（后端未提供）。
      </p>
      <NuxtLink
        v-for="t in topics"
        :key="t.topic"
        :to="`/hot-topics/${encodeURIComponent(t.topic)}`"
        class="card-row"
        data-testid="hot-topic"
      >
        <h2 class="text-base font-medium break-words">
          <bdi class="hover:underline">#{{ t.topic }}</bdi>
        </h2>
        <div class="mt1 text-sm text-secondary" data-testid="topic-score">
          score {{ t.score }}
        </div>
      </NuxtLink>
    </template>
  </MainContent>
</template>
