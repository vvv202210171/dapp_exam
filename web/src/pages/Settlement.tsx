// 只编码 deposit 的 calldata，不签名、不发送。真正上链仍走转账页的 EIP-1559。
import { useState } from "react";
import { Interface, parseUnits } from "ethers";
import { useSession } from "../session";

const ABI = [
  "function deposit(address token, uint256 amount)",
  "function withdraw(address token, uint256 amount)",
  "function settle(address user, address token, uint256 amount, bytes32 ref)",
  "function available(address user, address token) view returns (uint256)",
];
const USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";

// 说明链上结算，并编码一笔 deposit calldata。
export function Settlement() {
  const session = useSession();
  const [amount, setAmount] = useState("25");
  const [token, setToken] = useState(USDC);
  const [calldata, setCalldata] = useState("");
  const [error, setError] = useState("");

  // 按代币地址和金额编码 deposit，不签名也不发送。
  function build() {
    try {
      const data = new Interface(ABI).encodeFunctionData("deposit", [token, parseUnits(amount || "0", 6)]);
      setCalldata(data);
      setError("");
    } catch (err) {
      setCalldata("");
      setError(err instanceof Error ? err.message : "编码失败");
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
      <p className="muted">{session.meta.settlement.note} 沙箱里的美元账本不假装已经上链。</p>
      <div className="steps">
        <article><span>01</span><h3>deposit</h3><p className="muted">用户先 approve，再把 USDC 划进合约。余额记在 available[user][token]。</p></article>
        <article><span>02</span><h3>settle</h3><p className="muted">运营方用账单号结算消费。同一个 bytes32 不能结算两次，钱打给 operator。</p></article>
        <article><span>03</span><h3>withdraw</h3><p className="muted">用户提走还没被结算的部分。先减账再转币，并且有重入锁。</p></article>
      </div>
      <div className="two">
        <div className="sheet stack">
          <h2>构造 deposit calldata</h2>
          <label className="field">USDC 地址<input className="mono" value={token} onChange={(event) => setToken(event.target.value)} /></label>
          <label className="field">金额（6 位小数）<input value={amount} onChange={(event) => setAmount(event.target.value)} /></label>
          <button className="primary" type="button" onClick={build}>编码</button>
          {error && <p className="bad">{error}</p>}
          {calldata && <pre className="payload">{calldata}</pre>}
          <p className="muted">这只是未签名的 calldata。真正发送前还要 nonce、gas 和 chainId，走转账页那条 EIP-1559 路径。</p>
        </div>
        <div className="sheet">
          <h2>和卡账本的关系</h2>
          <p>沙箱卡用整数“分”记账，幂等键防重复提交。合约用代币的最小单位记账，账单号防重复结算。两边都是先改余额，再动钱。</p>
          <p className="muted">测试在 contracts/：npx hardhat test。里面有正常结算，也有一个会在 transfer 里重入的代币。</p>
          <p className="mono">账户 {session.derived.ethereum.address}</p>
        </div>
      </div>
    </section>
  );
}
