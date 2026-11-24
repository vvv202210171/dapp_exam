<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import Fault from "../components/Fault.vue";
import Field from "../components/Field.vue";
import { saveAndEnter } from "../session";
import { createMnemonic, mnemonicValid, normalizeMnemonic } from "../wallet/derive";

const route = useRoute();
const router = useRouter();
const importing = route.query.mode === "import";
const mnemonic = ref(importing ? "" : createMnemonic());
const stage = ref<"show" | "check" | "import" | "password">(importing ? "import" : "show");
const picks = sampleIndexes();
const answers = ref<Record<number, string>>({});
const label = ref("主账户");
const password = ref("");
const confirm = ref("");
const saved = ref(false);
const error = ref<unknown>(null);
const busy = ref(false);
const words = computed(() => normalizeMnemonic(mnemonic.value).split(" ").filter(Boolean));

function verify() {
  const ok = picks.every((index) => answers.value[index]?.trim().toLowerCase() === words.value[index]);
  if (!ok) {
    error.value = new Error("核对不一致。回到上一页，按顺序把 12 个词抄到纸上再试。");
    return;
  }
  error.value = null;
  stage.value = "password";
}

function continueImport() {
  if (!mnemonicValid(mnemonic.value)) {
    error.value = new Error("助记词无效。");
    return;
  }
  error.value = null;
  stage.value = "password";
}

async function finish() {
  busy.value = true;
  error.value = null;
  try {
    if (!mnemonicValid(mnemonic.value)) throw new Error("助记词无效，检查单词是否在 BIP39 英文词表里。");
    if (password.value.length < 8) throw new Error("密码至少 8 位。");
    if (password.value !== confirm.value) throw new Error("两次密码不一致。");
    await saveAndEnter(normalizeMnemonic(mnemonic.value), password.value, label.value.trim() || "主账户");
    await router.push("/app");
  } catch (err) {
    error.value = err;
  } finally {
    busy.value = false;
  }
}

function sampleIndexes(): number[] {
  const bag = Array.from({ length: 12 }, (_, index) => index);
  for (let i = bag.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [bag[i], bag[j]] = [bag[j], bag[i]];
  }
  return bag.slice(0, 3).sort((a, b) => a - b);
}
</script>

<template>
  <section class="setup">
    <div class="hero">
      <div>
        <p class="eyebrow">备份</p>
        <h1>{{ stage === "import" ? "导入已有助记词。" : "先把词写下来。" }}</h1>
        <p class="lede">助记词是这个钱包的根。它经过 PBKDF2 和 AES-GCM 后存在 localStorage，明文只在解锁后的内存里。</p>
      </div>
      <router-link to="/" class="text-btn">返回</router-link>
    </div>
    <div class="panel">
      <div class="card stack">
        <template v-if="stage === 'show'">
          <h2>12 个单词</h2>
          <div class="words">
            <div v-for="(word, index) in words" :key="index"><span>{{ index + 1 }}</span>{{ word }}</div>
          </div>
          <label class="row"><input v-model="saved" type="checkbox" />我已经离线抄下来了</label>
          <button class="primary" type="button" :disabled="!saved" @click="stage = 'check'">继续核对</button>
        </template>
        <template v-else-if="stage === 'check'">
          <h2>核对 3 个词</h2>
          <Field v-for="index in picks" :key="index" :label="`第 ${index + 1} 个词`">
            <input v-model="answers[index]" />
          </Field>
          <Fault :error="error" />
          <div class="row">
            <button class="ghost" type="button" @click="stage = 'show'">返回单词</button>
            <button class="primary" type="button" @click="verify">核对</button>
          </div>
        </template>
        <template v-else-if="stage === 'import'">
          <h2>粘贴助记词</h2>
          <Field label="12 或 24 个英文单词">
            <textarea v-model="mnemonic" />
          </Field>
          <button class="primary" type="button" @click="continueImport">继续</button>
          <Fault :error="error" />
        </template>
        <template v-else>
          <h2>设置密码</h2>
          <Field label="账户名称"><input v-model="label" /></Field>
          <Field label="密码"><input v-model="password" type="password" /></Field>
          <Field label="再输入一次"><input v-model="confirm" type="password" /></Field>
          <p class="muted">这里用了 21 万次 PBKDF2。生产环境应按 OWASP 提到 60 万次，或改用 Argon2id。浏览器里没有 Argon2，所以选了 PBKDF2。</p>
          <Fault :error="error" />
          <button class="primary" type="button" :disabled="busy" @click="finish">{{ busy ? "正在同步地址…" : "创建并进入" }}</button>
        </template>
      </div>
    </div>
  </section>
</template>
