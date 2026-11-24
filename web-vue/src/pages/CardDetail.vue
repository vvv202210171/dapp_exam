<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { api } from "../api";
import Fault from "../components/Fault.vue";
import Field from "../components/Field.vue";
import { formatWhen } from "../format";
import { wallet } from "../session";
import type { Bill, Card, LedgerEntry, SettlementQuote, SettlementView } from "../types";
import { connectMetamask, sendWithMetamask, waitForReceipt, type PreparedCall } from "../wallet/metamask";

const STATUS: Record<string, string> = {
  active: "可使用",
  frozen: "已冻结",
  requested: "待制卡",
  issued: "已制卡",
  shipped: "已寄出",
  closed: "已注销",
};
const KIND: Record<string, string> = { topup: "充值", spend: "消费", withdraw: "提现", fee: "手续费" };
const NEXT: Record<string, { action: string; label: string }[]> = {
  requested: [{ action: "issue", label: "标记已制卡" }],
  issued: [{ action: "ship", label: "标记已寄出" }],
  shipped: [{ action: "activate", label: "激活" }],
  active: [{ action: "freeze", label: "冻结" }],
  frozen: [{ action: "unfreeze", label: "解冻" }],
};

const route = useRoute();
const session = computed(() => wallet.session);
const id = computed(() => String(route.params.id ?? ""));
const card = ref<Card | null>(null);
const secret = ref<{ pan: string; cvv: string } | null>(issuedSecret());
const bill = ref<Bill | null>(null);
const month = ref(currentMonth());
const amount = ref("25.00");
const merchant = ref("星巴克");
const error = ref<unknown>(null);
const busy = ref(false);
const notice = ref("");
const key = ref(crypto.randomUUID());

const entries = computed(() => [...(bill.value?.entries ?? [])].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)));
const panText = computed(() => (secret.value ? secret.value.pan.replace(/(.{4})/g, "$1 ").trim() : `•••• •••• •••• ${card.value?.last4 ?? ""}`));

async function loadCard() {
  card.value = await api<Card>(`/api/v1/cards/${id.value}`);
}

async function loadBill(value = month.value) {
  bill.value = await api<Bill>(`/api/v1/cards/${id.value}/bills?month=${value}`);
}

async function reveal() {
  try {
    secret.value = await api<{ pan: string; cvv: string }>(`/api/v1/cards/${id.value}/reveal`, { method: "POST" });
  } catch (err) {
    error.value = err;
  }
}

async function transition(action: string) {
  try {
    await api(`/api/v1/cards/${id.value}/transition`, { method: "POST", body: JSON.stringify({ action }) });
    await loadCard();
  } catch (err) {
    error.value = err;
  }
}

async function fundCard() {
  const current = session.value;
  if (!current) return;
  busy.value = true;
  error.value = null;
  notice.value = "";
  try {
    const fox = await connectMetamask();
    const expected = current.derived.ethereum.address;
    if (fox.toLowerCase() !== expected.toLowerCase()) {
      throw new Error(`小狐狸当前是 ${fox}。请换成 ${expected}。`);
    }
    const view = await api<SettlementView>(`/api/v1/accounts/${current.accountId}/settlement`);
    if (!view.online) throw new Error(view.hint || "结算链没有连上。");
    const needed = usdcUnits(amount.value);
    if (usdcUnits(view.walletUsdc ?? "0") < needed) {
      throw new Error(`钱包里只有 ${view.walletUsdc ?? "0"} USDC，不够划入 ${amount.value}。先到结算页领取测试 USDC。`);
    }
    if (usdcUnits(view.allowance ?? "0") < needed) {
      await sendAction(fox, "approve", "1000000000000");
    }
    const hash = await sendAction(fox, "deposit", amount.value);
    await api(`/api/v1/cards/${id.value}/topup`, { method: "POST", body: JSON.stringify({ amount: amount.value, txHash: hash }) });
    await loadCard();
    await loadBill();
  } catch (err) {
    error.value = err;
  } finally {
    busy.value = false;
  }
}

async function withdrawCard() {
  const current = session.value;
  if (!current) return;
  busy.value = true;
  error.value = null;
  notice.value = "";
  try {
    const fox = await connectMetamask();
    const expected = current.derived.ethereum.address;
    if (fox.toLowerCase() !== expected.toLowerCase()) {
      throw new Error(`小狐狸当前是 ${fox}。请换成 ${expected}。`);
    }
    const principal = centsOf(amount.value);
    const fee = principal / 100n > 0n ? principal / 100n : 1n;
    if (centsOf(card.value?.available ?? "0") < principal + fee) {
      throw new Error("卡余额不够这笔提现和 1% 手续费。");
    }
    const view = await api<SettlementView>(`/api/v1/accounts/${current.accountId}/settlement`);
    if (!view.online) throw new Error(view.hint || "结算链没有连上。");
    if (usdcUnits(view.available ?? "0") < usdcUnits(amount.value)) {
      throw new Error(`合约里只有 ${view.available ?? "0"} USDC，不够提现 ${amount.value}。`);
    }
    const hash = await sendAction(fox, "withdraw", amount.value);
    const ref = hash.replace(/^0x/i, "").toLowerCase();
    await api(`/api/v1/cards/${id.value}/withdraw`, {
      method: "POST",
      body: JSON.stringify({ amount: amount.value, idempotencyKey: ref, note: `链上提现 ${hash}` }),
    });
    notice.value = `已提现 ${amount.value} USDC 到钱包，卡另扣 1% 手续费。`;
    await loadCard();
    await loadBill();
  } catch (err) {
    error.value = err;
  } finally {
    busy.value = false;
  }
}

async function sendAction(from: string, action: "approve" | "deposit" | "withdraw", value: string) {
  const current = session.value;
  if (!current) throw new Error("钱包还锁着");
  const quote = await api<SettlementQuote>(`/api/v1/accounts/${current.accountId}/settlement/prepare`, {
    method: "POST",
    body: JSON.stringify({ action, amount: value }),
  });
  const hash = await sendWithMetamask(from, quote.payload as PreparedCall);
  await waitForReceipt(hash);
  return hash;
}

async function spend() {
  error.value = null;
  try {
    await api(`/api/v1/cards/${id.value}/spend`, {
      method: "POST",
      body: JSON.stringify({ amount: amount.value, merchant: merchant.value, mcc: "5814", idempotencyKey: key.value }),
    });
    key.value = crypto.randomUUID();
    await loadCard();
    await loadBill();
  } catch (err) {
    error.value = err;
  }
}

function changeMonth(value: string) {
  month.value = value;
  void loadBill(value).catch((err) => {
    error.value = err;
  });
}

watch(id, () => {
  card.value = null;
  bill.value = null;
  secret.value = issuedSecret();
  void Promise.all([loadCard(), loadBill()]).catch((err) => {
    error.value = err;
  });
});

onMounted(() => {
  void Promise.all([loadCard(), loadBill()]).catch((err) => {
    error.value = err;
  });
});

function issuedSecret() {
  const state = history.state as { pan?: string; cvv?: string } | null;
  return state?.pan ? { pan: state.pan, cvv: state.cvv ?? "" } : null;
}

function usdcUnits(value: string): bigint {
  const [whole, frac = ""] = (value.trim() || "0").split(".");
  return BigInt(`${whole || "0"}${(frac + "000000").slice(0, 6)}`);
}

function centsOf(value: string): bigint {
  const [whole, frac = ""] = (value.trim() || "0").split(".");
  return BigInt(`${whole || "0"}${(frac + "00").slice(0, 2)}`);
}

function currentMonth(): string {
  const now = new Date();
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, "0")}`;
}

function entryDetail(entry: LedgerEntry) {
  return entry.merchant || entry.note;
}
</script>

<template>
  <Fault v-if="!card" :error="error" />
  <section v-else>
    <router-link to="/app/cards" class="text-btn">返回卡片</router-link>
    <div class="two" style="margin-top: 16px">
      <div :class="['plastic', card.kind]">
        <div class="row" style="justify-content: space-between">
          <span>{{ card.kind === "virtual" ? "虚拟卡" : "实体卡" }}</span>
          <span>{{ STATUS[card.status] }}</span>
        </div>
        <div class="chip" />
        <div>
          <div class="last4">{{ panText }}</div>
          <div class="row" style="justify-content: space-between">
            <span>{{ card.holder }}</span>
            <span>{{ secret ? `CVV ${secret.cvv}` : card.expiry }}</span>
          </div>
        </div>
      </div>
      <div class="sheet stack">
        <h2>${{ card.available }}</h2>
        <p class="muted">可用余额 · {{ card.currency }} · {{ card.expiry }}</p>
        <button v-if="!secret" class="ghost" type="button" @click="reveal">查看卡号</button>
        <div class="row">
          <button v-for="item in NEXT[card.status] ?? []" :key="item.action" class="ghost" type="button" @click="transition(item.action)">{{ item.label }}</button>
          <button v-if="card.status !== 'closed'" class="text-btn" type="button" @click="transition('close')">注销</button>
        </div>
        <Fault :error="error" />
      </div>
    </div>
    <div class="two" style="margin-top: 18px">
      <form class="sheet stack" @submit.prevent="fundCard">
        <h2>充值</h2>
        <Field label="金额 USD"><input v-model="amount" /></Field>
        <p class="muted">授权不够时，小狐狸会先弹出一笔授权，确认后再弹出划入。两笔都确认后，卡余额才加上这笔钱。拒绝签名则卡余额不变。</p>
        <button class="primary" type="submit" :disabled="busy">{{ busy ? "等待小狐狸确认…" : "划入 USDC 并入账" }}</button>
        <button class="ghost" type="button" :disabled="busy" @click="withdrawCard">{{ busy ? "等待小狐狸确认…" : "提现（另收 1%）" }}</button>
        <p class="muted">提现会弹出小狐狸，把同样金额的 USDC 从结算合约退回钱包，卡余额再扣这笔钱和 1% 手续费。</p>
        <p v-if="notice" class="ok">{{ notice }}</p>
        <Fault :error="error" />
      </form>
      <form class="sheet stack" @submit.prevent="spend">
        <h2>消费</h2>
        <Field label="商户"><input v-model="merchant" /></Field>
        <p class="muted">实体卡在激活前可以充值，不能消费。冻结和注销也不能扣款。</p>
        <button class="primary" type="submit">记一笔消费</button>
      </form>
    </div>
    <div class="page-head" style="margin-top: 28px">
      <h2>账单</h2>
      <input type="month" :value="month" @change="changeMonth(($event.target as HTMLInputElement).value)" />
    </div>
    <template v-if="bill">
      <div class="bill">
        <div><span class="muted">期初</span><b class="mono">{{ bill.opening }}</b></div>
        <div><span class="muted">充值</span><b class="mono">{{ bill.topup }}</b></div>
        <div><span class="muted">消费</span><b class="mono">{{ bill.spend }}</b></div>
        <div><span class="muted">提现 / 费</span><b class="mono">{{ bill.withdraw }} / {{ bill.fees }}</b></div>
        <div><span class="muted">期末</span><b class="mono">{{ bill.closing }}</b></div>
      </div>
      <div class="sheet">
        <p v-if="entries.length === 0" class="muted">这个月没有账。</p>
        <div v-for="entry in entries" :key="entry.id" class="entry">
          <div>
            <strong>{{ KIND[entry.kind] ?? entry.kind }}</strong>
            <div class="muted">{{ formatWhen(entry.createdAt) }}</div>
            <div v-if="entryDetail(entry)" class="muted">{{ entryDetail(entry) }}</div>
          </div>
          <div class="right">
            <div class="mono">{{ entry.kind === "topup" ? "+" : "-" }}${{ entry.amount }}</div>
            <div class="muted">余额 ${{ entry.balanceAfter }}</div>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
