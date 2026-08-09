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
    '@pinia/nuxt',
    'dayjs-nuxt',
  ],
  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE || 'http://localhost:8080/api'
    }
  },
  devServer: {
    port: 8088,
    host: '0.0.0.0'
  },
  dayjs: {
    locales: ["zh-cn"],
    defaultLocale: "zh-cn",
    plugins: ["timezone", "localizedFormat"],
    defaultTimezone: "Asia/Shanghai",
  },
  umami: {
    enabled: true,
    id: "48c0a518-a6fc-4d5f-94e5-9a755117729e",
    host: "https://umami.anycast.work",
    autoTrack: true,
    ignoreLocalhost: true,
    useDirective: true,
  },
})