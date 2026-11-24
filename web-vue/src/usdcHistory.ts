// 本地 Hardhat 上的测试 USDC 不写钱包转账表，也不发 Transfer 事件。这里按区块里的交易把领取、划入和提现读出来。

export type UsdcMove = {
  hash: string;
  createdAt: string;
  kind: "mint" | "deposit" | "withdraw" | "in" | "out";
  amount: string;
  from: string;
  to: string;
};

const MINT = "40c10f19";
const TRANSFER = "a9059cbb";
const TRANSFER_FROM = "23b872dd";
const DEPOSIT = "47e7ef24";
const WITHDRAW = "f3fef3a3";

// 读取这个地址在本地结算 USDC 上的成功交易，新的在前。
export async function localUsdcHistory(rpc: string, usdc: string, settlement: string, user: string): Promise<UsdcMove[]> {
  const token = usdc.toLowerCase();
  const box = settlement.toLowerCase();
  const who = user.toLowerCase();
  const latest = hex(await rpcCall<string>(rpc, "eth_blockNumber", []));
  const blocks = await rpcBatch<{ timestamp: string; transactions: RawTx[] }>(
    rpc,
    Array.from({ length: latest }, (_, index) => ({
      method: "eth_getBlockByNumber",
      params: ["0x" + (index + 1).toString(16), true],
    })),
  );
  const found: Found[] = [];
  blocks.forEach((block, index) => {
    const time = new Date(hex(block.timestamp) * 1000).toISOString();
    for (const tx of block.transactions ?? []) {
      const move = decode(tx, token, box, who, time);
      if (move) found.push({ ...move, block: index + 1 });
    }
  });
  if (found.length === 0) return [];
  const receipts = await rpcBatch<{ status?: string }>(
    rpc,
    found.map((row) => ({ method: "eth_getTransactionReceipt", params: [row.hash] })),
  );
  return found
    .filter((_, index) => receipts[index]?.status === "0x1")
    .sort((a, b) => b.block - a.block)
    .map(({ block: _block, ...row }) => row);
}

type RawTx = { hash: string; from: string; to?: string | null; input?: string };
type Found = UsdcMove & { block: number };

// 认出和这个地址有关的 USDC 动作。授权不算转账。
function decode(tx: RawTx, usdc: string, settlement: string, user: string, createdAt: string): UsdcMove | null {
  const to = (tx.to ?? "").toLowerCase();
  const from = tx.from.toLowerCase();
  const data = (tx.input ?? "").toLowerCase().replace(/^0x/, "");
  const selector = data.slice(0, 8);
  if (to === usdc && selector === MINT) {
    const recipient = address(data, 0);
    if (recipient !== user) return null;
    return row(tx.hash, createdAt, "mint", units(data, 1), from, recipient);
  }
  if (to === usdc && selector === TRANSFER) {
    const recipient = address(data, 0);
    if (from !== user && recipient !== user) return null;
    return row(tx.hash, createdAt, from === user ? "out" : "in", units(data, 1), from, recipient);
  }
  if (to === usdc && selector === TRANSFER_FROM) {
    const sender = address(data, 0);
    const recipient = address(data, 1);
    if (sender !== user && recipient !== user) return null;
    return row(tx.hash, createdAt, sender === user ? "out" : "in", units(data, 2), sender, recipient);
  }
  if (to === settlement && selector === DEPOSIT && from === user && address(data, 0) === usdc) {
    return row(tx.hash, createdAt, "deposit", units(data, 1), from, settlement);
  }
  if (to === settlement && selector === WITHDRAW && from === user && address(data, 0) === usdc) {
    return row(tx.hash, createdAt, "withdraw", units(data, 1), settlement, from);
  }
  return null;
}

function row(hash: string, createdAt: string, kind: UsdcMove["kind"], amount: string, from: string, to: string): UsdcMove {
  return { hash, createdAt, kind, amount, from, to };
}

// 取出 ABI 字里的地址。
function address(data: string, index: number): string {
  return "0x" + word(data, index).slice(24);
}

// 把 6 位小数的整数收成可读金额。
function units(data: string, index: number): string {
  const value = BigInt("0x" + word(data, index));
  const whole = value / 1_000_000n;
  const frac = (value % 1_000_000n).toString().padStart(6, "0").replace(/0+$/, "");
  return frac ? `${whole}.${frac}` : whole.toString();
}

function word(data: string, index: number): string {
  return data.slice(8 + index * 64, 8 + (index + 1) * 64).padStart(64, "0");
}

function hex(value: string): number {
  return Number.parseInt(value, 16);
}

async function rpcCall<T>(url: string, method: string, params: unknown[]): Promise<T> {
  const [row] = await rpcBatch<T>(url, [{ method, params }]);
  return row;
}

async function rpcBatch<T>(url: string, calls: { method: string; params: unknown[] }[]): Promise<T[]> {
  if (calls.length === 0) return [];
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(calls.map((call, id) => ({ jsonrpc: "2.0", id, method: call.method, params: call.params }))),
  });
  if (!response.ok) throw new Error("读不到本地 USDC 记录");
  const body = await response.json() as { id: number; result?: T; error?: { message?: string } } | { id: number; result?: T; error?: { message?: string } }[];
  const rows = Array.isArray(body) ? body : [body];
  rows.sort((a, b) => a.id - b.id);
  const failed = rows.find((row) => row.error);
  if (failed?.error) throw new Error(failed.error.message || "读不到本地 USDC 记录");
  return rows.map((row) => row.result as T);
}
