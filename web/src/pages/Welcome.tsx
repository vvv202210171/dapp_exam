import { useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { Fault } from "../components/ui";
import { useWallet } from "../session";
import { clearVault, hasVault } from "../wallet/vault";

// 首页：已有保险库就解锁，否则去创建或导入。
export function Welcome() {
  const wallet = useWallet();
  const navigate = useNavigate();
  const [password, setPassword] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [exists, setExists] = useState(hasVault());
  if (wallet.session) return <Navigate to="/app" replace />;

  // 用密码解开本机保险库并进入资产页。
  async function unlock() {
    setBusy(true);
    setError(null);
    try {
      await wallet.unlock(password);
      navigate("/app");
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="welcome">
      <div className="hero">
        <div>
          <p className="eyebrow">Aegis · learning wallet</p>
          <h1>钥匙留在这台浏览器里。</h1>
          <p className="lede">一套助记词派生 Bitcoin、Ethereum、BNB、Solana 和 TRON。转账在本地签名，卡账本单独记账，链上结算用 Solidity 合约。</p>
        </div>
        <div className="points">
          <div><strong>账户与签名</strong><span>助记词加密后只存在 localStorage。服务器只收地址。</span></div>
          <div><strong>五条链</strong><span>余额、报价、广播和状态都走同一套适配器。</span></div>
          <div><strong>异常排查</strong><span>超时、nonce、余额、限流会带上下一步该看什么。</span></div>
          <div><strong>支付卡</strong><span>虚拟卡、实体卡、充值、消费、提现和月账单。</span></div>
        </div>
      </div>
      <div className="panel">
        <div className="card stack">
          {exists ? (
            <>
              <h2>解锁</h2>
              <p className="muted">密码只用来解开本机保险库，不会发给服务器。</p>
              <form className="stack" onSubmit={(event) => { event.preventDefault(); void unlock(); }}>
                <label className="field">密码
                  <input type="password" value={password} autoFocus onChange={(event) => setPassword(event.target.value)} />
                </label>
                <Fault error={error} />
                <button className="primary" disabled={busy || password.length < 8}>{busy ? "正在派生密钥…" : "进入钱包"}</button>
              </form>
              <button className="text-btn" type="button" onClick={() => {
                if (window.confirm("本机保险库会被删除。没有另存的助记词就找不回这个钱包。")) {
                  clearVault();
                  setExists(false);
                }
              }}>清除本机保险库</button>
            </>
          ) : (
            <>
              <h2>开始</h2>
              <p className="muted">先创建一套新的助记词，或者导入你已经记下的 12/24 个单词。这是学习项目，不要使用存有真实资金的助记词。</p>
              <Link className="primary" to="/setup" style={{ textAlign: "center", textDecoration: "none" }}>创建钱包</Link>
              <Link className="ghost" to="/setup?mode=import" style={{ textAlign: "center", textDecoration: "none" }}>导入助记词</Link>
            </>
          )}
        </div>
      </div>
    </section>
  );
}
