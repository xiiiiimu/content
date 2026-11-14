<script setup lang="ts">
import type { MaterialDiscoverItem } from '~/types/content'
import { ContentApiError } from '~/types/content'

const api = useContentApi()
const { hydrate, token, expired } = useIdentity()
const q = ref('')
const tag = ref('')
const items = ref<MaterialDiscoverItem[]>([])
const status = ref<'loading' | 'ok' | 'error' | 'auth'>('loading')
const errorCode = ref('')
const nextBeforeMs = ref<number | null>(null)
const nextBeforeId = ref<number | null>(null)

useHead({ title: '素材发现 · Content' })

async function load(reset = true) {
  hydrate()
  if (!token.value || expired.value) {
    status.value = 'auth'
    return
  }
  status.value = 'loading'
  try {
    const page = await api.discoverMaterials({
      q: q.value || undefined,
      tag: tag.value || undefined,
      beforeMs: reset ? undefined : nextBeforeMs.value ?? undefined,
      beforeId: reset ? undefined : nextBeforeId.value ?? undefined,
    })
    items.value = reset ? page.items : [...items.value, ...page.items]
    nextBeforeMs.value = page.nextBeforeMs
    nextBeforeId.value = page.nextBeforeId
    status.value = 'ok'
  }
  catch (e) {
    const err = e instanceof ContentApiError ? e : new ContentApiError(500, 'UNKNOWN')
    errorCode.value = err.code
    status.value = err.isAuth ? 'auth' : 'error'
  }
}

onMounted(() => load(true))
</script>

<template>
  <MainContent>
    <template #title>
      <MainTitle>素材发现</MainTitle>
    </template>
    <p class="text-sm text-secondary mb4">
      仅检索作者填写的标题、描述与 Tag（不搜画面/音频/转录）。
    </p>
    <form class="flex flex-wrap gap2 mb4" @submit.prevent="load(true)">
      <input v-model="q" class="input flex-1 min-w-40" placeholder="标题/描述关键词" aria-label="关键词">
      <input v-model="tag" class="input w-40" placeholder="Tag" aria-label="Tag">
      <button type="submit" class="btn-primary">搜索</button>
    </form>
    <StateBlock v-if="status === 'loading'" kind="loading" message="加载素材…" />
    <StateBlock v-else-if="status === 'auth'" kind="auth" message="需要登录身份令牌" />
    <StateBlock v-else-if="status === 'error'" kind="error" :message="errorCode" />
    <ul v-else class="flex flex-col gap3">
      <li v-for="item in items" :key="item.postId" class="border-b border-base pb3">
        <NuxtLink :to="`/posts/${item.postId}`" class="text-primary font-medium">
          {{ item.title }}
        </NuxtLink>
        <p class="text-sm text-secondary mt1">{{ item.description }}</p>
        <p class="text-xs text-secondary mt1">
          #{{ item.tags.join(' #') || '无标签' }} · {{ item.shareMode }}
          <span v-if="item.coverAvailable"> · 封面可访问</span>
        </p>
      </li>
      <li v-if="!items.length" class="text-secondary text-sm">无结果</li>
    </ul>
    <button
      v-if="nextBeforeMs != null && nextBeforeId != null"
      type="button"
      class="btn-ghost mt4"
      @click="load(false)"
    >
      加载更多
    </button>
  </MainContent>
</template>
