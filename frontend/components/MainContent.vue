<template>
  <div>
    <header
      v-if="$slots.title"
      class="sticky top-0 z-5 flex items-center gap2 px4 py3 border-b border-base bg-base/90 backdrop-blur"
    >
      <button
        v-if="back"
        type="button"
        class="btn-text -ms2"
        aria-label="返回"
        @click="onBack"
      >
        <span class="i-ri-arrow-left-line text-lg" />
      </button>
      <slot name="title" />
    </header>
    <slot />
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{ back?: boolean | string }>()
const router = useRouter()
function onBack() {
  if (typeof props.back === 'string' && props.back) router.push(props.back)
  else router.back()
}
</script>
