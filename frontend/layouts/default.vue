<script setup lang="ts">
const { hydrate, token, readerId, role, expired, setToken, isOps } = useIdentity()
onMounted(() => hydrate())

const draftToken = ref('')
watch(token, (t) => { draftToken.value = t || '' }, { immediate: true })

function saveToken() {
  setToken(draftToken.value || null)
}

function clearToken() {
  draftToken.value = ''
  setToken(null)
}
</script>

<template>
  <div class="min-h-full bg-base">
    <main class="flex w-full mx-auto lg:max-w-80rem">
      <!-- Left nav — Elk-inspired sticky rail -->
      <aside class="hidden sm:flex w-20 xl:w-64 shrink-0 justify-end xl:me-4">
        <div class="sticky top-0 h-100dvh flex flex-col py3 px2 xl:px4 w-full max-w-xs">
          <NuxtLink to="/" class="text-xl font-bold text-primary px2 py3 tracking-tight">
            Content
          </NuxtLink>
          <nav class="flex flex-col gap1 mt2" aria-label="Primary">
            <NuxtLink to="/hot-topics" class="nav-link" active-class="bg-active text-primary">
              <span class="i-ri-fire-line text-lg" />
              <span class="hidden xl:inline">热点</span>
            </NuxtLink>
            <NuxtLink to="/feed" class="nav-link" active-class="bg-active text-primary">
              <span class="i-ri-home-5-line text-lg" />
              <span class="hidden xl:inline">关注 Feed</span>
            </NuxtLink>
            <NuxtLink to="/materials" class="nav-link" active-class="bg-active text-primary">
              <span class="i-ri-video-line text-lg" />
              <span class="hidden xl:inline">素材发现</span>
            </NuxtLink>
            <NuxtLink
              v-if="isOps"
              to="/ops/risks"
              class="nav-link"
              active-class="bg-active text-primary"
            >
              <span class="i-ri-alarm-warning-line text-lg" />
              <span class="hidden xl:inline">TrendGuard</span>
            </NuxtLink>
          </nav>
          <div class="flex-auto" />
          <div class="border-t border-base pt3 pb4 text-sm">
            <p v-if="token && !expired" class="text-secondary px2 mb2">
              reader {{ readerId }} · {{ role }}
            </p>
            <p v-else-if="expired" class="text-$c-danger px2 mb2" role="alert">
              身份已过期，请重新 mint 并粘贴 Bearer
            </p>
            <p v-else class="text-secondary px2 mb2">
              未注入身份（开发用 mint-identity）
            </p>
            <details class="px2">
              <summary class="cursor-pointer text-primary">开发身份</summary>
              <textarea
                v-model="draftToken"
                class="mt2 w-full text-xs p2 rounded border border-base bg-base"
                rows="3"
                placeholder="粘贴 mint-identity 输出的 Bearer（不含 Bearer 前缀）"
                spellcheck="false"
              />
              <div class="flex gap2 mt2">
                <button type="button" class="btn-solid text-xs" @click="saveToken">保存</button>
                <button type="button" class="btn-text text-xs" @click="clearToken">清除</button>
              </div>
            </details>
          </div>
        </div>
      </aside>

      <!-- Center column -->
      <div class="w-full min-h-screen sm:w-600px md:shrink-0 border-x border-base">
        <div class="min-h-[calc(100vh-3.5rem)] sm:min-h-screen">
          <slot />
        </div>
        <nav
          class="sm:hidden sticky bottom-0 z-10 flex border-t border-base bg-base pb-[env(safe-area-inset-bottom)]"
          aria-label="Mobile"
        >
          <NuxtLink to="/hot-topics" class="flex-1 py3 text-center" active-class="text-primary">热点</NuxtLink>
          <NuxtLink to="/feed" class="flex-1 py3 text-center" active-class="text-primary">Feed</NuxtLink>
          <NuxtLink v-if="isOps" to="/ops/risks" class="flex-1 py3 text-center" active-class="text-primary">运营</NuxtLink>
        </nav>
      </div>

      <!-- Right rail -->
      <aside class="hidden xl:block w-72 ps-4 py4 text-sm text-secondary">
        <div class="sticky top-4 flex flex-col gap3">
          <p class="font-medium text-$c-text">Content 前端</p>
          <p>展示已实现后端能力。布局参考 Elk，无 Mastodon 业务。</p>
          <NuxtLink to="/docs/gaps" class="text-primary hover:underline">能力缺口</NuxtLink>
        </div>
      </aside>
    </main>
  </div>
</template>

<style scoped>
.nav-link {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.625rem 0.75rem;
  border-radius: 0.75rem;
  transition: background 0.15s;
}
.nav-link:hover {
  background: var(--c-bg-active);
}
</style>
