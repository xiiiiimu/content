<script setup lang="ts">
import type { ContentPost, MaterialDetail, MaterialSigned, PostStats, SignedMedia } from '~/types/content'
import { ContentApiError } from '~/types/content'

const route = useRoute()
const postId = computed(() => Number(route.params.id))
const api = useContentApi()
const { hydrate, token, expired } = useIdentity()

const post = ref<ContentPost | null>(null)
const stats = ref<PostStats | null>(null)
const media = ref<SignedMedia | null>(null)
const material = ref<MaterialDetail | null>(null)
const preview = ref<MaterialSigned | null>(null)
const selectedVersionId = ref<number | null>(null)
const offsetMs = ref(8000)
const commentBody = ref('')
const assistantBody = ref('@助手 ')
const feedbackCommentId = ref<number | null>(null)
const subscribeMsg = ref('')
const password = ref('')
const grant = ref('')
const downloadUrl = ref('')
const mediaError = ref('')
const comments = ref<Array<{ commentId: number; userId: number; parentId?: number; body: string; createdAtMs: number }>>([])
const notifications = ref<Array<{ id: number; kind: string; postId: number; commentId: number; refVersionId?: number }>>([])
const entitlement = ref<{ entitled: boolean; versionId?: number; license?: string } | null>(null)
const campaign = ref<{
  id: number
  priceCents: number
  stockCap: number
  soldCount: number
  startMs: number
  endMs: number
  state: string
  linkToken: string
  remainingStock?: number
} | null>(null)
const myOrder = ref<{ id: number; state: string; priceCents: number; refundReason?: string } | null>(null)
const purchaseState = ref('')
const salePrice = ref(990)
const saleStock = ref(10)
const blacklistTarget = ref<number | null>(null)
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
  material.value = null
  preview.value = null
  downloadUrl.value = ''
  try {
    post.value = await api.post(postId.value)
    try { stats.value = await api.stats(postId.value) }
    catch { stats.value = null }
    try { media.value = await api.signMedia(postId.value) }
    catch (e) {
      media.value = null
      mediaError.value = e instanceof ContentApiError ? e.code : 'MEDIA_UNAVAILABLE'
    }
    try {
      material.value = await api.material(postId.value)
      selectedVersionId.value = material.value.defaultVersionId
      if (selectedVersionId.value) {
        preview.value = await api.signMaterialPreview(postId.value, selectedVersionId.value)
      }
    }
    catch {
      material.value = null
    }
    try { comments.value = await api.comments(postId.value) }
    catch { comments.value = [] }
    try { notifications.value = await api.notifications(20) }
    catch { notifications.value = [] }
    try { entitlement.value = await api.entitlement(postId.value) }
    catch { entitlement.value = null }
    try { campaign.value = await api.saleCampaignCurrent(postId.value) }
    catch { campaign.value = null }
    try { myOrder.value = await api.myMaterialOrder(postId.value) }
    catch { myOrder.value = null }
    status.value = 'ok'
  }
  catch (e) {
    const err = e instanceof ContentApiError ? e : new ContentApiError(500, 'UNKNOWN')
    errorCode.value = err.code
    status.value = err.isAuth ? 'auth' : 'error'
  }
}

async function switchVersion(versionId: number) {
  selectedVersionId.value = versionId
  preview.value = await api.signMaterialPreview(postId.value, versionId)
}

async function doCommentAtOffset() {
  if (!selectedVersionId.value || !commentBody.value || busy.value) return
  busy.value = true
  try {
    await api.comment(postId.value, commentBody.value, `ui-${Date.now()}`, undefined, {
      versionId: selectedVersionId.value,
      offsetMs: offsetMs.value,
    })
    commentBody.value = ''
    stats.value = await api.stats(postId.value)
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'COMMENT_FAILED'
  }
  finally { busy.value = false }
}

async function doUnlockDownload() {
  busy.value = true
  try {
    const u = await api.unlockMaterial(postId.value, password.value)
    grant.value = u.grant
    const signed = await api.signMaterialOriginal(postId.value, u.grant, selectedVersionId.value ?? undefined)
    downloadUrl.value = signed.url
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'UNLOCK_FAILED'
  }
  finally { busy.value = false }
}

async function doLike() {
  if (!post.value || busy.value) return
  busy.value = true
  try {
    const r = await api.like(post.value.postId)
    stats.value = {
      postId: post.value.postId,
      likes: r.likeCount,
      comments: stats.value?.comments ?? 0,
      dislikes: stats.value?.dislikes,
      reports: stats.value?.reports,
    }
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
    stats.value = {
      postId: post.value.postId,
      likes: r.likeCount,
      comments: stats.value?.comments ?? 0,
      dislikes: stats.value?.dislikes,
      reports: stats.value?.reports,
    }
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'UNLIKE_FAILED'
  }
  finally { busy.value = false }
}

async function doDislike() {
  if (!post.value || busy.value) return
  busy.value = true
  try {
    const r = await api.dislike(post.value.postId)
    stats.value = {
      postId: post.value.postId,
      likes: stats.value?.likes ?? 0,
      comments: stats.value?.comments ?? 0,
      dislikes: r.dislikeCount,
      reports: stats.value?.reports,
    }
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'DISLIKE_FAILED'
  }
  finally { busy.value = false }
}

async function doAssistantComment() {
  if (!assistantBody.value || busy.value) return
  busy.value = true
  try {
    await api.comment(postId.value, assistantBody.value, `assistant-${Date.now()}`, undefined, {
      parentId: feedbackCommentId.value ?? undefined,
    })
    assistantBody.value = '@助手 '
    stats.value = await api.stats(postId.value)
    comments.value = await api.comments(postId.value)
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'ASSISTANT_COMMENT_FAILED'
  }
  finally { busy.value = false }
}

async function doSubscribe() {
  if (!feedbackCommentId.value || busy.value) return
  busy.value = true
  try {
    const r = await api.subscribeFeedback(postId.value, feedbackCommentId.value)
    subscribeMsg.value = r.message
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'SUBSCRIBE_FAILED'
  }
  finally { busy.value = false }
}

async function doCancelSubscribe() {
  if (!feedbackCommentId.value || busy.value) return
  busy.value = true
  try {
    await api.cancelFeedbackSubscribe(postId.value, feedbackCommentId.value)
    subscribeMsg.value = '已取消订阅'
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'UNSUBSCRIBE_FAILED'
  }
  finally { busy.value = false }
}

async function doFavorite() {
  if (busy.value) return
  busy.value = true
  try { await api.favorite(postId.value) }
  catch (e) { errorCode.value = e instanceof ContentApiError ? e.code : 'FAVORITE_FAILED' }
  finally { busy.value = false }
}

async function doCreateCampaign() {
  if (busy.value) return
  busy.value = true
  errorCode.value = ''
  try {
    const now = Date.now()
    campaign.value = await api.createSaleCampaign(postId.value, {
      priceCents: salePrice.value,
      stockCap: saleStock.value,
      startMs: now - 1000,
      endMs: now + 24 * 3600_000,
    }) as typeof campaign.value
  }
  catch (e) { errorCode.value = e instanceof ContentApiError ? e.code : 'CAMPAIGN_FAILED' }
  finally { busy.value = false }
}

async function doPurchase() {
  if (!campaign.value || busy.value) return
  busy.value = true
  purchaseState.value = 'PROCESSING'
  errorCode.value = ''
  try {
    const key = `web-${postId.value}-${Date.now()}`
    let result = await api.purchaseSale(campaign.value.id, key)
    const deadline = Date.now() + 30_000
    while (result.state === 'PROCESSING' && Date.now() < deadline) {
      await new Promise(r => setTimeout(r, 400))
      result = await api.purchaseRequest(result.reservationId)
    }
    purchaseState.value = result.state + (result.rejectReason ? `:${result.rejectReason}` : '')
    myOrder.value = await api.myMaterialOrder(postId.value)
    campaign.value = await api.saleCampaignCurrent(postId.value)
    entitlement.value = await api.entitlement(postId.value)
  }
  catch (e) {
    errorCode.value = e instanceof ContentApiError ? e.code : 'PURCHASE_FAILED'
    purchaseState.value = 'FAILED'
  }
  finally { busy.value = false }
}

async function doForceRefund() {
  if (!myOrder.value || busy.value) return
  busy.value = true
  try {
    myOrder.value = await api.forceOrderRefund(myOrder.value.id) as typeof myOrder.value
    entitlement.value = await api.entitlement(postId.value)
  }
  catch (e) { errorCode.value = e instanceof ContentApiError ? e.code : 'REFUND_FAILED' }
  finally { busy.value = false }
}

async function doBlacklist() {
  if (!blacklistTarget.value || busy.value) return
  busy.value = true
  try { await api.blacklistUser(blacklistTarget.value, 'merchant block') }
  catch (e) { errorCode.value = e instanceof ContentApiError ? e.code : 'BLACKLIST_FAILED' }
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
      <p v-else-if="mediaError && !material" class="mt4 text-sm text-secondary">媒体不可用（{{ mediaError }}）</p>

      <section v-if="material" class="mt6 border-t border-base pt4" data-testid="material-panel">
        <h2 class="text-base font-medium">{{ material.title }}</h2>
        <p class="text-sm text-secondary mt1">{{ material.description }}</p>
        <p class="text-xs text-secondary mt1">#{{ material.tags.join(' #') }} · {{ material.shareMode }}</p>
        <div class="flex flex-wrap gap2 mt3">
          <button
            v-for="v in material.versions"
            :key="v.versionId"
            type="button"
            class="btn-ghost text-sm"
            :class="{ 'text-primary': selectedVersionId === v.versionId }"
            @click="switchVersion(v.versionId)"
          >
            v{{ v.revision }} {{ v.isDefault ? '(默认)' : '' }} · {{ v.state }}
          </button>
        </div>
        <p v-if="preview" class="text-xs text-secondary mt2 break-all">
          预览授权：{{ preview.url }}
        </p>
        <div class="mt4 flex flex-wrap gap2 items-end">
          <label class="text-sm">
            时间点 ms
            <input v-model.number="offsetMs" type="number" min="0" class="input w-28 ms2">
          </label>
          <input v-model="commentBody" class="input flex-1 min-w-40" placeholder="在该时间点反馈">
          <button type="button" class="btn-primary" :disabled="busy" @click="doCommentAtOffset">评论</button>
        </div>
        <div class="mt4 flex flex-wrap gap2 items-end">
          <input v-model="password" type="password" class="input w-40" placeholder="原片密码">
          <button type="button" class="btn-ghost" :disabled="busy" @click="doUnlockDownload">解锁下载</button>
          <a v-if="downloadUrl" :href="downloadUrl" class="text-sm text-primary" target="_blank" rel="noopener">打开原片授权链接</a>
        </div>

        <div class="mt6 border-t border-base pt4" data-testid="sale-panel">
          <h3 class="text-sm font-medium">限量抢购</h3>
          <div v-if="campaign" class="mt2 text-sm text-secondary">
            ¥{{ (campaign.priceCents / 100).toFixed(2) }} · 库存 {{ campaign.remainingStock ?? (campaign.stockCap - campaign.soldCount) }}/{{ campaign.stockCap }}
            · {{ campaign.state }} · 链接 token {{ campaign.linkToken.slice(0, 8) }}…
            <button type="button" class="btn-primary ms2" :disabled="busy" @click="doPurchase">抢购</button>
            <span v-if="purchaseState" class="ms2">{{ purchaseState }}</span>
          </div>
          <div v-else class="mt2 flex flex-wrap gap2 items-end">
            <label class="text-sm">价格分<input v-model.number="salePrice" type="number" class="input w-24 ms1"></label>
            <label class="text-sm">库存<input v-model.number="saleStock" type="number" class="input w-20 ms1"></label>
            <button type="button" class="btn-ghost" :disabled="busy" @click="doCreateCampaign">创建活动</button>
          </div>
          <p v-if="myOrder" class="text-sm mt2">
            我的订单 #{{ myOrder.id }} · {{ myOrder.state }}
            <span v-if="myOrder.refundReason"> · {{ myOrder.refundReason }}</span>
            <button v-if="myOrder.state === 'PAID'" type="button" class="btn-text ms2" :disabled="busy" @click="doForceRefund">强制退款</button>
          </p>
          <div class="mt2 flex flex-wrap gap2 items-end">
            <input v-model.number="blacklistTarget" type="number" class="input w-28" placeholder="拉黑用户ID">
            <button type="button" class="btn-ghost" :disabled="busy || !blacklistTarget" @click="doBlacklist">拉黑</button>
          </div>
        </div>
      </section>

      <section class="mt6 border-t border-base pt4" data-testid="assistant-panel">
        <h2 class="text-base font-medium">@助手</h2>
        <p class="text-sm text-secondary mt1">评论会立即发布；助手异步回复找素材 / 蹲改进 / 抽奖。</p>
        <div class="mt3 flex flex-wrap gap2 items-end">
          <input v-model="assistantBody" class="input flex-1 min-w-48" placeholder="@助手 找竖屏允许商用的雨夜">
          <label class="text-sm">
            反馈评论 ID
            <input v-model.number="feedbackCommentId" type="number" class="input w-28 ms2" placeholder="可选">
          </label>
          <button type="button" class="btn-primary" :disabled="busy" @click="doAssistantComment">发送</button>
          <button type="button" class="btn-ghost" :disabled="busy || !feedbackCommentId" @click="doSubscribe">订阅改进</button>
          <button type="button" class="btn-ghost" :disabled="busy || !feedbackCommentId" @click="doCancelSubscribe">取消订阅</button>
        </div>
        <p v-if="subscribeMsg" class="text-sm text-secondary mt2">{{ subscribeMsg }}</p>
      </section>

      <section class="mt6 border-t border-base pt4" data-testid="comment-thread">
        <div class="flex items-center justify-between gap2">
          <h2 class="text-base font-medium">评论</h2>
          <button type="button" class="btn-ghost text-sm" :disabled="busy" @click="load">刷新</button>
        </div>
        <ul v-if="comments.length" class="mt3 space-y-3">
          <li v-for="c in comments" :key="c.commentId" class="text-sm">
            <div class="text-xs text-secondary">#{{ c.commentId }} · 用户 {{ c.userId }}<span v-if="c.parentId"> · 回复 #{{ c.parentId }}</span></div>
            <p class="mt1 whitespace-pre-wrap break-words">{{ c.body }}</p>
            <button
              type="button"
              class="btn-text text-xs mt1"
              @click="feedbackCommentId = c.commentId"
            >
              用作反馈 ID
            </button>
          </li>
        </ul>
        <p v-else class="mt2 text-sm text-secondary">暂无评论</p>
      </section>

      <section class="mt6 border-t border-base pt4" data-testid="notification-panel">
        <h2 class="text-base font-medium">我的通知</h2>
        <p v-if="entitlement?.entitled" class="text-sm text-secondary mt1">
          本帖奖励资格：版本 {{ entitlement.versionId }} · {{ entitlement.license }}
        </p>
        <ul v-if="notifications.length" class="mt3 space-y-2">
          <li v-for="n in notifications" :key="n.id" class="text-sm">
            {{ n.kind }} · 帖 {{ n.postId }} · 评论 {{ n.commentId }}
            <span v-if="n.refVersionId"> · 版本 {{ n.refVersionId }}</span>
          </li>
        </ul>
        <p v-else class="mt2 text-sm text-secondary">暂无通知</p>
      </section>

      <div class="mt6 flex gap2 items-center border-t border-base pt4">
        <span class="text-sm text-secondary">
          赞 {{ stats?.likes ?? '—' }} · 踩 {{ stats?.dislikes ?? '—' }} · 评 {{ stats?.comments ?? '—' }}
        </span>
        <button type="button" class="btn-text" :disabled="busy" @click="doLike">点赞</button>
        <button type="button" class="btn-text" :disabled="busy" @click="doUnlike">取消赞</button>
        <button type="button" class="btn-text" :disabled="busy" @click="doDislike">反对</button>
        <button type="button" class="btn-text" :disabled="busy" @click="doFavorite">收藏</button>
      </div>
      <p v-if="errorCode" class="text-sm text-red mt2">{{ errorCode }}</p>
    </article>
  </MainContent>
</template>
