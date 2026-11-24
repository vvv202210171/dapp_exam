<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../api";
import CopyButton from "../components/CopyButton.vue";
import Fault from "../components/Fault.vue";
import { formatWhen, shortAddress } from "../format";
import { wallet } from "../session";
import { CHAIN_LABEL, type Transfer } from "../types";

const STATUS: Record<Transfer["status"], string> = {
  signed: "已签名",
  broadcast: "已广播",
  confirmed: "已确认",
  failed: "失败",
};

const session = computed(() => wallet.session);
const rows = ref<Transfer[]>([]);
const error = ref<unknown>(null);

async function load() {
  const current = session.value;
  if (!current) return;
  try {
    const data = await api<{ transfers: Transfer[] }>(`/api/v1/accounts/${current.accountId}/transfers`);
    rows.value = data.transfers;
    error.value = null;
  } catch (err) {
    error.value = err;
  }
}

async function act(id: string, kind: "broadcast" | "refresh") {
  error.value = null;
  try {
    await api(`/api/v1/transfers/${id}/${kind}`, { method: "POST" });
    await load();
  } catch (err) {
    error.value = err;
    await load();
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="page-head">
      <div>
        <p class="eyebrow">记录</p>
        <h2>只记录这只钱包发出的交易</h2>
      </div>
      <button class="ghost" type="button" @click="load">刷新</button>
    </div>
    <p class="muted">节点不能廉价地给出某个地址的完整历史。这里的账本是本地发起记录，状态再向节点核对。全量历史需要索引器。</p>
    <Fault :error="error" />
    <div class="sheet" style="margin-top: 16px">
      <p v-if="rows.length === 0" class="muted">还没有交易。</p>
      <article v-for="row in rows" :key="row.id" class="transfer">
        <div>
          <strong>{{ CHAIN_LABEL[row.chain] }} · {{ row.amount }} {{ row.asset }}</strong>
          <div class="muted">{{ formatWhen(row.createdAt) }}</div>
          <div class="muted">{{ shortAddress(row.from) }} → {{ shortAddress(row.to) }}</div>
          <div class="tag">{{ STATUS[row.status] }}{{ row.fee ? ` · 费 ${row.fee}` : "" }}</div>
          <div v-if="row.detail" class="muted">{{ row.detail }}</div>
          <div v-if="row.txHash" class="addr">{{ shortAddress(row.txHash) }} <CopyButton :value="row.txHash" /></div>
        </div>
        <div class="row">
          <button v-if="row.status === 'signed'" class="primary" type="button" @click="act(row.id, 'broadcast')">广播</button>
          <button v-if="row.txHash" class="ghost" type="button" @click="act(row.id, 'refresh')">查状态</button>
        </div>
      </article>
    </div>
  </section>
</template>
