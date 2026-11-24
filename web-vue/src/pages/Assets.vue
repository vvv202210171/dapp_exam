<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import QRCode from "qrcode";
import { api } from "../api";
import CopyButton from "../components/CopyButton.vue";
import Fault from "../components/Fault.vue";
import { formatWhen, money, shortAddress } from "../format";
import { wallet } from "../session";
import type { Portfolio, SettlementView, Transfer } from "../types";
import { localUsdcHistory, type UsdcMove } from "../usdcHistory";

const session = computed(() => wallet.session);
const data = ref<Portfolio | null>(null);
const error = ref<unknown>(null);
const receive = ref<{ address: string; title: string } | null>(null);
const transfers = ref<Transfer[]>([]);
const usdc = ref<UsdcMove[]>([]);
const qr = ref("");

const USDC_KIND: Record<UsdcMove["kind"], string> = {
  mint: "领取",
  deposit: "划入合约",
  withdraw: "提现",
  in: "转入",
  out: "转出",
};

const STATUS: Record<Transfer["status"], string> = {
  signed: "已签名",
  broadcast: "已广播",
  confirmed: "已确认",
  failed: "失败",
};

function sameAddress(left: string, right: string) {
  return left.trim().toLowerCase() === right.trim().toLowerCase();
}

function chainTransfers(address: string, chain: string) {
  return transfers.value.filter((row) => row.chain === chain && (sameAddress(row.from, address) || sameAddress(row.to, address)));
}

async function load() {
  const current = session.value;
  if (!current) return;
  error.value = null;
  try {
    data.value = await api<Portfolio>(`/api/v1/accounts/${current.accountId}/assets`);
    const history = await api<{ transfers: Transfer[] }>(`/api/v1/accounts/${current.accountId}/transfers`);
    transfers.value = history.transfers;
    try {
      const view = await api<SettlementView>(`/api/v1/accounts/${current.accountId}/settlement`);
      if (view.online && view.rpc && view.usdc && view.settlement && view.user) {
        usdc.value = await localUsdcHistory(view.rpc, view.usdc, view.settlement, view.user);
      } else {
        usdc.value = [];
      }
    } catch {
      usdc.value = [];
    }
  } catch (err) {
    error.value = err;
  }
}

watch(receive, (next) => {
  qr.value = "";
  if (!next) return;
  void QRCode.toDataURL(next.address, { margin: 1, width: 220, color: { dark: "#172000", light: "#f6f3e8" } }).then((src) => {
    qr.value = src;
  });
});

onMounted(() => {
  void load();
});
</script>

<template>
  <section v-if="session">
    <div class="page-head">
      <div>
        <p class="eyebrow">资产</p>
        <h2>五条链，一次看完</h2>
      </div>
      <div class="total">
        <span class="muted">估算{{ data?.priceSource === "static" ? " · 静态价格" : "" }}</span>
        <b>${{ money(data?.totalUsd) }}</b>
      </div>
    </div>
    <div class="row" style="margin-bottom: 16px">
      <button class="ghost" type="button" @click="load">刷新</button>
      <span class="muted">刷新是手动的，避免对着公共节点轮询。</span>
    </div>
    <Fault :error="error" />
    <div class="grid">
      <article v-for="chain in data?.chains ?? []" :key="chain.chain" class="chain" :data-chain="chain.chain">
        <header>
          <div>
            <strong>{{ chain.name }}</strong>
            <div class="addr">{{ shortAddress(chain.address) }} <CopyButton :value="chain.address" /></div>
            <div class="tag">{{ chain.path }}</div>
          </div>
          <button class="ghost" type="button" @click="receive = { address: chain.address, title: chain.name }">收款</button>
        </header>
        <Fault v-if="!chain.ok" :error="chain.error" />
        <div v-for="asset in chain.assets" :key="asset.symbol" class="asset">
          <div>
            <b>{{ asset.symbol }}</b>
            <div class="muted">{{ asset.name }}{{ asset.note ? ` · ${asset.note}` : "" }}</div>
            <div v-if="asset.error" class="bad">{{ asset.error.message }}</div>
          </div>
          <div class="right">
            <div class="amount">{{ asset.amount }}</div>
            <div class="muted">${{ asset.usd }}</div>
          </div>
        </div>
        <div class="stack" style="margin-top: 8px">
          <span class="muted">转账记录</span>
          <p v-if="chainTransfers(chain.address, chain.chain).length === 0" class="muted">这个地址还没有从本钱包发出的转账。</p>
          <div v-for="row in chainTransfers(chain.address, chain.chain)" :key="row.id" class="entry">
            <div>
              <strong>{{ sameAddress(row.from, chain.address) ? "转出" : "转入" }} {{ row.amount }} {{ row.asset }}</strong>
              <div class="muted">{{ formatWhen(row.createdAt) }}</div>
              <div class="muted">{{ shortAddress(row.from) }} → {{ shortAddress(row.to) }}</div>
              <div v-if="row.txHash" class="addr">{{ shortAddress(row.txHash) }} <CopyButton :value="row.txHash" /></div>
            </div>
            <span class="tag">{{ STATUS[row.status] }}</span>
          </div>
        </div>
        <div v-if="chain.chain === 'ethereum'" class="stack" style="margin-top: 8px">
          <span class="muted">USDC 记录</span>
          <p v-if="usdc.length === 0" class="muted">这个地址在本地结算链上还没有 USDC 流水。</p>
          <div v-for="row in usdc" :key="row.hash + row.kind" class="entry">
            <div>
              <strong>{{ USDC_KIND[row.kind] }} {{ row.amount }} USDC</strong>
              <div class="muted">{{ formatWhen(row.createdAt) }}</div>
              <div class="muted">{{ shortAddress(row.from) }} → {{ shortAddress(row.to) }}</div>
              <div class="addr">{{ shortAddress(row.hash) }} <CopyButton :value="row.hash" /></div>
            </div>
            <span class="tag">已确认</span>
          </div>
        </div>
      </article>
    </div>
    <div v-if="receive" class="panel" style="position: fixed; inset: 0; background: rgba(0,0,0,.55)" @click="receive = null">
      <div class="card stack" @click.stop>
        <h2>接收 {{ receive.title }}</h2>
        <img v-if="qr" :src="qr" alt="" width="220" height="220" />
        <div class="addr">{{ receive.address }}</div>
        <div class="row">
          <CopyButton :value="receive.address" />
          <button class="ghost" type="button" @click="receive = null">关闭</button>
        </div>
      </div>
    </div>
  </section>
</template>
