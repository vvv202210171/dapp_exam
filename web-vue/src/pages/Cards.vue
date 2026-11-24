<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { api } from "../api";
import Fault from "../components/Fault.vue";
import Field from "../components/Field.vue";
import { wallet } from "../session";
import type { Card } from "../types";

const STATUS: Record<string, string> = {
  active: "可使用",
  frozen: "已冻结",
  requested: "待制卡",
  issued: "已制卡",
  shipped: "已寄出",
  closed: "已注销",
};

const router = useRouter();
const session = computed(() => wallet.session);
const cards = ref<Card[]>([]);
const holder = ref(session.value?.label ?? "");
const error = ref<unknown>(null);
const busy = ref(false);

async function load() {
  const current = session.value;
  if (!current) return;
  const data = await api<{ cards: Card[] }>(`/api/v1/accounts/${current.accountId}/cards`);
  cards.value = data.cards;
}

async function create(kind: "virtual" | "physical") {
  const current = session.value;
  if (!current) return;
  busy.value = true;
  error.value = null;
  try {
    const issued = await api<{ card: Card; pan: string; cvv: string }>(`/api/v1/accounts/${current.accountId}/cards`, {
      method: "POST",
      body: JSON.stringify({ kind, holder: holder.value }),
    });
    await router.push({ path: `/app/cards/${issued.card.id}`, state: { pan: issued.pan, cvv: issued.cvv } });
  } catch (err) {
    error.value = err;
  } finally {
    busy.value = false;
  }
}

onMounted(() => {
  void load().catch((err) => {
    error.value = err;
  });
});
</script>

<template>
  <section v-if="session">
    <div class="page-head">
      <div>
        <p class="eyebrow">支付卡</p>
        <h2>虚拟卡现在能用，实体卡要走状态机</h2>
      </div>
    </div>
    <div class="sheet stack" style="margin-bottom: 18px">
      <Field label="持卡人"><input v-model="holder" /></Field>
      <div class="row">
        <button class="primary" type="button" :disabled="busy" @click="create('virtual')">开虚拟卡</button>
        <button class="ghost" type="button" :disabled="busy" @click="create('physical')">申请实体卡</button>
      </div>
      <p class="muted">卡号以 990016 开头，只在这个沙箱里有效。完整卡号用 AES-GCM 存在本地数据库，密钥不进前端。</p>
      <Fault :error="error" />
    </div>
    <div class="cards">
      <router-link v-for="card in cards" :key="card.id" :to="`/app/cards/${card.id}`" :class="['plastic', card.kind]">
        <div class="row" style="justify-content: space-between">
          <span>{{ card.kind === "virtual" ? "虚拟卡" : "实体卡" }}</span>
          <span>{{ STATUS[card.status] ?? card.status }}</span>
        </div>
        <div class="chip" />
        <div>
          <div class="last4">•••• {{ card.last4 }}</div>
          <div class="row" style="justify-content: space-between">
            <span>{{ card.holder }}</span>
            <b>${{ card.available }}</b>
          </div>
        </div>
      </router-link>
    </div>
  </section>
</template>
