<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../api";
import Fault from "../components/Fault.vue";
import { formatWhen } from "../format";
import { wallet } from "../session";
import type { SettlementQuote, SettlementReceipt, SettlementView } from "../types";
import { connectMetamask, sendWithMetamask, waitForReceipt, watchUsdc, type PreparedCall } from "../wallet/metamask";

const session = computed(() => wallet.session);
const view = ref<SettlementView | null>(null);
const amount = ref("25");
const error = ref<unknown>(null);
const log = ref<string[]>([]);
const busy = ref(false);
const fox = ref("");

const expected = computed(() => (view.value?.user || session.value?.derived.ethereum.address || "").toLowerCase());
const foxReady = computed(() => fox.value !== "" && fox.value.toLowerCase() === expected.value);

async function load() {
  const current = session.value;
  if (!current) return;
  view.value = await api<SettlementView>(`/api/v1/accounts/${current.accountId}/settlement`);
}

async function signAndSend(action: "approve" | "deposit" | "withdraw", value: string) {
  const current = session.value;
  if (!current) throw new Error("钱包还锁着");
  if (!foxReady.value) throw new Error("先连接小狐狸，并选中这个钱包的以太坊地址。");
  const quote = await api<SettlementQuote>(`/api/v1/accounts/${current.accountId}/settlement/prepare`, {
    method: "POST",
    body: JSON.stringify({ action, amount: value }),
  });
  const hash = await sendWithMetamask(fox.value, quote.payload as PreparedCall);
  await waitForReceipt(hash);
  log.value = [`${action} confirmed ${hash}`, ...log.value];
}

async function operator(path: string, body?: Record<string, string>) {
  const current = session.value;
  if (!current) throw new Error("钱包还锁着");
  return api<SettlementReceipt>(`/api/v1/accounts/${current.accountId}/settlement/${path}`, {
    method: "POST",
    body: JSON.stringify(body ?? {}),
  });
}

async function run(work: () => Promise<void>) {
  busy.value = true;
  error.value = null;
  try {
    await work();
    await load();
  } catch (err) {
    error.value = err;
  } finally {
    busy.value = false;
  }
}

function connect() {
  return run(async () => {
    fox.value = await connectMetamask();
  });
}

function showUsdc() {
  return run(() => watchUsdc(view.value?.usdc ?? ""));
}

function faucet() {
  const current = session.value;
  if (!current) return;
  return run(async () => {
    const sent = await api<{ ethTx: string; usdcTx: string }>(`/api/v1/accounts/${current.accountId}/settlement/faucet`, {
      method: "POST",
      body: "{}",
    });
    log.value = [`faucet USDC ${sent.usdcTx}`, `faucet ETH ${sent.ethTx}`, ...log.value];
  });
}

function approveAndDeposit() {
  return run(async () => {
    await signAndSend("approve", amount.value);
    await signAndSend("deposit", amount.value);
  });
}

function withdraw() {
  return run(async () => {
    if (!foxReady.value) throw new Error("先连接小狐狸，并选中这个钱包的以太坊地址。");
    await signAndSend("withdraw", amount.value);
  });
}

function settle(entryId: string) {
  return run(async () => {
    const receipt = await operator("settle", { entryId });
    log.value = [`settle ${receipt.status} ${receipt.hash}`, ...log.value];
  });
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
        <p class="eyebrow">Solidity</p>
        <h2>卡的链上那一半</h2>
      </div>
    </div>
    <p class="muted">{{ session.meta.settlement.note }}</p>
    <div class="steps">
      <article><span>01</span><h3>小狐狸签名</h3><p class="muted">approve、deposit、withdraw 会弹出小狐狸。账户必须是下面这个以太坊地址。</p></article>
      <article><span>02</span><h3>卡账本</h3><p class="muted">卡片里的充值和消费仍按“分”记账。消费先落成流水，不直接动链。</p></article>
      <article><span>03</span><h3>运营方结算</h3><p class="muted">服务端用部署账户调用 settle。账单号是流水 id 的 SHA-256，同一笔不能结两次。</p></article>
    </div>
    <Fault :error="error" />
    <div v-if="view && !view.online" class="banner">{{ view.hint }}</div>
    <div class="two">
      <div class="sheet stack">
        <h2>你的签名</h2>
        <div class="kv">
          <span>链</span><b class="mono">{{ view?.online ? `Hardhat ${view.chainId}` : "未连接" }}</b>
          <span>ETH</span><b class="mono">{{ view?.eth ?? "—" }}</b>
          <span>钱包 USDC</span><b class="mono">{{ view?.walletUsdc ?? "—" }}</b>
          <span>已授权</span><b class="mono">{{ view?.allowance ?? "—" }}</b>
          <span>合约里可用</span><b class="mono">{{ view?.available ?? "—" }}</b>
        </div>
        <p class="muted">用装了小狐狸的浏览器打开本页。连接后，授权、存入、提现都会弹出确认。领取测试币和结算不弹窗，那是运营方账户发的。</p>
        <div class="row">
          <button class="primary" type="button" :disabled="busy" @click="connect">{{ fox ? "重新连接小狐狸" : "连接小狐狸" }}</button>
          <button class="ghost" type="button" :disabled="busy || !foxReady || !view?.usdc" @click="showUsdc">在小狐狸里显示 USDC</button>
        </div>
        <p v-if="fox && !foxReady" class="bad">小狐狸当前是 {{ fox }}。请换成 {{ expected }}，否则存进去的钱对不上这张卡。</p>
        <p v-if="foxReady" class="ok">小狐狸已接上 {{ fox }}</p>
        <button class="ghost" type="button" :disabled="busy || !view?.online" @click="faucet">领取测试 ETH 和 USDC</button>
        <label class="field">金额（USDC，6 位小数）
          <input v-model="amount" />
        </label>
        <div class="row">
          <button class="primary" type="button" :disabled="busy || !view?.online || !foxReady" @click="approveAndDeposit">授权并存入</button>
          <button class="ghost" type="button" :disabled="busy || !view?.online" @click="withdraw">提现</button>
        </div>
        <pre v-if="log.length > 0" class="payload">{{ log.join("\n") }}</pre>
      </div>
      <div class="sheet stack">
        <h2>待结算的消费</h2>
        <p class="muted">到<router-link to="/app/cards">卡片</router-link>点「划入 USDC 并入账」，确认后卡余额才会增加。这里的「授权并存入」只把币放进合约，不加卡余额。消费仍只扣卡账本。</p>
        <div class="kv">
          <span>合约</span><b class="mono">{{ view?.settlement ?? "—" }}</b>
          <span>USDC</span><b class="mono">{{ view?.usdc ?? "—" }}</b>
          <span>运营方</span><b class="mono">{{ view?.operator ?? "—" }}</b>
          <span>你的地址</span><b class="mono">{{ view?.user ?? session.derived.ethereum.address }}</b>
        </div>
        <p v-if="view && view.spends.length === 0" class="muted">还没有消费流水。</p>
        <div v-for="spend in view?.spends ?? []" :key="spend.entryId" class="entry">
          <div>
            <strong>{{ spend.merchant || "消费" }}</strong>
            <div class="muted">{{ formatWhen(spend.createdAt) }}</div>
            <div class="muted">尾号 {{ spend.last4 }} · ${{ spend.amount }} · {{ spend.units }} USDC</div>
            <div v-if="spend.txHash" class="mono">{{ spend.txHash }}</div>
          </div>
          <span v-if="spend.settled" class="ok">已结算</span>
          <button v-else class="primary" type="button" :disabled="busy || !view?.online" @click="settle(spend.entryId)">结算</button>
        </div>
      </div>
    </div>
  </section>
</template>
