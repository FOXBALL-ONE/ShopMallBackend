// https://nuxt.com/docs/api/configuration/nuxt-config
import Components from 'unplugin-vue-components/vite'
import { NaiveUiResolver } from 'unplugin-vue-components/resolvers'

export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: true },
  modules: ['nuxtjs-naive-ui'],
  runtimeConfig: {
    public: {
      // 用户端及管理员共享 API 基址；可用 NUXT_PUBLIC_API_BASE 覆盖
      apiBase: 'http://127.0.0.1:8080/api',
      // 纯管理员 API 基址；可用 NUXT_PUBLIC_ADMIN_API_BASE 覆盖
      adminApiBase: 'http://127.0.0.1:8080/admin/api',
      // 是否启用管理后台的登录验证路由守卫
      loginAuthEnabled: true,
      // 公告的业务时区，须与后端 ANNOUNCEMENT_TIME_ZONE 保持一致。
      announcementTimeZone: 'Asia/Shanghai',
      // 首页推荐方案的业务时区，须与后端 HOME_RECOMMENDATION_TIME_ZONE 保持一致。
      homeRecommendationTimeZone: 'Asia/Shanghai',
    },
  },
  vite: {
    plugins: [
      // 模板里裸用 n-button 等标签时，编译期自动注入 naive-ui 的 import
      Components({
        dts: 'components.d.ts',
        resolvers: [NaiveUiResolver()],
      }),
    ],
  },
})
