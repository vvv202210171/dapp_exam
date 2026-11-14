import { NavLink, Outlet } from "react-router-dom";
import { useSession } from "../session";

const LINKS = [
  ["", "资产"],
  ["send", "转账"],
  ["activity", "记录"],
  ["cards", "卡片"],
  ["console", "链控制台"],
  ["settlement", "链上结算"],
  ["backup", "备份"],
] as const;

// 钱包内的导航壳，包含锁定按钮。
export function Shell() {
  const session = useSession();
  return (
    <div className="shell">
      <aside className="rail">
        <div className="brand">
          <span className="mark">Ae</span>
          <div>
            <strong>Aegis</strong>
            <small>钱包 / 卡</small>
          </div>
        </div>
        <nav>
          {LINKS.map(([path, label], index) => (
            <NavLink key={path} to={path} end={path === ""}>
              <span>{String(index + 1).padStart(2, "0")}</span>
              {label}
            </NavLink>
          ))}
        </nav>
        <button className="text-btn lock" type="button" onClick={session.lock}>
          锁定
        </button>
      </aside>
      <main>
        <header className="topbar">
          <div>
            <p className="eyebrow">{session.label}</p>
            <strong className="mode">
              {session.meta.mode === "sandbox" ? "沙箱" : "Live"} · {session.meta.network === "mainnet" ? "主网" : "测试网"}
            </strong>
          </div>
        </header>
        {session.meta.warnings[0] && <div className="banner">{session.meta.warnings[0]}</div>}
        <Outlet />
      </main>
    </div>
  );
}
