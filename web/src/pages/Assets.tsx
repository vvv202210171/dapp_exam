// 资产总览。刷新是手动的，避免对着公共节点轮询。
import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { api } from "../api";
import { CopyButton, Fault, formatWhen, money, shortAddress } from "../components/ui";
import { useSession } from "../session";
import type { Portfolio, SettlementView, Transfer } from "../types";
import { localUsdcHistory, type UsdcMove } from "../usdcHistory";

// 展示五条链的余额、估值和收款地址。
export function Assets() {
  const session = useSession();
  const [data, setData] = useState<Portfolio | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [receive, setReceive] = useState<{ address: string; title: string } | null>(null);
  const [transfers, setTransfers] = useState<Transfer[]>([]);
  const [usdc, setUsdc] = useState<UsdcMove[]>([]);

  // 向后端拉取这个账户的资产组合。
  async function load() {
    setError(null);
    try {
      setData(await api<Portfolio>(`/api/v1/accounts/${session.accountId}/assets`));
      const history = await api<{ transfers: Transfer[] }>(`/api/v1/accounts/${session.accountId}/transfers`);
      setTransfers(history.transfers);
      try {
        const view = await api<SettlementView>(`/api/v1/accounts/${session.accountId}/settlement`);
        if (view.online && view.rpc && view.usdc && view.settlement && view.user) {
          setUsdc(await localUsdcHistory(view.rpc, view.usdc, view.settlement, view.user));
        } else {
          setUsdc([]);
        }
      } catch {
        setUsdc([]);
      }
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
            <AddressTransfers address={chain.address} rows={transfers.filter((row) => row.chain === chain.chain && (sameAddress(row.from, chain.address) || sameAddress(row.to, chain.address)))} />
            {chain.chain === "ethereum" && <UsdcTransfers rows={usdc} />}
          </article>
        ))}
      </div>
      {receive && <Receive title={receive.title} address={receive.address} onClose={() => setReceive(null)} />}
    </section>
  );
}

// 展示这个地址在本钱包里记下的转账。链上完整历史不在这里。
function AddressTransfers({ address, rows }: { address: string; rows: Transfer[] }) {
  return (
    <div className="stack" style={{ marginTop: 8 }}>
      <span className="muted">转账记录</span>
      {rows.length === 0 && <p className="muted">这个地址还没有从本钱包发出的转账。</p>}
      {rows.map((row) => {
        const outgoing = sameAddress(row.from, address);
        return (
          <div className="entry" key={row.id}>
            <div>
              <strong>{outgoing ? "转出" : "转入"} {row.amount} {row.asset}</strong>
              <div className="muted">{formatWhen(row.createdAt)}</div>
              <div className="muted">{shortAddress(row.from)} → {shortAddress(row.to)}</div>
              {row.txHash && <div className="addr">{shortAddress(row.txHash)} <CopyButton value={row.txHash} /></div>}
            </div>
            <span className="tag">{row.status === "confirmed" ? "已确认" : row.status === "failed" ? "失败" : row.status === "broadcast" ? "已广播" : "已签名"}</span>
          </div>
        );
      })}
    </div>
  );
}

// 地址比较忽略大小写。EVM 地址校验和不同，仍是同一个账户。
function sameAddress(left: string, right: string): boolean {
  return left.trim().toLowerCase() === right.trim().toLowerCase();
}

const USDC_KIND: Record<UsdcMove["kind"], string> = {
  mint: "领取",
  deposit: "划入合约",
  withdraw: "提现",
  in: "转入",
  out: "转出",
};

// 展示本地结算链上这个地址的测试 USDC 流水。
function UsdcTransfers({ rows }: { rows: UsdcMove[] }) {
  return (
    <div className="stack" style={{ marginTop: 8 }}>
      <span className="muted">USDC 记录</span>
      {rows.length === 0 && <p className="muted">这个地址在本地结算链上还没有 USDC 流水。</p>}
      {rows.map((row) => (
        <div className="entry" key={row.hash + row.kind}>
          <div>
            <strong>{USDC_KIND[row.kind]} {row.amount} USDC</strong>
            <div className="muted">{formatWhen(row.createdAt)}</div>
            <div className="muted">{shortAddress(row.from)} → {shortAddress(row.to)}</div>
            <div className="addr">{shortAddress(row.hash)} <CopyButton value={row.hash} /></div>
          </div>
          <span className="tag">已确认</span>
        </div>
      ))}
    </div>
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
