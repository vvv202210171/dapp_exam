<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import Fault from "../components/Fault.vue";
import { unlock } from "../session";
import { clearVault, hasVault } from "../wallet/vault";

const router = useRouter();
const password = ref("");
const error = ref<unknown>(null);
const busy = ref(false);
const exists = ref(hasVault());

async function enter() {
  busy.value = true;
  error.value = null;
  try {
    await unlock(password.value);
    await router.push("/app");
  } catch (err) {
    error.value = err;
  } finally {
    busy.value = false;
  }
}

function wipe() {
  if (window.confirm("本机保险库会被删除。没有另存的助记词就找不回这个钱包。")) {
    clearVault();
    exists.value = false;
  }
}
</script>

<template>
  <section class="welcome">
    <div class="hero">
      <div>
        <p class="eyebrow">Aegis · learning wallet</p>
        <h1>钥匙留在这台浏览器里。</h1>
        <p class="lede">一套助记词派生 Bitcoin、Ethereum、BNB、Solana 和 TRON。转账在本地签名，卡账本单独记账，链上结算用 Solidity 合约。</p>
      </div>
      <div class="points">
        <div><strong>账户与签名</strong><span>助记词加密后只存在 localStorage。服务器只收地址。</span></div>
        <div><strong>五条链</strong><span>余额、报价、广播和状态都走同一套适配器。</span></div>
        <div><strong>异常排查</strong><span>超时、nonce、余额、限流会带上下一步该看什么。</span></div>
        <div><strong>支付卡</strong><span>虚拟卡、实体卡、充值、消费、提现和月账单。</span></div>
      </div>
    </div>
    <div class="panel">
      <div class="card stack">
        <template v-if="exists">
          <h2>解锁</h2>
          <p class="muted">密码只用来解开本机保险库，不会发给服务器。</p>
          <form class="stack" @submit.prevent="enter">
            <label class="field">密码
              <input v-model="password" type="password" autofocus />
            </label>
            <Fault :error="error" />
            <button class="primary" :disabled="busy || password.length < 8">{{ busy ? "正在派生密钥…" : "进入钱包" }}</button>
          </form>
          <button class="text-btn" type="button" @click="wipe">清除本机保险库</button>
        </template>
        <template v-else>
          <h2>开始</h2>
          <p class="muted">先创建一套新的助记词，或者导入你已经记下的 12/24 个单词。这是学习项目，不要使用存有真实资金的助记词。</p>
          <router-link class="primary" to="/setup" style="text-align: center; text-decoration: none">创建钱包</router-link>
          <router-link class="ghost" to="/setup?mode=import" style="text-align: center; text-decoration: none">导入助记词</router-link>
        </template>
      </div>
    </div>
  </section>
</template>
