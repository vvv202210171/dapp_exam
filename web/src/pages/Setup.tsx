// 创建或导入助记词。确认三个随机词之后才允许设密码，避免没抄下来就进入钱包。
import { useMemo, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { Fault, Field } from "../components/ui";
import { useWallet } from "../session";
import { createMnemonic, mnemonicValid, normalizeMnemonic } from "../wallet/derive";

// 创建或导入助记词，核对单词后设置密码。
export function Setup() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const wallet = useWallet();
  const initial = params.get("mode") === "import" ? "import" : "show";
  const [mnemonic, setMnemonic] = useState(() => (initial === "import" ? "" : createMnemonic()));
  const [stage, setStage] = useState<"show" | "check" | "import" | "password">(initial === "import" ? "import" : "show");
  const [picks] = useState(() => sampleIndexes());
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [label, setLabel] = useState("主账户");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const words = useMemo(() => normalizeMnemonic(mnemonic).split(" ").filter(Boolean), [mnemonic]);

  // 检查用户填的三个单词是否和助记词一致。
  function verify() {
    const ok = picks.every((index) => answers[index]?.trim().toLowerCase() === words[index]);
    if (!ok) {
      setError(new Error("核对不一致。回到上一页，按顺序把 12 个词抄到纸上再试。"));
      return;
    }
    setError(null);
    setStage("password");
  }

  // 用助记词和密码进入钱包。
  async function finish() {
    setBusy(true);
    setError(null);
    try {
      if (!mnemonicValid(mnemonic)) throw new Error("助记词无效，检查单词是否在 BIP39 英文词表里。");
      if (password.length < 8) throw new Error("密码至少 8 位。");
      if (password !== confirm) throw new Error("两次密码不一致。");
      await wallet.saveAndEnter(normalizeMnemonic(mnemonic), password, label.trim() || "主账户");
      navigate("/app");
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="setup">
      <div className="hero">
        <div>
          <p className="eyebrow">备份</p>
          <h1>{stage === "import" ? "导入已有助记词。" : "先把词写下来。"}</h1>
          <p className="lede">助记词是这个钱包的根。它经过 PBKDF2 和 AES-GCM 后存在 localStorage，明文只在解锁后的内存里。</p>
        </div>
        <Link to="/" className="text-btn">返回</Link>
      </div>
      <div className="panel">
        <div className="card stack">
          {stage === "show" && (
            <>
              <h2>12 个单词</h2>
              <div className="words">
                {words.map((word, index) => <div key={index}><span>{index + 1}</span>{word}</div>)}
              </div>
              <label className="row"><input type="checkbox" checked={saved} onChange={(event) => setSaved(event.target.checked)} />我已经离线抄下来了</label>
              <button className="primary" type="button" disabled={!saved} onClick={() => setStage("check")}>继续核对</button>
            </>
          )}
          {stage === "check" && (
            <>
              <h2>核对 3 个词</h2>
              {picks.map((index) => (
                <Field key={index} label={`第 ${index + 1} 个词`}>
                  <input value={answers[index] ?? ""} onChange={(event) => setAnswers({ ...answers, [index]: event.target.value })} />
                </Field>
              ))}
              <Fault error={error} />
              <div className="row">
                <button className="ghost" type="button" onClick={() => setStage("show")}>返回单词</button>
                <button className="primary" type="button" onClick={verify}>核对</button>
              </div>
            </>
          )}
          {stage === "import" && (
            <>
              <h2>粘贴助记词</h2>
              <Field label="12 或 24 个英文单词">
                <textarea value={mnemonic} onChange={(event) => setMnemonic(event.target.value)} />
              </Field>
              <button className="primary" type="button" onClick={() => {
                if (!mnemonicValid(mnemonic)) {
                  setError(new Error("助记词无效。"));
                  return;
                }
                setError(null);
                setStage("password");
              }}>继续</button>
              <Fault error={error} />
            </>
          )}
          {stage === "password" && (
            <>
              <h2>设置密码</h2>
              <Field label="账户名称"><input value={label} onChange={(event) => setLabel(event.target.value)} /></Field>
              <Field label="密码"><input type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></Field>
              <Field label="再输入一次"><input type="password" value={confirm} onChange={(event) => setConfirm(event.target.value)} /></Field>
              <p className="muted">这里用了 21 万次 PBKDF2。生产环境应按 OWASP 提到 60 万次，或改用 Argon2id。浏览器里没有 Argon2，所以选了 PBKDF2。</p>
              <Fault error={error} />
              <button className="primary" type="button" disabled={busy} onClick={() => void finish()}>{busy ? "正在同步地址…" : "创建并进入"}</button>
            </>
          )}
        </div>
      </div>
    </section>
  );
}

// 随机抽三个不重复的单词位置用来核对。
function sampleIndexes(): number[] {
  const bag = Array.from({ length: 12 }, (_, index) => index);
  for (let i = bag.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [bag[i], bag[j]] = [bag[j], bag[i]];
  }
  return bag.slice(0, 3).sort((a, b) => a - b);
}
