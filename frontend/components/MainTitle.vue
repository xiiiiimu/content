<script setup lang="ts">
defineProps<{ icon?: string }>()
</script>

<template>
  <h1 class="flex items-center gap2 text-xl font-bold truncate">
    <span v-if="icon" :class="icon" class="text-primary" />
    <slot />
  </h1>
</template>
