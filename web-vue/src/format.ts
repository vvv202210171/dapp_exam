// 缩短地址，便于在列表里显示。
export function shortAddress(value: string): string {
  if (value.length <= 18) return value;
  return `${value.slice(0, 8)}…${value.slice(-6)}`;
}

// 没有金额时显示 0。
export function money(value: string | undefined): string {
  return value && value !== "" ? value : "0";
}

// 把流水时间显示成本地的日期和时间。
export function formatWhen(value: string): string {
  const time = new Date(/^\d+$/.test(value) ? Number(value) : value);
  if (Number.isNaN(time.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${time.getFullYear()}-${pad(time.getMonth() + 1)}-${pad(time.getDate())} ${pad(time.getHours())}:${pad(time.getMinutes())}:${pad(time.getSeconds())}`;
}
