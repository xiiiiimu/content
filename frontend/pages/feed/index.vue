<script setup lang="ts">
import type { FeedItem, PageState } from '~/types/content'
import { ContentApiError } from '~/types/content'

useHead({ title: '关注 Feed · Content' })

const api = useContentApi()
const { hydrate, token, expired } = useIdentity()

const items = ref<FeedItem[]>([])
const nextCursor = ref<string | null>(null)
const pageState = ref<PageState | null>(null)
const status = ref<'idle' | 'loading' | 'ok' | 'empty' | 'error' | 'auth'>('idle')
const errorCode = ref('')
const loadingMore = ref(false)

async function loadFirst() {
  hydrate()
  if (!token.value || expired.value) {
    status.value = 'auth'
    items.value = []
    return
  }
  status.value = 'loading'
  try {
    const page = await api.followingFeed(20, null)
    items.value = page.items
    nextCursor.value = page.nextCursor
    pageState.value = page.pageState
    status.value = page.items.length ? 'ok' : (page.pageState === 'EXHAUSTED' ? 'empty' : 'ok')
  }
  catch (e) {
    const err = e instanceof ContentApiError ? e : new ContentApiError(500, 'UNKNOWN')
    errorCode.value = err.code
    status.value = err.isAuth ? 'auth' : 'error'
  }
}

/** Continue while HAS_MORE or UNKNOWN; stop only on EXHAUSTED or missing cursor. */
async function loadMore() {
  if (loadingMore.value) return
  if (!nextCursor.value) return
  if (pageState.value === 'EXHAUSTED') return
  loadingMore.value = true
  errorCode.value = ''
  try {
    const page = await api.followingFeed(20, nextCursor.value)
    const seen = new Set(items.value.map(i => i.postId))
    for (const it of page.items) {
      if (!seen.has(it.postId)) items.value.push(it)
    }
    nextCursor.value = page.nextCursor
    pageState.value = page.pageState
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'FEED_MORE_FAILED'
  }
  finally {
    loadingMore.value = false
  }
}

const canLoadMore = computed(() =>
  !!nextCursor.value && pageState.value !== 'EXHAUSTED',
)

onMounted(() => loadFirst())
</script>

<template>
  <MainContent>
    <template #title>
      <MainTitle icon="i-ri-home-5-line">关注 Feed</MainTitle>
    </template>

    <StateBlock v-if="status === 'loading'" kind="loading" message="加载 Feed…" />
    <StateBlock
      v-else-if="status === 'auth'"
      kind="auth"
      message="需要有效身份读取关注 Feed"
      @retry="loadFirst"
    />
    <StateBlock
      v-else-if="status === 'error'"
      kind="error"
      :message="`加载失败（${errorCode}）`"
      @retry="loadFirst"
    />
    <StateBlock v-else-if="status === 'empty'" kind="empty" message="暂无可见帖子（已耗尽）" />

    <template v-else>
      <p class="px4 py3 text-sm text-secondary border-b border-base">
        游标原样回传。pageState={{ pageState ?? '—' }}（UNKNOWN 仍可继续，非结束）。
      </p>
      <PostCard v-for="it in items" :key="it.postId" :post="it" />
      <div class="p4 flex flex-col items-start gap2">
        <button
          v-if="canLoadMore"
          type="button"
          class="btn-solid"
          :disabled="loadingMore"
          data-testid="feed-load-more"
          @click="loadMore"
        >
          {{ loadingMore ? '加载中…' : (pageState === 'UNKNOWN' ? '继续扫描' : '加载更多') }}
        </button>
        <p v-else-if="pageState === 'EXHAUSTED'" class="text-sm text-secondary">已耗尽</p>
        <p v-if="errorCode" class="text-sm text-$c-danger" role="alert">{{ errorCode }}</p>
      </div>
    </template>
  </MainContent>
</template>
