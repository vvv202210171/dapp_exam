import { useState, type ReactNode } from "react";
import { ApiError } from "../api";

// 把接口错误的代码、说明和提示显示出来。
export function Fault({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof Error ? error.message : "出错了";
  const hint = error instanceof ApiError ? error.hint : undefined;
  const code = error instanceof ApiError ? error.code : undefined;
  return (
    <div className="fault" role="alert">
      <strong>{message}</strong>
      {code && <span className="code">{code}</span>}
      {hint && <p>{hint}</p>}
    </div>
  );
}

// 给表单控件包一层标题。
export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}

// 把一段文本复制到剪贴板。
export function CopyButton({ value }: { value: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      className="text-btn"
      type="button"
      onClick={() => {
        void navigator.clipboard.writeText(value).then(() => {
          setDone(true);
          window.setTimeout(() => setDone(false), 1200);
        });
      }}
    >
      {done ? "已复制" : "复制"}
    </button>
  );
}

// 缩短地址，便于在列表里显示。
export function shortAddress(value: string): string {
  if (value.length <= 18) return value;
  return `${value.slice(0, 8)}…${value.slice(-6)}`;
}

// 没有金额时显示 0。
export function money(value: string | undefined): string {
  return value && value !== "" ? value : "0";
}
