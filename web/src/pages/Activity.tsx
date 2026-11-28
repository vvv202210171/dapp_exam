// 只记录这只钱包发出的交易。链上全量历史需要索引器，这里是本地发起记录再向节点核对状态。
import { useEffect, useState } from "react";
import { api } from "../api";
import { CopyButton, Fault, formatWhen, shortAddress } from "../components/ui";
import { useSession } from "../session";
import { CHAINS, CHAIN_LABEL, type Chain, type Transfer } from "../types";

const STATUS: Record<Transfer["status"], string> = {
  signed: "已签名",
  broadcast: "已广播",
  confirmed: "已确认",
  failed: "失败",
};

// 展示这只钱包发出的交易。
export function Activity() {
  const session = useSession();
  const [rows, setRows] = useState<Transfer[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [tab, setTab] = useState<Chain>("bitcoin");

  // 拉取转账记录。
  async function load() {
    try {
      const data = await api<{ transfers: Transfer[] }>(`/api/v1/accounts/${session.accountId}/transfers`);
      setRows(data.transfers);
      setError(null);
    } catch (err) {
      setError(err);
    }
  }

  useEffect(() => { void load(); }, [session.accountId]);

  const visible = rows.filter((row) => row.chain === tab);

  // 广播一笔已签名交易，或向节点刷新状态。
  async function act(id: string, kind: "broadcast" | "refresh") {
    setError(null);
    try {
      await api(`/api/v1/transfers/${id}/${kind}`, { method: "POST" });
      await load();
    } catch (err) {
      setError(err);
      await load();
    }
  }

  return (
    <section>
      <div className="page-head">
        <div>
          <p className="eyebrow">记录</p>
          <h2>只记录这只钱包发出的交易</h2>
        </div>
        <button className="ghost" type="button" onClick={() => void load()}>刷新</button>
      </div>
      <p className="muted">节点不能廉价地给出某个地址的完整历史。这里的账本是本地发起记录，状态再向节点核对。全量历史需要索引器。</p>
      <Fault error={error} />
      <div className="browser" style={{ marginTop: 16 }}>
        <div className="tabs" role="tablist">
          {CHAINS.map((id) => (
            <button key={id} type="button" role="tab" aria-selected={tab === id} data-chain={id} onClick={() => setTab(id)}>
              {CHAIN_LABEL[id]}
            </button>
          ))}
        </div>
        <div className="sheet tab-body" data-chain={tab}>
        {visible.length === 0 && <p className="muted">这条链还没有从本钱包发出的交易。</p>}
        {visible.map((row) => (
          <article className="transfer" key={row.id}>
            <div>
              <strong>{row.amount} {row.asset}</strong>
              <div className="muted">{formatWhen(row.createdAt)}</div>
              <div className="muted">{shortAddress(row.from)} → {shortAddress(row.to)}</div>
              <div className="tag">{STATUS[row.status]}{row.fee ? ` · 费 ${row.fee}` : ""}</div>
              {row.detail && <div className="muted">{row.detail}</div>}
              {row.txHash && <div className="addr">{shortAddress(row.txHash)} <CopyButton value={row.txHash} /></div>}
            </div>
            <div className="row">
              {row.status === "signed" && <button className="primary" type="button" onClick={() => void act(row.id, "broadcast")}>广播</button>}
              {row.txHash && <button className="ghost" type="button" onClick={() => void act(row.id, "refresh")}>查状态</button>}
            </div>
          </article>
        ))}
        </div>
      </div>
    </section>
  );
}
