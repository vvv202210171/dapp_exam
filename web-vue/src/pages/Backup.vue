<script setup lang="ts">
import { computed, ref } from "vue";
import { bytesToHex } from "@noble/hashes/utils";
import CopyButton from "../components/CopyButton.vue";
import Fault from "../components/Fault.vue";
import { wallet } from "../session";
import { CHAINS, CHAIN_LABEL, type Chain } from "../types";
import { deriveAddress, pathFor } from "../wallet/derive";
import { openVault } from "../wallet/vault";

type KeyLine = { chain: Chain; address: string; privateKey: string };

const session = computed(() => wallet.session);
const password = ref("");
const words = ref<string[] | null>(null);
const keys = ref<KeyLine[] | null>(null);
const error = ref<unknown>(null);

async function reveal() {
  const current = session.value;
  if (!current) return;
  error.value = null;
  try {
    const opened = await openVault(password.value);
    if (opened.mnemonic !== current.mnemonic) throw new Error("密码解开的不是当前这套助记词。");
    words.value = opened.mnemonic.split(" ");
    keys.value = CHAINS.map((chain) => {
      const derived = deriveAddress(opened.mnemonic, chain, current.meta.network, 0);
      return { chain, address: derived.address, privateKey: bytesToHex(derived.privateKey) };
    });
  } catch (err) {
    words.value = null;
    keys.value = null;
    error.value = err;
  }
}
</script>

<template>
  <section v-if="session">
    <div class="page-head">
      <div>
        <p class="eyebrow">备份</p>
        <h2>再看一次助记词</h2>
      </div>
    </div>
    <div class="two">
      <div class="sheet stack">
        <p class="muted">需要再输入密码。这一步会重新从 localStorage 解密，而不是直接把内存里的词画出来。</p>
        <input v-model="password" type="password" />
        <button class="primary" type="button" @click="reveal">显示</button>
        <Fault :error="error" />
        <div v-if="words" class="words">
          <div v-for="(word, index) in words" :key="index"><span>{{ index + 1 }}</span>{{ word }}</div>
        </div>
      </div>
      <div class="sheet">
        <h2>派生路径</h2>
        <div v-for="chain in CHAINS" :key="chain" class="entry">
          <strong>{{ CHAIN_LABEL[chain] }}</strong>
          <span class="mono">{{ pathFor(chain, session.meta.network, 0) }}</span>
        </div>
        <p class="muted">以太坊和 BNB 共用 coin type 60，所以地址和私钥都相同。比特币测试网用 1，主网用 0，地址会变。Solana 走 SLIP-0010 的 ed25519，不能用 secp256k1 那套 BIP32。</p>
        <template v-if="keys">
          <h2>私钥</h2>
          <div v-for="line in keys" :key="line.chain" class="entry">
            <div>
              <strong>{{ CHAIN_LABEL[line.chain] }}</strong>
              <div class="muted">{{ line.address }}</div>
              <div class="mono" style="word-break: break-all">{{ line.privateKey }}</div>
            </div>
            <CopyButton :value="line.privateKey" />
          </div>
        </template>
      </div>
    </div>
  </section>
</template>
