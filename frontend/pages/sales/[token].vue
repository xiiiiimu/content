<script setup lang="ts">
import { ContentApiError } from '~/types/content'
const api = useContentApi()
const route = useRoute()
const identity = useIdentity()
const campaign = ref<Awaited<ReturnType<typeof api.saleByToken>> | null>(null)
const order = ref<Awaited<ReturnType<typeof api.myMaterialOrder>>>(null)
const busy = ref(false)
const message = ref('')
const error = ref('')
const download = ref('')
const reservation = ref('')
useHead({ title: '素材购买 · Content' })
async function load() {
  identity.hydrate()
  if (!identity.token.value || identity.expired.value) { error.value = '请先登录，再打开购买链接'; return }
  try {
    campaign.value = await api.saleByToken(String(route.params.token))
    order.value = await api.myMaterialOrder(campaign.value.postId)
    reservation.value = sessionStorage.getItem(`sale-reservation:${identity.readerId.value}:${campaign.value.id}`) || ''
    error.value = ''
  }
  catch (e) { error.value = e instanceof ContentApiError ? e.code : '加载失败' }
}
async function purchase() {
  if (!campaign.value || busy.value) return
  busy.value = true; error.value = ''; message.value = '处理中，受理不等于付款成功'
  try {
    const storage = `sale-attempt:${identity.readerId.value}:${campaign.value.id}`
    const key = sessionStorage.getItem(storage) || `web-${crypto.randomUUID()}`
    sessionStorage.setItem(storage, key)
    let result = await api.purchaseSale(campaign.value.id, key)
    reservation.value = result.reservationId
    sessionStorage.setItem(`sale-reservation:${identity.readerId.value}:${campaign.value.id}`, result.reservationId)
    const deadline = Date.now() + 30_000
    while (result.state === 'PROCESSING' && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 400))
      result = await api.purchaseRequest(result.reservationId)
    }
    message.value = result.state === 'PROCESSING' ? '仍在处理，可刷新查询；请勿更换请求重试' : result.rejectReason || result.state
    if (result.state === 'REJECTED') sessionStorage.removeItem(storage)
    await load()
  }
  catch (e) { error.value = e instanceof ContentApiError ? e.code : '状态未知，可使用同一请求重试'; message.value = '未确认付款结果，请查询订单或使用原请求重试' }
  finally { busy.value = false }
}
async function original() {
  if (!campaign.value) return
  try { download.value = (await api.signMaterialOriginal(campaign.value.postId, '')).url }
  catch (e) { error.value = e instanceof ContentApiError ? e.code : '下载授权失败' }
}
async function refund() {
  if (!order.value) return
  busy.value = true
  try { const r = await api.requestGiveawayRefund(order.value.id); message.value = `中奖退款申请 #${r.refundRequestId}，等待商家审批；中奖权益保留` }
  catch (e) { error.value = e instanceof ContentApiError ? e.code : '申请失败' }
  finally { busy.value = false }
}
onMounted(load)
</script>

<template>
  <main class="p6 max-w-2xl mx-auto" data-testid="standalone-sale">
    <h1 class="text-xl font-semibold">素材购买</h1>
    <p class="mt2 text-secondary">登录即可购买，无须关注作者或取得预览权限。商家限制和限购规则仍适用。</p>
    <p v-if="error" role="alert" class="mt3">{{ error }}</p>
    <section v-if="campaign" class="mt4 space-y-3">
      <p>素材 #{{ campaign.postId }} · ¥{{ (campaign.priceCents / 100).toFixed(2) }}</p>
      <p>剩余 {{ campaign.remainingStock ?? campaign.stockCap - campaign.soldCount }} / {{ campaign.stockCap }} · {{ campaign.state }}</p>
      <button class="btn-primary" :disabled="busy || campaign.state !== 'OPEN'" @click="purchase">购买／原请求重试</button>
      <button class="btn-ghost" :disabled="busy" @click="load">刷新订单</button>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="reservation">请求 {{ reservation }}</p>
      <div v-if="order" class="space-y-2">
        <p>订单 #{{ order.id }} · {{ order.state }} <span v-if="order.refundReason">· {{ order.refundReason }}</span></p>
        <button v-if="order.state === 'PAID'" class="btn-primary" @click="original">凭购买权益下载</button>
        <button v-if="order.giveawayRefundEligible" class="btn-ghost" :disabled="busy" @click="refund">申请中奖退款</button>
        <p v-if="order.state === 'REFUNDED'">购买权益已撤销；独立中奖权益仍按获奖版本保留。</p>
        <a v-if="download" :href="download" rel="noopener" target="_blank" class="text-primary">打开已授权原片</a>
      </div>
    </section>
  </main>
</template>
