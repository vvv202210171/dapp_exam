<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../api";
import CopyButton from "../components/CopyButton.vue";
import Fault from "../components/Fault.vue";
import Field from "../components/Field.vue";
import { nextAddress, wallet } from "../session";
import type { Chain, Portfolio, PrepareResult, Transfer } from "../types";
import { signPayload } from "../wallet/sign";

const session = computed(() => wallet.session);
const chain = ref<Chain>("ethereum");
const asset = ref("ETH");
const to = ref("");
const amount = ref("");
const quote = ref<PrepareResult | null>(null);
const result = ref<Transfer | null>(null);
const error = ref<unknown>(null);
const busy = ref(false);
const portfolio = ref<Portfolio | null>(null);
const balanceError = ref<unknown>(null);
const balanceBusy = ref(false);

const info = computed(() => session.value?.meta.chains.find((item) => item.id === chain.value));
const assets = computed(() => info.value?.assets ?? []);
const selected = computed(() => assets.value.find((item) => item.symbol === asset.value) ?? assets.value[0]);
const from = computed(() => (session.value ? session.value.derived[chain.value].address : ""));
const chainRow = computed(() => portfolio.value?.chains.find((item) => item.chain === chain.value));
const balance = computed(() => chainRow.value?.assets.find((item) => item.symbol === (selected.value?.symbol ?? asset.value)));
const nativeBalance = computed(() => chainRow.value?.assets.find((item) => item.kind === "native"));

async function loadBalance() {
  const current = session.value;
  if (!current) return;
  balanceBusy.value = true;
  balanceError.value = null;
  try {
    portfolio.value = await api<Portfolio>(`/api/v1/accounts/${current.accountId}/assets`);
  } catch (err) {
    balanceError.value = err;
  } finally {
    balanceBusy.value = false;
  }
}

function resetQuote() {
  quote.value = null;
  result.value = null;
}

function changeChain(value: string) {
  const next = value as Chain;
  chain.value = next;
  asset.value = session.value?.meta.chains.find((item) => item.id === next)?.assets[0]?.symbol ?? "";
  resetQuote();
}

async function prepare() {
  const current = session.value;
  if (!current) return;
  busy.value = true;
  error.value = null;
  result.value = null;
  try {
    quote.value = await api<PrepareResult>("/api/v1/transfers/prepare", {
      method: "POST",
      body: JSON.stringify({
        accountId: current.accountId,
        chain: chain.value,
        asset: selected.value?.symbol,
        from: from.value,
        to: to.value,
        amount: amount.value,
      }),
    });
  } catch (err) {
    quote.value = null;
    error.value = err;
  } finally {
    busy.value = false;
  }
}

async function sign(broadcast: boolean) {
  const current = session.value;
  const prepared = quote.value;
  if (!current || !prepared || !selected.value) return;
  busy.value = true;
  error.value = null;
  try {
   
    const signedPayload = await signPayload(chain.value, current.derived[chain.value].privateKey, prepared.payload);
    result.value = await api<Transfer>("/api/v1/transfers", {
      method: "POST",
      body: JSON.stringify({
        accountId: current.accountId,
        chain: chain.value,
        asset: prepared.asset,
        from: from.value,
        to: to.value,
        amount: amount.value,
        fee: `${prepared.fee.amount} ${prepared.fee.symbol}`,
        signedPayload,
        broadcast,
      }),
    });
    if (broadcast) await loadBalance();
  } catch (err) {
    error.value = err;
  } finally {
    busy.value = false;
  }
}

function fillNext() {
  to.value = nextAddress(chain.value);
  resetQuote();
}

onMounted(() => {
  void loadBalance();
});

function resultTitle(status: Transfer["status"]) {
  if (status === "signed") return "已签名，尚未广播";
  if (status === "confirmed") return "沙箱已记为确认";
  return "已提交";
}
</script>

<template>
  <section v-if="session">
    <div class="page-head">
      <div>
        <p class="eyebrow">转账</p>
        <h2>先报价，再离线签名</h2>
      </div>
    </div>
    <div class="two">
      <form class="sheet stack" @submit.prevent="prepare">
        <Field label="链">
          <select :value="chain" @change="changeChain(($event.target as HTMLSelectElement).value)">
            <option v-for="item in session.meta.chains" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
        </Field>
        <Field label="资产">
          <select v-model="asset" @change="resetQuote">
            <option v-for="item in assets" :key="item.symbol" :value="item.symbol">{{ item.symbol }} · {{ item.name }}</option>
          </select>
        </Field>
        <Field label="从">
          <input class="mono" readonly :value="from" />
        </Field>
        <div class="stack">
          <div class="row">
            <span v-if="balanceBusy" class="muted">正在查询余额…</span>
            <span v-else-if="balanceError" class="bad">余额暂时查不到</span>
            <span v-else-if="balance" class="mono">余额 {{ balance.amount }} {{ balance.symbol }}</span>
            <span v-else class="muted">余额还没取到</span>
            <button class="text-btn" type="button" :disabled="balanceBusy" @click="loadBalance">刷新余额</button>
          </div>
          <div v-if="balance && !balance.ok && balance.error" class="bad">{{ balance.error.message }}</div>
          <div v-if="nativeBalance && selected && nativeBalance.symbol !== selected.symbol" class="muted">
            {{ nativeBalance.symbol }} {{ nativeBalance.amount }}，手续费从这里扣
          </div>
        </div>
        <Field label="到">
          <input v-model="to" class="mono" placeholder="收款地址" @input="resetQuote" />
        </Field>
        <button class="text-btn" type="button" @click="fillNext">填入本钱包的下一个地址</button>
        <Field label="金额">
          <input v-model="amount" :placeholder="selected ? `最多 ${selected.decimals} 位小数` : ''" @input="resetQuote" />
        </Field>
        <Fault :error="error" />
        <button class="primary" :disabled="busy || !to || !amount">获取报价</button>
      </form>
      <aside class="sheet stack">
        <p v-if="!quote" class="muted">报价会返回 gas、UTXO 或 blockhash。签名发生在这个页面，私钥不会出现在请求里。</p>
        <template v-if="quote">
          <h2>报价</h2>
          <p>手续费 <b class="mono">{{ quote.fee.amount }} {{ quote.fee.symbol }}</b>{{ quote.fee.usd ? ` · $${quote.fee.usd}` : "" }}</p>
          <p v-for="item in quote.warnings" :key="item" class="warn">{{ item }}</p>
          <div class="row">
            <button class="ghost" type="button" :disabled="busy" @click="sign(false)">只签名</button>
            <button class="primary" type="button" :disabled="busy" @click="sign(true)">签名并广播</button>
          </div>
        </template>
        <div v-if="result" class="stack">
          <strong>{{ resultTitle(result.status) }}</strong>
          <div v-if="result.txHash" class="addr">哈希 {{ result.txHash }} <CopyButton :value="result.txHash" /></div>
          <div class="payload">{{ result.signedPayload }}</div>
          <p class="muted">{{ result.detail }}</p>
        </div>
      </aside>
    </div>
  </section>
</template>
