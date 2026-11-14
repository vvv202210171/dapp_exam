// 开卡。卡号明文只放进这次跳转的 location state，刷新后要再点“查看卡号”才会解密。
import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api } from "../api";
import { Fault, Field } from "../components/ui";
import { useSession } from "../session";
import type { Card } from "../types";

const STATUS: Record<string, string> = {
  active: "可使用",
  frozen: "已冻结",
  requested: "待制卡",
  issued: "已制卡",
  shipped: "已寄出",
  closed: "已注销",
};

// 开卡和卡列表。
export function Cards() {
  const session = useSession();
  const navigate = useNavigate();
  const [cards, setCards] = useState<Card[]>([]);
  const [holder, setHolder] = useState(session.label);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  // 拉取这个账户的卡。
  async function load() {
    const data = await api<{ cards: Card[] }>(`/api/v1/accounts/${session.accountId}/cards`);
    setCards(data.cards);
  }

  useEffect(() => { void load().catch(setError); }, [session.accountId]);

  // 开虚拟卡或申请实体卡，并带着明文卡号进入详情。
  async function create(kind: "virtual" | "physical") {
    setBusy(true);
    setError(null);
    try {
      const issued = await api<{ card: Card; pan: string; cvv: string }>(`/api/v1/accounts/${session.accountId}/cards`, {
        method: "POST",
        body: JSON.stringify({ kind, holder }),
      });
      navigate(`/app/cards/${issued.card.id}`, { state: { pan: issued.pan, cvv: issued.cvv } });
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
          <p className="eyebrow">支付卡</p>
          <h2>虚拟卡现在能用，实体卡要走状态机</h2>
        </div>
      </div>
      <div className="sheet stack" style={{ marginBottom: 18 }}>
        <Field label="持卡人"><input value={holder} onChange={(event) => setHolder(event.target.value)} /></Field>
        <div className="row">
          <button className="primary" type="button" disabled={busy} onClick={() => void create("virtual")}>开虚拟卡</button>
          <button className="ghost" type="button" disabled={busy} onClick={() => void create("physical")}>申请实体卡</button>
        </div>
        <p className="muted">卡号以 990016 开头，只在这个沙箱里有效。完整卡号用 AES-GCM 存在本地数据库，密钥不进前端。</p>
        <Fault error={error} />
      </div>
      <div className="cards">
        {cards.map((card) => (
          <Link key={card.id} to={`/app/cards/${card.id}`} className={`plastic ${card.kind}`}>
            <div className="row" style={{ justifyContent: "space-between" }}>
              <span>{card.kind === "virtual" ? "虚拟卡" : "实体卡"}</span>
              <span>{STATUS[card.status] ?? card.status}</span>
            </div>
            <div className="chip" />
            <div>
              <div className="last4">•••• {card.last4}</div>
              <div className="row" style={{ justifyContent: "space-between" }}>
                <span>{card.holder}</span>
                <b>${card.available}</b>
              </div>
            </div>
          </Link>
        ))}
      </div>
    </section>
  );
}
