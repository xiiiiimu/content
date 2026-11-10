<script setup lang="ts">
import type { RiskEvent } from '~/types/content'
import { ContentApiError } from '~/types/content'

useHead({ title: 'TrendGuard · Content' })

const api = useContentApi()
const { hydrate, token, expired, isOps } = useIdentity()

const windowOpt = ref<'1h' | '24h'>('1h')
const topicFilter = ref('')
const events = ref<RiskEvent[]>([])
const status = ref<'idle' | 'loading' | 'ok' | 'empty' | 'error' | 'auth' | 'forbidden'>('idle')
const errorCode = ref('')
const loadingMore = ref(false)

async function loadFirst() {
  hydrate()
  if (!token.value || expired.value) {
    status.value = 'auth'
    return
  }
  status.value = 'loading'
  try {
    events.value = await api.risks({
      window: windowOpt.value,
      topic: topicFilter.value.trim() || undefined,
      limit: 50,
    })
    status.value = events.value.length ? 'ok' : 'empty'
  }
  catch (e) {
    const err = e instanceof ContentApiError ? e : new ContentApiError(500, 'UNKNOWN')
    errorCode.value = err.code
    if (err.isForbidden) status.value = 'forbidden'
    else if (err.isAuth) status.value = 'auth'
    else status.value = 'error'
  }
}

async function loadMore() {
  const last = events.value[events.value.length - 1]
  if (!last || loadingMore.value) return
  loadingMore.value = true
  try {
    const more = await api.risks({
      window: windowOpt.value,
      topic: topicFilter.value.trim() || undefined,
      limit: 50,
      beforeMs: last.occurredAtMs,
      beforeEventId: last.eventId,
    })
    const ids = new Set(events.value.map(e => e.eventId))
    for (const ev of more) {
      if (!ids.has(ev.eventId)) events.value.push(ev)
    }
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'RISKS_MORE_FAILED'
  }
  finally {
    loadingMore.value = false
  }
}

function formatTime(ms: number) {
  return new Date(ms).toLocaleString()
}

onMounted(() => loadFirst())
watch([windowOpt], () => loadFirst())
</script>

<template>
  <MainContent>
    <template #title>
      <MainTitle icon="i-ri-alarm-warning-line">TrendGuard 风险</MainTitle>
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
        topic
        <input v-model="topicFilter" class="border border-base rounded px2 py1 bg-base" placeholder="可选">
      </label>
      <button type="button" class="btn-text" @click="loadFirst">查询</button>
      <span v-if="!isOps" class="text-secondary">需要 TRENDGUARD_OPS 角色</span>
    </div>

    <StateBlock v-if="status === 'loading'" kind="loading" message="加载风险事件…" />
    <StateBlock
      v-else-if="status === 'auth'"
      kind="auth"
      message="需要有效 Bearer"
      @retry="loadFirst"
    />
    <StateBlock
      v-else-if="status === 'forbidden'"
      kind="error"
      message="无权限（403 FORBIDDEN）。请使用 TRENDGUARD_OPS 身份。"
      @retry="loadFirst"
    />
    <StateBlock
      v-else-if="status === 'error'"
      kind="error"
      :message="`失败（${errorCode}）`"
      @retry="loadFirst"
    />
    <StateBlock v-else-if="status === 'empty'" kind="empty" message="窗口内无风险事件" />

    <template v-else>
      <div
        v-for="ev in events"
        :key="ev.eventId"
        class="p4 border-b border-base"
        data-testid="risk-event"
      >
        <div class="font-medium">{{ ev.kind }} · #{{ ev.topic }}</div>
        <div class="text-sm text-secondary mt1">
          {{ formatTime(ev.occurredAtMs) }}
          <span v-if="ev.postId != null"> · post {{ ev.postId }}</span>
        </div>
        <p class="text-sm mt2 break-words">{{ ev.detail }}</p>
        <code class="text-xs text-secondary break-all">{{ ev.eventId }}</code>
      </div>
      <div class="p4">
        <button type="button" class="btn-solid" :disabled="loadingMore" @click="loadMore">
          {{ loadingMore ? '…' : '下一页（keyset）' }}
        </button>
      </div>
    </template>
  </MainContent>
</template>
