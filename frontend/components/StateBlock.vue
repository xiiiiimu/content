<script setup lang="ts">
defineProps<{
  kind: 'loading' | 'empty' | 'error' | 'auth'
  message: string
}>()
const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div
    class="px4 py6"
    :role="kind === 'error' || kind === 'auth' ? 'alert' : 'status'"
  >
    <p :class="kind === 'error' || kind === 'auth' ? 'text-$c-danger' : 'text-secondary'">
      {{ message }}
    </p>
    <button
      v-if="kind === 'error' || kind === 'auth'"
      type="button"
      class="btn-text mt2"
      @click="emit('retry')"
    >
      重试
    </button>
  </div>
</template>
