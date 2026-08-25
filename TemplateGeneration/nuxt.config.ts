// https://nuxt.com/docs/api/configuration/nuxt-config
import Components from 'unplugin-vue-components/vite'
import {NaiveUiResolver} from 'unplugin-vue-components/resolvers'

export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: true },

  modules: [
    '@nuxt/eslint',
    '@nuxtjs/tailwindcss',
    '@pinia/nuxt',
    'nuxtjs-naive-ui'
  ],
  ssr: true,
  devServer: {
    port: 8040,
    host: '0.0.0.0'
  },
  vite: {
    ssr: {
      // vueuc 的 main 入口指向 CommonJS。EdgeOne 的 SSR Node 运行时
      // 不一定能从该入口推断 VResizeObserver 等具名导出，因此必须在
      // Vite SSR 阶段内联它，避免产物保留 `import { ... } from 'vueuc'`。
      noExternal: ['vueuc'],
    },
    plugins: [
      // 模板里裸用 n-button 等标签时，编译期自动注入 naive-ui 的 import
      Components({
        dts: 'components.d.ts',
        resolvers: [NaiveUiResolver()],
      }),
    ],
  },
})
