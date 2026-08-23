// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  ssr: true,
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
    locales: ['en', 'zh-cn', 'ru'],
    defaultLocale: 'en',
    plugins: ["timezone", "localizedFormat"],
    defaultTimezone: "Asia/Shanghai",
  },
  i18n: {
    strategy: 'no_prefix',
    defaultLocale: 'en-US',
    langDir: 'locales',
    locales: [
      {
        code: 'en-US',
        language: 'en-US',
        name: 'English',
        files: [
          'en-US/common.ts',
          'en-US/storefront.ts',
          'en-US/auth.ts',
          'en-US/catalog.ts',
          'en-US/commerce.ts',
          'en-US/account.ts',
          'en-US/legal.ts',
        ],
      },
      {
        code: 'zh-CN',
        language: 'zh-CN',
        name: '简体中文',
        files: [
          'zh-CN/common.ts',
          'zh-CN/storefront.ts',
          'zh-CN/auth.ts',
          'zh-CN/catalog.ts',
          'zh-CN/commerce.ts',
          'zh-CN/account.ts',
          'zh-CN/legal.ts',
        ],
      },
      {
        code: 'ru-RU',
        language: 'ru-RU',
        name: 'Русский',
        files: [
          'ru-RU/common.ts',
          'ru-RU/storefront.ts',
          'ru-RU/auth.ts',
          'ru-RU/catalog.ts',
          'ru-RU/commerce.ts',
          'ru-RU/account.ts',
          'ru-RU/legal.ts',
        ],
      },
    ],
    detectBrowserLanguage: {
      useCookie: true,
      cookieKey: 'pelissa_locale',
      fallbackLocale: 'en-US',
      redirectOn: 'root',
    },
    compilation: {
      strictMessage: true,
      escapeHtml: false,
    },
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
