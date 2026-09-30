<template>
  <a-config-provider
    :theme="{
      token: {
        colorPrimary: '#1054B9',
        borderRadius: 6,
        colorBgLayout: '#f0f2f5'
      }
    }"
  >
    <router-view />
  </a-config-provider>
</template>

<script setup>
</script>
