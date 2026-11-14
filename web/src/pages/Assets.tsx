// 资产总览。刷新是手动的，避免对着公共节点轮询。
import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { api } from "../api";
import { CopyButton, Fault, money, shortAddress } from "../components/ui";
import { useSession } from "../session";
import type { Portfolio } from "../types";

// 展示五条链的余额、估值和收款地址。
export function Assets() {
  const session = useSession();
  const [data, setData] = useState<Portfolio | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [receive, setReceive] = useState<{ address: string; title: string } | null>(null);

  // 向后端拉取这个账户的资产组合。
  async function load() {
    setError(null);
    try {
      setData(await api<Portfolio>(`/api/v1/accounts/${session.accountId}/assets`));
    } catch (err) {
      setError(err);
    }
  }

  useEffect(() => { void load(); }, [session.accountId]);

  return (
    <section>
      <div className="page-head">
        <div>
          <p className="eyebrow">资产</p>
          <h2>五条链，一次看完</h2>
        </div>
        <div className="total">
          <span className="muted">估算 {data?.priceSource === "static" ? "· 静态价格" : ""}</span>
          <b>${money(data?.totalUsd)}</b>
        </div>
      </div>
      <div className="row" style={{ marginBottom: 16 }}>
        <button className="ghost" type="button" onClick={() => void load()}>刷新</button>
        <span className="muted">刷新是手动的，避免对着公共节点轮询。</span>
      </div>
      <Fault error={error} />
      <div className="grid">
        {data?.chains.map((chain) => (
          <article className="chain" key={chain.chain} data-chain={chain.chain}>
            <header>
              <div>
                <strong>{chain.name}</strong>
                <div className="addr">{shortAddress(chain.address)} <CopyButton value={chain.address} /></div>
                <div className="tag">{chain.path}</div>
              </div>
              <button className="ghost" type="button" onClick={() => setReceive({ address: chain.address, title: chain.name })}>收款</button>
            </header>
            {!chain.ok && <Fault error={chain.error} />}
            {chain.assets.map((asset) => (
              <div className="asset" key={asset.symbol}>
                <div>
                  <b>{asset.symbol}</b>
                  <div className="muted">{asset.name}{asset.note ? ` · ${asset.note}` : ""}</div>
                  {asset.error && <div className="bad">{asset.error.message}</div>}
                </div>
                <div className="right">
                  <div className="amount">{asset.amount}</div>
                  <div className="muted">${asset.usd}</div>
                </div>
              </div>
            ))}
          </article>
        ))}
      </div>
      {receive && <Receive title={receive.title} address={receive.address} onClose={() => setReceive(null)} />}
    </section>
  );
}

// 弹出收款地址和二维码。
function Receive({ title, address, onClose }: { title: string; address: string; onClose: () => void }) {
  const [src, setSrc] = useState("");
  useEffect(() => {
    void QRCode.toDataURL(address, { margin: 1, width: 220, color: { dark: "#172000", light: "#f6f3e8" } }).then(setSrc);
  }, [address]);
  return (
    <div className="panel" style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,.55)" }} onClick={onClose}>
      <div className="card stack" onClick={(event) => event.stopPropagation()}>
        <h2>接收 {title}</h2>
        {src && <img src={src} alt="" width={220} height={220} />}
        <div className="addr">{address}</div>
        <div className="row">
          <CopyButton value={address} />
          <button className="ghost" type="button" onClick={onClose}>关闭</button>
        </div>
      </div>
    </div>
  );
}
