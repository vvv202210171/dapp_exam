<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { api } from "../api";
import Fault from "../components/Fault.vue";
import Field from "../components/Field.vue";
import { wallet } from "../session";
import type { Chain, Health } from "../types";

const COOKBOOK = [
  ["RPC_TIMEOUT", "节点超时", "先看是所有链都超时，还是只有一条。所有链都超时多半是本机网络；只有一条就换那条链的 RPC。"],
  ["RATE_LIMITED", "公共节点限流", "不要用定时器猛刷余额。演示完就停。真环境换自己的节点或带 key 的服务。"],
  ["NONCE_TOO_LOW", "Nonce 过低", "重新报价。报价用的是 pending nonce。上一笔还在内存池时，要么等，要么加价替换。"],
  ["INSUFFICIENT_FUNDS", "余额不够", "代币转账也要付原生币 gas。ETH 上的 USDT 是 6 位小数，BSC 上的 USDT 是 18 位，填错会差 12 个零。"],
  ["REVERTED", "合约回滚", "用同样的 calldata 做一次 eth_call，看 revert 原因。常见的是余额不足、没 approve、USDT 黑名单。"],
  ["BLOCKHASH_EXPIRED", "Solana blockhash 过期", "大约一分钟。重新报价再签，不要拿旧 payload 反复广播。"],
  ["ACCOUNT_INACTIVE", "TRON 账户未激活", "地址要先收到 TRX 才会被创建。空账户直接转出会被节点拒绝。"],
];

const session = computed(() => wallet.session);
const health = ref<Health[]>([]);
const chain = ref<Chain>("ethereum");
const probes = ref<{ name: string; title: string; description: string }[]>([]);
const probe = ref("echo");
const address = ref(session.value?.derived.ethereum.address ?? "");
const result = ref("");
const error = ref<unknown>(null);

function changeChain(value: string) {
  const next = value as Chain;
  chain.value = next;
  const current = session.value;
  if (current) address.value = current.derived[next].address;
}

async function run() {
  error.value = null;
  try {
    const data = await api<unknown>(`/api/v1/chains/${chain.value}/probes/${probe.value}`, {
      method: "POST",
      body: JSON.stringify({ address: address.value }),
    });
    result.value = JSON.stringify(data, null, 2);
  } catch (err) {
    result.value = "";
    error.value = err;
  }
}

watch(chain, (next) => {
  void api<{ probes: { name: string; title: string; description: string }[] }>(`/api/v1/chains/${next}/probes`)
    .then((data) => {
      probes.value = data.probes;
      probe.value = data.probes[0]?.name ?? "";
    })
    .catch((err) => {
      error.value = err;
    });
}, { immediate: true });

onMounted(() => {
  void api<{ chains: Health[] }>("/api/v1/chains/health")
    .then((data) => {
      health.value = data.chains;
    })
    .catch((err) => {
      error.value = err;
    });
});
</script>

<template>
  <section v-if="session">
    <div class="page-head">
      <div>
        <p class="eyebrow">链控制台</p>
        <h2>节点通不通，错误下一步看什么</h2>
      </div>
    </div>
    <div class="health-grid">
      <article v-for="item in health" :key="item.chain" class="health">
        <div>
          <strong>{{ item.name }}</strong>
          <div class="tag">{{ item.endpoint }}</div>
        </div>
        <div :class="item.ok ? 'ok' : 'bad'">{{ item.ok ? `${item.latencyMs} ms` : "失败" }}</div>
      </article>
    </div>
    <div class="two">
      <div class="sheet stack">
        <Field label="链">
          <select :value="chain" @change="changeChain(($event.target as HTMLSelectElement).value)">
            <option v-for="item in session.meta.chains" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
        </Field>
        <Field label="探针">
          <select v-model="probe">
            <option v-for="item in probes" :key="item.name" :value="item.name">{{ item.title }} · {{ item.description }}</option>
          </select>
        </Field>
        <Field label="地址"><input v-model="address" class="mono" /></Field>
        <button class="primary" type="button" @click="run">调用</button>
        <p class="muted">方法是白名单。后端不会接受任意 URL，避免变成开放代理。</p>
        <Fault :error="error" />
        <pre v-if="result" class="payload">{{ result }}</pre>
      </div>
      <div class="sheet">
        <h2>排查手册</h2>
        <div class="cookbook">
          <article v-for="[code, title, hint] in COOKBOOK" :key="code">
            <strong>{{ title }}</strong>
            <div class="tag">{{ code }}</div>
            <p class="muted">{{ hint }}</p>
          </article>
        </div>
      </div>
    </div>
  </section>
</template>
