// 用户的授权、存入、提现弹出小狐狸确认。领取测试币和结算由服务端的运营方账户发送。
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import { Fault, formatWhen } from "../components/ui";
import { useSession } from "../session";
import type { SettlementQuote, SettlementReceipt, SettlementView } from "../types";
import { connectMetamask, sendWithMetamask, waitForReceipt, watchUsdc } from "../wallet/metamask";

// 链上结算页：领测试币、存入、按卡消费结算、提走剩余。
export function Settlement() {
  const session = useSession();
  const [view, setView] = useState<SettlementView | null>(null);
  const [amount, setAmount] = useState("25");
  const [error, setError] = useState<unknown>(null);
  const [log, setLog] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [fox, setFox] = useState("");
  const expected = (view?.user || session.derived.ethereum.address).toLowerCase();
  const foxReady = fox !== "" && fox.toLowerCase() === expected;

  // 读取合约地址、余额和未结算消费。
  async function load() {
    const next = await api<SettlementView>(`/api/v1/accounts/${session.accountId}/settlement`);
    setView(next);
  }

  useEffect(() => {
    void load().catch(setError);
  }, [session.accountId]);

  // 服务端只报价。小狐狸签名并广播，确认后页面再刷新余额。
  async function signAndSend(action: "approve" | "deposit" | "withdraw", value: string) {
    if (!foxReady) throw new Error("先连接小狐狸，并选中这个钱包的以太坊地址。");
    const quote = await api<SettlementQuote>(`/api/v1/accounts/${session.accountId}/settlement/prepare`, {
      method: "POST",
      body: JSON.stringify({ action, amount: value }),
    });
    const payload = quote.payload as {
      to: string; value: string; data: string; gasLimit: string;
      maxFeePerGas: string; maxPriorityFeePerGas: string; nonce: string;
    };
    const hash = await sendWithMetamask(fox, payload);
    await waitForReceipt(hash);
    setLog((prev) => [`${action} confirmed ${hash}`, ...prev]);
  }

  // 运营方发交易，浏览器不签名。
  async function operator(path: string, body?: Record<string, string>) {
    const receipt = await api<SettlementReceipt>(`/api/v1/accounts/${session.accountId}/settlement/${path}`, {
      method: "POST",
      body: JSON.stringify(body ?? {}),
    });
    return receipt;
  }

  // 跑完一步后刷新余额。失败留在页面上。
  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await work();
      await load();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <div className="page-head">
        <div>
          <p className="eyebrow">Solidity</p>
          <h2>卡的链上那一半</h2>
        </div>
      </div>
      <p className="muted">{session.meta.settlement.note}</p>
      <div className="steps">
        <article><span>01</span><h3>小狐狸签名</h3><p className="muted">approve、deposit、withdraw 会弹出小狐狸。账户必须是下面这个以太坊地址。</p></article>
        <article><span>02</span><h3>卡账本</h3><p className="muted">卡片里的充值和消费仍按“分”记账。消费先落成流水，不直接动链。</p></article>
        <article><span>03</span><h3>运营方结算</h3><p className="muted">服务端用部署账户调用 settle。账单号是流水 id 的 SHA-256，同一笔不能结两次。</p></article>
      </div>
      <Fault error={error} />
      {view && !view.online && <div className="banner">{view.hint}</div>}
      <div className="two">
        <div className="sheet stack">
          <h2>你的签名</h2>
          <div className="kv">
            <span>链</span><b className="mono">{view?.online ? `Hardhat ${view.chainId}` : "未连接"}</b>
            <span>ETH</span><b className="mono">{view?.eth ?? "—"}</b>
            <span>钱包 USDC</span><b className="mono">{view?.walletUsdc ?? "—"}</b>
            <span>已授权</span><b className="mono">{view?.allowance ?? "—"}</b>
            <span>合约里可用</span><b className="mono">{view?.available ?? "—"}</b>
          </div>
          <p className="muted">用装了小狐狸的浏览器打开本页。连接后，授权、存入、提现都会弹出确认。领取测试币和结算不弹窗，那是运营方账户发的。</p>
          <div className="row">
            <button className="primary" type="button" disabled={busy} onClick={() => void run(async () => setFox(await connectMetamask()))}>
              {fox ? "重新连接小狐狸" : "连接小狐狸"}
            </button>
            <button className="ghost" type="button" disabled={busy || !foxReady || !view?.usdc} onClick={() => void run(() => watchUsdc(view?.usdc ?? ""))}>在小狐狸里显示 USDC</button>
          </div>
          {fox && !foxReady && <p className="bad">小狐狸当前是 {fox}。请换成 {expected}，否则存进去的钱对不上这张卡。</p>}
          {foxReady && <p className="ok">小狐狸已接上 {fox}</p>}
          <button className="ghost" type="button" disabled={busy || !view?.online} onClick={() => void run(async () => {
            const sent = await api<{ ethTx: string; usdcTx: string }>(`/api/v1/accounts/${session.accountId}/settlement/faucet`, {
              method: "POST",
              body: "{}",
            });
            setLog((prev) => [`faucet USDC ${sent.usdcTx}`, `faucet ETH ${sent.ethTx}`, ...prev]);
          })}>领取测试 ETH 和 USDC</button>
          <label className="field">金额（USDC，6 位小数）
            <input value={amount} onChange={(event) => setAmount(event.target.value)} />
          </label>
          <div className="row">
            <button className="primary" type="button" disabled={busy || !view?.online || !foxReady} onClick={() => void run(async () => {
              await signAndSend("approve", amount);
              await signAndSend("deposit", amount);
            })}>授权并存入</button>
            <button className="ghost" type="button" disabled={busy || !view?.online} onClick={() => void run(async () => {
              if (!foxReady) throw new Error("先连接小狐狸，并选中这个钱包的以太坊地址。");
              await signAndSend("withdraw", amount);
            })}>提现</button>
          </div>
          {log.length > 0 && <pre className="payload">{log.join("\n")}</pre>}
        </div>
        <div className="sheet stack">
          <h2>待结算的消费</h2>
          <p className="muted">到<Link to="/app/cards">卡片</Link>点「划入 USDC 并入账」，确认后卡余额才会增加。这里的「授权并存入」只把币放进合约，不加卡余额。消费仍只扣卡账本。</p>
          <div className="kv">
            <span>合约</span><b className="mono">{view?.settlement ?? "—"}</b>
            <span>USDC</span><b className="mono">{view?.usdc ?? "—"}</b>
            <span>运营方</span><b className="mono">{view?.operator ?? "—"}</b>
            <span>你的地址</span><b className="mono">{view?.user ?? session.derived.ethereum.address}</b>
          </div>
          {view && view.spends.length === 0 && <p className="muted">还没有消费流水。</p>}
          {view?.spends.map((spend) => (
            <div className="entry" key={spend.entryId}>
              <div>
                <strong>{spend.merchant || "消费"}</strong>
                <div className="muted">{formatWhen(spend.createdAt)}</div>
                <div className="muted">尾号 {spend.last4} · ${spend.amount} · {spend.units} USDC</div>
                {spend.txHash && <div className="mono">{spend.txHash}</div>}
              </div>
              {spend.settled ? <span className="ok">已结算</span> : (
                <button className="primary" type="button" disabled={busy || !view.online} onClick={() => void run(async () => {
                  const receipt = await operator("settle", { entryId: spend.entryId });
                  setLog((prev) => [`settle ${receipt.status} ${receipt.hash}`, ...prev]);
                })}>结算</button>
              )}
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
