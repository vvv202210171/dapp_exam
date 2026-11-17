// 和 /api/v1 对齐的类型。金额是字符串，卡余额在 JSON 里是 "0.00"，不要改成 number。
export type Chain = "bitcoin" | "ethereum" | "bnb" | "solana" | "tron";
export type Network = "mainnet" | "testnet";

export const CHAINS: Chain[] = ["bitcoin", "ethereum", "bnb", "solana", "tron"];

export const CHAIN_LABEL: Record<Chain, string> = {
  bitcoin: "Bitcoin",
  ethereum: "Ethereum",
  bnb: "BNB",
  solana: "Solana",
  tron: "TRON",
};

export type AssetInfo = {
  symbol: string;
  name: string;
  decimals: number;
  kind: string;
  contract?: string;
  note?: string;
};

export type ChainInfo = {
  id: Chain;
  name: string;
  native: string;
  explorerTx: string;
  assets: AssetInfo[];
};

export type Meta = {
  mode: "sandbox" | "live";
  network: Network;
  warnings: string[];
  chains: ChainInfo[];
  settlement: { name: string; note: string };
};

export type Account = {
  id: string;
  label: string;
  createdAt: string;
  addresses: { chain: Chain; address: string; path: string }[];
};

export type ApiErrorBody = {
  error: { code: string; message: string; hint?: string; retryable?: boolean };
};

export type AssetLine = {
  symbol: string;
  name: string;
  kind: string;
  decimals: number;
  contract?: string;
  amount: string;
  usd: string;
  note?: string;
  ok: boolean;
  error?: ApiErrorBody["error"];
};

export type Portfolio = {
  accountId: string;
  mode: string;
  network: Network;
  priceSource: string;
  totalUsd: string;
  chains: {
    chain: Chain;
    name: string;
    address: string;
    path: string;
    ok: boolean;
    error?: ApiErrorBody["error"];
    assets: AssetLine[];
  }[];
};

export type PrepareResult = {
  chain: Chain;
  network: Network;
  mode: string;
  asset: string;
  from: string;
  to: string;
  amount: string;
  fee: { symbol: string; amount: string; usd?: string; detail?: Record<string, string> };
  payload: Record<string, unknown>;
  warnings: string[];
};

export type Transfer = {
  id: string;
  accountId: string;
  chain: Chain;
  asset: string;
  from: string;
  to: string;
  amount: string;
  fee: string;
  txHash: string;
  signedPayload: string;
  status: "signed" | "broadcast" | "confirmed" | "failed";
  detail?: string;
  createdAt: string;
  updatedAt: string;
};

export type Card = {
  id: string;
  accountId: string;
  kind: "virtual" | "physical";
  status: string;
  last4: string;
  expiry: string;
  holder: string;
  currency: string;
  available: string;
  createdAt: string;
};

export type LedgerEntry = {
  id: string;
  cardId: string;
  kind: string;
  amount: string;
  balanceAfter: string;
  merchant?: string;
  mcc?: string;
  ref: string;
  note?: string;
  createdAt: string;
};

export type Bill = {
  cardId: string;
  month: string;
  currency: string;
  opening: string;
  topup: string;
  spend: string;
  withdraw: string;
  fees: string;
  closing: string;
  entries: LedgerEntry[];
};

export type SettlementSpend = {
  entryId: string;
  cardId: string;
  last4: string;
  merchant?: string;
  amount: string;
  units: string;
  settled: boolean;
  txHash?: string;
  createdAt: string;
};

export type SettlementView = {
  online: boolean;
  hint?: string;
  chainId?: string;
  rpc?: string;
  settlement?: string;
  usdc?: string;
  operator?: string;
  user?: string;
  eth?: string;
  walletUsdc?: string;
  allowance?: string;
  available?: string;
  spends: SettlementSpend[];
};

export type SettlementQuote = {
  action: string;
  amount: string;
  payload: Record<string, unknown>;
  fee: { symbol: string; amount: string };
  warnings: string[];
};

export type SettlementReceipt = {
  hash: string;
  status: string;
};

export type Health = {
  chain: Chain;
  name: string;
  ok: boolean;
  latencyMs: number;
  block?: string;
  endpoint: string;
  mode: string;
  error?: ApiErrorBody["error"];
};
