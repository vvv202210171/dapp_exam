// 卡详情。充值和消费共用金额框；幂等键只在成功后轮换，失败重试仍用同一键。
import { useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { api } from "../api";
import { Fault, Field } from "../components/ui";
import type { Bill, Card, LedgerEntry } from "../types";

const STATUS: Record<string, string> = {
  active: "可使用", frozen: "已冻结", requested: "待制卡", issued: "已制卡", shipped: "已寄出", closed: "已注销",
};
const KIND: Record<string, string> = { topup: "充值", spend: "消费", withdraw: "提现", fee: "手续费" };
const NEXT: Record<string, { action: string; label: string }[]> = {
  requested: [{ action: "issue", label: "标记已制卡" }],
  issued: [{ action: "ship", label: "标记已寄出" }],
  shipped: [{ action: "activate", label: "激活" }],
  active: [{ action: "freeze", label: "冻结" }],
  frozen: [{ action: "unfreeze", label: "解冻" }],
};

// 一张卡的余额、状态流转、充值、消费、提现和账单。
export function CardDetail() {
  const { id = "" } = useParams();
  const location = useLocation();
  const issued = location.state as { pan?: string; cvv?: string } | null;
  const [card, setCard] = useState<Card | null>(null);
  const [secret, setSecret] = useState<{ pan: string; cvv: string } | null>(issued?.pan ? { pan: issued.pan, cvv: issued.cvv ?? "" } : null);
  const [bill, setBill] = useState<Bill | null>(null);
  const [month, setMonth] = useState(currentMonth());
  const [amount, setAmount] = useState("25.00");
  const [merchant, setMerchant] = useState("星巴克");
  const [error, setError] = useState<unknown>(null);
  const [key, setKey] = useState(crypto.randomUUID());

  // 读取卡的展示信息。
  async function loadCard() {
    setCard(await api<Card>(`/api/v1/cards/${id}`));
  }
  // 读取所选月份的账单。
  async function loadBill(value = month) {
    setBill(await api<Bill>(`/api/v1/cards/${id}/bills?month=${value}`));
  }
  useEffect(() => {
    void Promise.all([loadCard(), loadBill()]).catch(setError);
  }, [id]);

  // 提交一次动账或状态变更。成功后才更换幂等键。
  async function run(path: string, body: Record<string, string>) {
    setError(null);
    try {
      await api(path, { method: "POST", body: JSON.stringify({ ...body, idempotencyKey: key }) });
      setKey(crypto.randomUUID());
      await loadCard();
      await loadBill();
    } catch (err) {
      setError(err);
    }
  }

  if (!card) return <Fault error={error} />;
  return (
    <section>
      <Link to="/app/cards" className="text-btn">返回卡片</Link>
      <div className="two" style={{ marginTop: 16 }}>
        <div className={`plastic ${card.kind}`}>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <span>{card.kind === "virtual" ? "虚拟卡" : "实体卡"}</span>
            <span>{STATUS[card.status]}</span>
          </div>
          <div className="chip" />
          <div>
            <div className="last4">{secret ? secret.pan.replace(/(.{4})/g, "$1 ").trim() : `•••• •••• •••• ${card.last4}`}</div>
            <div className="row" style={{ justifyContent: "space-between" }}>
              <span>{card.holder}</span>
              <span>{secret ? `CVV ${secret.cvv}` : card.expiry}</span>
            </div>
          </div>
        </div>
        <div className="sheet stack">
          <h2>${card.available}</h2>
          <p className="muted">可用余额 · {card.currency} · {card.expiry}</p>
          {!secret && <button className="ghost" type="button" onClick={() => void api<{ pan: string; cvv: string }>(`/api/v1/cards/${id}/reveal`, { method: "POST" }).then(setSecret).catch(setError)}>查看卡号</button>}
          <div className="row">
            {(NEXT[card.status] ?? []).map((item) => (
              <button key={item.action} className="ghost" type="button" onClick={() => void api(`/api/v1/cards/${id}/transition`, { method: "POST", body: JSON.stringify({ action: item.action }) }).then(loadCard).catch(setError)}>{item.label}</button>
            ))}
            {card.status !== "closed" && <button className="text-btn" type="button" onClick={() => void api(`/api/v1/cards/${id}/transition`, { method: "POST", body: JSON.stringify({ action: "close" }) }).then(loadCard).catch(setError)}>注销</button>}
          </div>
          <Fault error={error} />
        </div>
      </div>
      <div className="two" style={{ marginTop: 18 }}>
        <form className="sheet stack" onSubmit={(event) => { event.preventDefault(); void run(`/api/v1/cards/${id}/topup`, { amount }); }}>
          <h2>充值</h2>
          <Field label="金额 USD"><input value={amount} onChange={(event) => setAmount(event.target.value)} /></Field>
          <button className="primary" type="submit">记一笔充值</button>
          <button className="ghost" type="button" onClick={() => void run(`/api/v1/cards/${id}/withdraw`, { amount })}>提现（另收 1%）</button>
        </form>
        <form className="sheet stack" onSubmit={(event) => { event.preventDefault(); void run(`/api/v1/cards/${id}/spend`, { amount, merchant, mcc: "5814" }); }}>
          <h2>消费</h2>
          <Field label="商户"><input value={merchant} onChange={(event) => setMerchant(event.target.value)} /></Field>
          <p className="muted">实体卡在激活前可以充值，不能消费。冻结和注销也不能扣款。</p>
          <button className="primary" type="submit">记一笔消费</button>
        </form>
      </div>
      <div className="page-head" style={{ marginTop: 28 }}>
        <h2>账单</h2>
        <input type="month" value={month} onChange={(event) => { setMonth(event.target.value); void loadBill(event.target.value).catch(setError); }} />
      </div>
      {bill && (
        <>
          <div className="bill">
            <Stat label="期初" value={bill.opening} />
            <Stat label="充值" value={bill.topup} />
            <Stat label="消费" value={bill.spend} />
            <Stat label="提现 / 费" value={`${bill.withdraw} / ${bill.fees}`} />
            <Stat label="期末" value={bill.closing} />
          </div>
          <div className="sheet">
            {bill.entries.length === 0 && <p className="muted">这个月没有账。</p>}
            {bill.entries.map((entry) => <Entry key={entry.id} entry={entry} />)}
          </div>
        </>
      )}
    </section>
  );
}

// 展示账单上的一个汇总数字。
function Stat({ label, value }: { label: string; value: string }) {
  return <div><span className="muted">{label}</span><b className="mono">{value}</b></div>;
}

// 展示一条流水。
function Entry({ entry }: { entry: LedgerEntry }) {
  return (
    <div className="entry">
      <div>
        <strong>{KIND[entry.kind] ?? entry.kind}</strong>
        <div className="muted">{entry.merchant || entry.note}</div>
      </div>
      <div className="right">
        <div className="mono">{entry.kind === "topup" ? "+" : "-"}${entry.amount}</div>
        <div className="muted">余额 ${entry.balanceAfter}</div>
      </div>
    </div>
  );
}

// 返回当月，格式 YYYY-MM。
function currentMonth(): string {
  const now = new Date();
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, "0")}`;
}
