// Content frontend — Nuxt app. Visual language referenced from Elk (MIT); no Mastodon runtime.
export default defineNuxtConfig({
  compatibilityDate: '2024-11-01',
  modules: ['@unocss/nuxt', '@pinia/nuxt', '@vueuse/nuxt'],
  css: ['~/assets/css/main.css'],
  app: {
    head: {
      title: 'Content',
      meta: [{ name: 'viewport', content: 'width=device-width, initial-scale=1' }],
      htmlAttrs: { lang: 'zh-CN' },
    },
  },
  runtimeConfig: {
    public: {
      /** Empty in dev → same-origin `/api` via Nitro proxy. Production: absolute API origin. */
      contentApiBase: process.env.NUXT_PUBLIC_CONTENT_API_BASE || '',
    },
  },
  nitro: {
    devProxy: {
      '/api': {
        target: 'http://127.0.0.1:28080/api',
        changeOrigin: true,
      },
    },
  },
  vite: {
    server: {
      port: 5314,
      strictPort: true,
    },
  },
  typescript: { strict: true, typeCheck: false },
  devtools: { enabled: false },
})
