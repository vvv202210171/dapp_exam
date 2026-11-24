<script setup lang="ts">
import { computed } from "vue";
import { ApiError } from "../api";

const props = defineProps<{ error: unknown }>();

const message = computed(() => {
  if (!props.error) return "";
  return props.error instanceof Error ? props.error.message : "出错了";
});
const hint = computed(() => (props.error instanceof ApiError ? props.error.hint : undefined));
const code = computed(() => (props.error instanceof ApiError ? props.error.code : undefined));
</script>

<template>
  <div v-if="error" class="fault" role="alert">
    <strong>{{ message }}</strong>
    <span v-if="code" class="code">{{ code }}</span>
    <p v-if="hint">{{ hint }}</p>
  </div>
</template>
