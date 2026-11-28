// 备份必须重新解密 localStorage，不能直接把内存里的助记词画出来。
import { useState } from "react";
import { bytesToHex } from "@noble/hashes/utils";
import { CopyButton, Fault } from "../components/ui";
import { useSession } from "../session";
import { CHAINS, CHAIN_LABEL, type Chain } from "../types";
import { deriveAddress, pathFor } from "../wallet/derive";
import { openVault } from "../wallet/vault";

type KeyLine = { chain: Chain; address: string; privateKey: string };

// 展示派生路径，并在核对密码后重新显示助记词和各链私钥。
export function Backup() {
  const session = useSession();
  const [password, setPassword] = useState("");
  const [words, setWords] = useState<string[] | null>(null);
  const [keys, setKeys] = useState<KeyLine[] | null>(null);
  const [error, setError] = useState<unknown>(null);

  // 用密码从 localStorage 解密助记词，再派生各链私钥。
  async function reveal() {
    setError(null);
    try {
      const opened = await openVault(password);
      if (opened.mnemonic !== session.mnemonic) throw new Error("密码解开的不是当前这套助记词。");
      setWords(opened.mnemonic.split(" "));
      setKeys(CHAINS.map((chain) => {
        const derived = deriveAddress(opened.mnemonic, chain, session.meta.network, 0);
        return { chain, address: derived.address, privateKey: bytesToHex(derived.privateKey) };
      }));
    } catch (err) {
      setWords(null);
      setKeys(null);
      setError(err);
    }
  }

  return (
    <section>
      <div className="page-head">
        <div>
          <p className="eyebrow">备份</p>
          <h2>再看一次助记词</h2>
        </div>
      </div>
      <div className="two">
        <div className="sheet stack">
          <p className="muted">需要再输入密码。这一步会重新从 localStorage 解密，而不是直接把内存里的词画出来。</p>
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
          <button className="primary" type="button" onClick={() => void reveal()}>显示</button>
          <Fault error={error} />
          {words && (
            <div className="words">
              {words.map((word, index) => <div key={index}><span>{index + 1}</span>{word}</div>)}
            </div>
          )}
        </div>
        <div className="sheet">
          <h2>派生路径</h2>
          {CHAINS.map((chain) => (
            <div className="entry" key={chain}>
              <strong>{CHAIN_LABEL[chain]}</strong>
              <span className="mono">{pathFor(chain, session.meta.network, 0)}</span>
            </div>
          ))}
          <p className="muted">以太坊和 BNB 共用 coin type 60，所以地址和私钥都相同。比特币测试网用 1，主网用 0，地址会变。Solana 走 SLIP-0010 的 ed25519，不能用 secp256k1 那套 BIP32。</p>
          {keys && (
            <>
              <h2>私钥</h2>
              {keys.map((line) => (
                <div className="entry" key={line.chain}>
                  <div>
                    <strong>{CHAIN_LABEL[line.chain]}</strong>
                    <div className="muted">{line.address}</div>
                    <div className="mono" style={{ wordBreak: "break-all" }}>{line.privateKey}</div>
                  </div>
                  <CopyButton value={line.privateKey} />
                </div>
              ))}
            </>
          )}
        </div>
      </div>
    </section>
  );
}
