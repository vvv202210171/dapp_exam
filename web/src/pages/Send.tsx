// 先向后端报价，再用本地私钥签名。改表单会清掉旧报价，避免拿过期 nonce 或 blockhash 去签。
import { useMemo, useState } from "react";
import { api } from "../api";
import { CopyButton, Fault, Field } from "../components/ui";
import { useSession } from "../session";
import type { Chain, PrepareResult, Transfer } from "../types";
import { signPayload } from "../wallet/sign";

// 转账页：先报价，再本地签名，可选立刻广播。
export function Send() {
  const session = useSession();
  const [chain, setChain] = useState<Chain>("ethereum");
  const info = session.meta.chains.find((item) => item.id === chain);
  const assets = info?.assets ?? [];
  const [asset, setAsset] = useState(assets[0]?.symbol ?? "ETH");
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const [quote, setQuote] = useState<PrepareResult | null>(null);
  const [result, setResult] = useState<Transfer | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const from = session.derived[chain].address;
  const selected = useMemo(() => assets.find((item) => item.symbol === asset) ?? assets[0], [assets, asset]);

  // 表单变化后清掉旧报价，避免签到过期参数。
  function resetQuote() {
    setQuote(null);
    setResult(null);
  }

  // 向后端要一份未签名报价。
  async function prepare() {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setQuote(await api<PrepareResult>("/api/v1/transfers/prepare", {
        method: "POST",
        body: JSON.stringify({ accountId: session.accountId, chain, asset: selected?.symbol, from, to, amount }),
      }));
    } catch (err) {
      setQuote(null);
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  // 用本链私钥签名，再把结果交给后端保存或广播。
  async function sign(broadcast: boolean) {
    if (!quote || !selected) return;
    setBusy(true);
    setError(null);
    try {
      const signedPayload = await signPayload(chain, session.derived[chain].privateKey, quote.payload);
      setResult(await api<Transfer>("/api/v1/transfers", {
        method: "POST",
        body: JSON.stringify({
          accountId: session.accountId,
          chain,
          asset: quote.asset,
          from,
          to,
          amount,
          fee: `${quote.fee.amount} ${quote.fee.symbol}`,
          signedPayload,
          broadcast,
        }),
      }));
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
          <p className="eyebrow">转账</p>
          <h2>先报价，再离线签名</h2>
        </div>
      </div>
      <div className="two">
        <form className="sheet stack" onSubmit={(event) => { event.preventDefault(); void prepare(); }}>
          <Field label="链">
            <select value={chain} onChange={(event) => {
              const next = event.target.value as Chain;
              setChain(next);
              const native = session.meta.chains.find((item) => item.id === next)?.assets[0]?.symbol ?? "";
              setAsset(native);
              resetQuote();
            }}>
              {session.meta.chains.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
          </Field>
          <Field label="资产">
            <select value={selected?.symbol ?? ""} onChange={(event) => { setAsset(event.target.value); resetQuote(); }}>
              {assets.map((item) => <option key={item.symbol} value={item.symbol}>{item.symbol} · {item.name}</option>)}
            </select>
          </Field>
          <Field label="从">
            <input className="mono" readOnly value={from} />
          </Field>
          <Field label="到">
            <input className="mono" value={to} onChange={(event) => { setTo(event.target.value); resetQuote(); }} placeholder="收款地址" />
          </Field>
          <button className="text-btn" type="button" onClick={() => { setTo(session.nextAddress(chain)); resetQuote(); }}>填入本钱包的下一个地址</button>
          <Field label="金额">
            <input value={amount} onChange={(event) => { setAmount(event.target.value); resetQuote(); }} placeholder={selected ? `最多 ${selected.decimals} 位小数` : ""} />
          </Field>
          <Fault error={error} />
          <button className="primary" disabled={busy || !to || !amount}>获取报价</button>
        </form>
        <aside className="sheet stack">
          {!quote && <p className="muted">报价会返回 gas、UTXO 或 blockhash。签名发生在这个页面，私钥不会出现在请求里。</p>}
          {quote && (
            <>
              <h2>报价</h2>
              <p>手续费 <b className="mono">{quote.fee.amount} {quote.fee.symbol}</b>{quote.fee.usd ? ` · $${quote.fee.usd}` : ""}</p>
              {quote.warnings.map((item) => <p className="warn" key={item}>{item}</p>)}
              <div className="row">
                <button className="ghost" type="button" disabled={busy} onClick={() => void sign(false)}>只签名</button>
                <button className="primary" type="button" disabled={busy} onClick={() => void sign(true)}>签名并广播</button>
              </div>
            </>
          )}
          {result && (
            <div className="stack">
              <strong>{result.status === "signed" ? "已签名，尚未广播" : result.status === "confirmed" ? "沙箱已记为确认" : "已提交"}</strong>
              {result.txHash && <div className="addr">哈希 {result.txHash} <CopyButton value={result.txHash} /></div>}
              <div className="payload">{result.signedPayload}</div>
              <p className="muted">{result.detail}</p>
            </div>
          )}
        </aside>
      </div>
    </section>
  );
}
