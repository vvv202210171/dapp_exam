// 链控制台。探针名称来自白名单，页面不能填任意 URL。
import { useEffect, useState } from "react";
import { api } from "../api";
import { Fault, Field } from "../components/ui";
import { useSession } from "../session";
import type { Chain, Health } from "../types";

const COOKBOOK = [
  ["RPC_TIMEOUT", "节点超时", "先看是所有链都超时，还是只有一条。所有链都超时多半是本机网络；只有一条就换那条链的 RPC。"],
  ["RATE_LIMITED", "公共节点限流", "不要用定时器猛刷余额。演示完就停。真环境换自己的节点或带 key 的服务。"],
  ["NONCE_TOO_LOW", "Nonce 过低", "重新报价。报价用的是 pending nonce。上一笔还在内存池时，要么等，要么加价替换。"],
  ["INSUFFICIENT_FUNDS", "余额不够", "代币转账也要付原生币 gas。ETH 上的 USDT 是 6 位小数，BSC 上的 USDT 是 18 位，填错会差 12 个零。"],
  ["REVERTED", "合约回滚", "用同样的 calldata 做一次 eth_call，看 revert 原因。常见的是余额不足、没 approve、USDT 黑名单。"],
  ["BLOCKHASH_EXPIRED", "Solana blockhash 过期", "大约一分钟。重新报价再签，不要拿旧 payload 反复广播。"],
  ["ACCOUNT_INACTIVE", "TRON 账户未激活", "地址要先收到 TRX 才会被创建。空账户直接转出会被节点拒绝。"],
];

// 查看节点健康、运行白名单探针，并展示错误码手册。
export function Console() {
  const session = useSession();
  const [health, setHealth] = useState<Health[]>([]);
  const [chain, setChain] = useState<Chain>("ethereum");
  const [probes, setProbes] = useState<{ name: string; title: string; description: string }[]>([]);
  const [probe, setProbe] = useState("echo");
  const [address, setAddress] = useState(session.derived.ethereum.address);
  const [result, setResult] = useState("");
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    void api<{ chains: Health[] }>("/api/v1/chains/health").then((data) => setHealth(data.chains)).catch(setError);
  }, []);

  useEffect(() => {
    void api<{ probes: { name: string; title: string; description: string }[] }>(`/api/v1/chains/${chain}/probes`).then((data) => {
      setProbes(data.probes);
      setProbe(data.probes[0]?.name ?? "");
    }).catch(setError);
  }, [chain]);

  // 用当前地址调用选中的探针。
  async function run() {
    setError(null);
    try {
      const data = await api<unknown>(`/api/v1/chains/${chain}/probes/${probe}`, {
        method: "POST",
        body: JSON.stringify({ address }),
      });
      setResult(JSON.stringify(data, null, 2));
    } catch (err) {
      setResult("");
      setError(err);
    }
  }

  return (
    <section>
      <div className="page-head">
        <div>
          <p className="eyebrow">链控制台</p>
          <h2>节点通不通，错误下一步看什么</h2>
        </div>
      </div>
      <div className="health-grid">
        {health.map((item) => (
          <article className="health" key={item.chain}>
            <div>
              <strong>{item.name}</strong>
              <div className="tag">{item.endpoint}</div>
            </div>
            <div className={item.ok ? "ok" : "bad"}>{item.ok ? `${item.latencyMs} ms` : "失败"}</div>
          </article>
        ))}
      </div>
      <div className="two">
        <div className="sheet stack">
          <Field label="链">
            <select value={chain} onChange={(event) => {
              const next = event.target.value as Chain;
              setChain(next);
              setAddress(session.derived[next].address);
            }}>
              {session.meta.chains.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
          </Field>
          <Field label="探针">
            <select value={probe} onChange={(event) => setProbe(event.target.value)}>
              {probes.map((item) => <option key={item.name} value={item.name}>{item.title} · {item.description}</option>)}
            </select>
          </Field>
          <Field label="地址"><input className="mono" value={address} onChange={(event) => setAddress(event.target.value)} /></Field>
          <button className="primary" type="button" onClick={() => void run()}>调用</button>
          <p className="muted">方法是白名单。后端不会接受任意 URL，避免变成开放代理。</p>
          <Fault error={error} />
          {result && <pre className="payload">{result}</pre>}
        </div>
        <div className="sheet">
          <h2>排查手册</h2>
          <div className="cookbook">
            {COOKBOOK.map(([code, title, hint]) => (
              <article key={code}>
                <strong>{title}</strong>
                <div className="tag">{code}</div>
                <p className="muted">{hint}</p>
              </article>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
