// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: true },
  css: ['~/assets/css/storefront.css'],

  modules: [
    '@nuxt/eslint',
    '@nuxt/image',
    '@nuxt/ui',
    'nuxt-umami',
    '@nuxtjs/eslint-module',
    '@nuxtjs/i18n',
    '@nuxtjs/seo',
    '@pinia/nuxt'
  ],
  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE || 'http://localhost:8080/api'
    }
  },
  devServer: {
    port: 8088,
    host: '0.0.0.0'
  }
})
