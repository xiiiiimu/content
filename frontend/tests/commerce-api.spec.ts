import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useContentApi } from '../composables/useContentApi'

describe('purchase and voluntary giveaway refund API wiring', () => {
  const fetcher = vi.fn()
  beforeEach(() => {
    vi.stubGlobal('useRuntimeConfig', () => ({ public: { contentApiBase: '' } }))
    vi.stubGlobal('useIdentity', () => ({ token: { value: 'authorized-test-identity' }, hydrate() {} }))
    vi.stubGlobal('fetch', fetcher)
    fetcher.mockResolvedValue(new Response('{}', { status: 200 }))
  })
  afterEach(() => { vi.unstubAllGlobals(); fetcher.mockReset() })
  it('opens the sale link without asking the preview or follow endpoints', async () => {
    await useContentApi().saleByToken('opaque-sale-link')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher.mock.calls[0]![0]).toBe('/api/sale-links/opaque-sale-link')
    expect(fetcher.mock.calls[0]![1].headers.get('Authorization')).toBe('Bearer authorized-test-identity')
  })
  it('requests the distinct giveaway refund and does not force or approve it', async () => {
    await useContentApi().requestGiveawayRefund(42)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher.mock.calls[0]![0]).toBe('/api/orders/42/giveaway-refund-requests')
    expect(fetcher.mock.calls[0]![1].method).toBe('POST')
  })
  it('keeps the caller clientKey unchanged for an uncertain retry', async () => {
    const api = useContentApi()
    await api.purchaseSale(7, 'same-attempt')
    fetcher.mockResolvedValueOnce(new Response('{}', { status: 200 }))
    await api.purchaseSale(7, 'same-attempt')
    expect(fetcher.mock.calls.map(c => JSON.parse(c[1].body).clientKey)).toEqual(['same-attempt', 'same-attempt'])
  })
  it('can ask for a purchased original without a share password grant', async () => {
    await useContentApi().signMaterialOriginal(7, '')
    expect(fetcher.mock.calls[0]![0]).toBe('/api/materials/7/original/sign')
    expect(JSON.parse(fetcher.mock.calls[0]![1].body).grant).toBe('')
  })
})
