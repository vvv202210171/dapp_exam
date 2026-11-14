// 会话只存在内存。刷新页面要重新输密码解密保险库。同步给服务器的只有地址。
import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import { Navigate } from "react-router-dom";
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

type WalletApi = {
  session: Session | null;
  lock: () => void;
  unlock: (password: string) => Promise<void>;
  saveAndEnter: (mnemonic: string, password: string, label: string) => Promise<void>;
};

const WalletContext = createContext<WalletApi | null>(null);

// 拉取网络配置，派生五条链地址，同步账户，必要时把助记词封进保险库。
async function enter(mnemonic: string, label: string, password: string | undefined, setSession: (session: Session) => void) {
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
  setSession({ label: account.label, mnemonic, accountId: account.id, meta, derived });
}

// 在内存里保存解锁后的会话，锁定就是把它清掉。
export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const value = useMemo<WalletApi>(
    () => ({
      session,
      lock: () => setSession(null),
      unlock: async (password: string) => {
        const opened = await openVault(password);
        await enter(opened.mnemonic, opened.label, undefined, setSession);
      },
      saveAndEnter: (mnemonic, password, label) => enter(mnemonic, label, password, setSession),
    }),
    [session],
  );
  return <WalletContext.Provider value={value}>{children}</WalletContext.Provider>;
}

// 读取会话上下文。
export function useWallet() {
  const value = useContext(WalletContext);
  if (!value) throw new Error("SessionProvider 缺失");
  return value;
}

// 读取已解锁的会话，并提供派生下一个地址的方法。
export function useSession() {
  const { session, lock } = useWallet();
  if (!session) throw new Error("钱包还锁着");
  return {
    ...session,
    lock,
    nextAddress(chain: Chain) {
      // index 1 是演示用的“下一个地址”。SOL 的 index 是账户序号，不是外部链上的地址序号。
      return deriveAddress(session.mnemonic, chain, session.meta.network, 1).address;
    },
  };
}

// 还没解锁时把页面送回首页。
export function RequireSession({ children }: { children: ReactNode }) {
  const { session } = useWallet();
  if (!session) return <Navigate to="/" replace />;
  return children;
}
