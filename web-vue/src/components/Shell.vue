<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import { lock, wallet } from "../session";

const route = useRoute();
const session = computed(() => wallet.session);

const links = [
  { to: "/app", label: "资产", end: true },
  { to: "/app/send", label: "转账", end: false },
  { to: "/app/activity", label: "记录", end: false },
  { to: "/app/cards", label: "卡片", end: false },
  { to: "/app/console", label: "链控制台", end: false },
  { to: "/app/settlement", label: "链上结算", end: false },
  { to: "/app/backup", label: "备份", end: false },
];

function active(link: { to: string; end: boolean }) {
  if (link.end) return route.path === link.to;
  return route.path === link.to || route.path.startsWith(`${link.to}/`);
}
</script>

<template>
  <div v-if="session" class="shell">
    <aside class="rail">
      <div class="brand">
        <span class="mark">Ae</span>
        <div>
          <strong>Aegis</strong>
          <small>钱包 / 卡</small>
        </div>
      </div>
      <nav>
        <router-link v-for="(link, index) in links" :key="link.to" :to="link.to" :class="{ active: active(link) }">
          <span>{{ String(index + 1).padStart(2, "0") }}</span>
          {{ link.label }}
        </router-link>
      </nav>
      <button class="text-btn lock" type="button" @click="lock">锁定</button>
    </aside>
    <main>
      <header class="topbar">
        <div>
          <p class="eyebrow">{{ session.label }}</p>
          <strong class="mode">
            {{ session.meta.mode === "sandbox" ? "沙箱" : "Live" }} · {{ session.meta.network === "mainnet" ? "主网" : "测试网" }}
          </strong>
        </div>
      </header>
      <div v-if="session.meta.warnings[0]" class="banner">{{ session.meta.warnings[0] }}</div>
      <router-view />
    </main>
  </div>
</template>
