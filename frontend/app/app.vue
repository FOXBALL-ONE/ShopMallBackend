<script setup lang="ts">
const { locale } = useI18n()

useHead(() => ({
  htmlAttrs: {
    lang: locale.value,
    dir: 'ltr',
  },
}))
</script>

<template>
  <UApp>
    <NuxtRouteAnnouncer />
    <AnnouncementExperience />
    <NuxtPage />
  </UApp>
</template>
