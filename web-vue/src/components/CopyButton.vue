<script setup lang="ts">
import { ref } from "vue";

const props = defineProps<{ value: string }>();
const done = ref(false);

function copy() {
  void navigator.clipboard.writeText(props.value).then(() => {
    done.value = true;
    window.setTimeout(() => {
      done.value = false;
    }, 1200);
  });
}
</script>

<template>
  <button class="text-btn" type="button" @click="copy">{{ done ? "已复制" : "复制" }}</button>
</template>
