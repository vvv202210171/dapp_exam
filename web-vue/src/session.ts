// 会话只存在内存。刷新页面要重新输密码解密保险库。同步给服务器的只有地址。
import { reactive } from "vue";
import { api } from "./api";
import type { Account, Chain, Meta } from "./types";
import { deriveAddress, deriveAll, type Derived } from "./wallet/derive";
import { openVault, sealVault } from "./wallet/vault";

export type Session = {
  label: string;
  mnemonic: string;
  accountId: string;
  meta: Meta;
  derived: Record<Chain, Derived>;
};

export const wallet = reactive({
  session: null as Session | null,
});

export function lock() {
  wallet.session = null;
}

export async function unlock(password: string) {
  const opened = await openVault(password);
  await enter(opened.mnemonic, opened.label);
}

export async function saveAndEnter(mnemonic: string, password: string, label: string) {
  await enter(mnemonic, label, password);
}

// index 1 是演示用的“下一个地址”。SOL 的 index 是账户序号，不是外部链上的地址序号。
export function nextAddress(chain: Chain) {
  const session = wallet.session;
  if (!session) throw new Error("钱包还锁着");
  return deriveAddress(session.mnemonic, chain, session.meta.network, 1).address;
}

// 拉取网络配置，派生五条链地址，同步账户，必要时把助记词封进保险库。
async function enter(mnemonic: string, label: string, password?: string) {
  const meta = await api<Meta>("/api/v1/meta");
  const derived = deriveAll(mnemonic, meta.network);
  const account = await api<Account>("/api/v1/accounts/sync", {
    method: "POST",
    body: JSON.stringify({
      label,
      addresses: Object.values(derived).map((item) => ({
        chain: item.chain,
        address: item.address,
        path: item.path,
      })),
    }),
  });
  if (password) await sealVault(password, mnemonic, label);
  wallet.session = { label: account.label, mnemonic, accountId: account.id, meta, derived };
}
