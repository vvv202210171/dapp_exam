import { createHash } from "node:crypto";

/**
 * 直接问链节点算手续费。公式和 Java / Go 报价一致，只读，不广播。
 *
 *   node scripts/chain-fee.mjs
 *   node scripts/chain-fee.mjs settlement deposit 25
 *   node scripts/chain-fee.mjs evm --from 0x8a8b... --to 0x9858... --amount 0.01
 *   node scripts/chain-fee.mjs evm --from 0x8a8b... --to 0x9858... --amount 25 --token 0x5FbD...
 *   node scripts/chain-fee.mjs btc --from tb1q... --to tb1q... --amount 0.0001
 *   node scripts/chain-fee.mjs sol
 *   node scripts/chain-fee.mjs tron --from T... --to T... --amount 1
 *   node scripts/chain-fee.mjs tron --from T... --to T... --amount 1 --token T...
 */

const SETTLEMENT_RPC = "http://127.0.0.1:8545";
const SETTLEMENT = "0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512";
const USDC = "0x5FbDB2315678afecb367f032d93F642f64180aa3";
const USER = "0x8a8bc31678b81e094ceed04e11a4faf8c89af626";

const SELECTOR = {
  approve: "095ea7b3",
  deposit: "47e7ef24",
  withdraw: "f3fef3a3",
  transfer: "a9059cbb",
};

async function main() {
  const [cmd, ...rest] = process.argv.slice(2);
  if (!cmd || cmd === "settlement") {
    const [action, amount = "25"] = rest;
    const actions = action ? [action] : ["approve", "deposit", "withdraw"];
    const rows = [];
    for (const name of actions) rows.push(await settlementQuote(name, amount));
    print(rows.length === 1 ? rows[0] : rows);
    return;
  }
  if (cmd === "evm") return print(await evmQuote(flags(rest)));
  if (cmd === "btc") return print(await btcQuote(flags(rest)));
  if (cmd === "sol") return print(await solQuote(flags(rest)));
  if (cmd === "tron") return print(await tronQuote(flags(rest)));
  throw new Error("命令只能是 settlement、evm、btc、sol、tron");
}

/**
 * 结算合约三笔用户交易：approve 打到 USDC，deposit / withdraw 打到结算合约。
 * gas 算法和钱包转 ETH 相同。
 */
async function settlementQuote(action, amount) {
  const units = parseUnits(amount, 6);
  let to;
  let data;
  if (action === "approve") {
    to = USDC;
    data = pack(SELECTOR.approve, wordAddress(SETTLEMENT), wordUint(units));
  } else if (action === "deposit") {
    to = SETTLEMENT;
    data = pack(SELECTOR.deposit, wordAddress(USDC), wordUint(units));
  } else if (action === "withdraw") {
    to = SETTLEMENT;
    data = pack(SELECTOR.withdraw, wordAddress(USDC), wordUint(units));
  } else {
    throw new Error("settlement 的动作只能是 approve、deposit、withdraw");
  }
  try {
    const fee = await quoteEip1559({ rpc: SETTLEMENT_RPC, from: USER, to, data, symbol: "ETH" });
    return { kind: "settlement", action, amount, units: units.toString(), from: USER, to, data, ...fee };
  } catch (err) {
    return { kind: "settlement", action, amount, from: USER, to, data, error: err.message };
  }
}

/**
 * 以太坊或 BNB 转账。不传 --token 时转原生币；传了就对代币合约做 transfer，value 为 0。
 */
async function evmQuote(opt) {
  const rpc = opt.rpc || SETTLEMENT_RPC;
  const from = required(opt, "from");
  const to = required(opt, "to");
  const amount = required(opt, "amount");
  const asset = opt.asset || (opt.token ? "USDC" : opt.symbol || "ETH");
  const feeSymbol = opt.symbol || "ETH";
  const decimals = Number(opt.decimals || (opt.token ? 6 : 18));
  const units = parseUnits(amount, decimals);
  let target = to;
  let data = "0x";
  let value = units;
  if (opt.token) {
    target = opt.token;
    data = pack(SELECTOR.transfer, wordAddress(to), wordUint(units));
    value = 0n;
  }
  const fee = await quoteEip1559({ rpc, from, to: target, data, value, symbol: feeSymbol });
  return { kind: "evm", asset, amount, from, to, token: opt.token || "", ...fee };
}

/**
 * EIP-1559，和 EvmClient.quote / HttpSettlementRpc.quote 相同。
 *
 * gasLimit = eth_estimateGas * 12 / 10
 * tip = eth_maxPriorityFeePerGas，没有或为 0 时用 eth_gasPrice / 10
 * maxFeePerGas = eth_gasPrice * 2 + tip
 * 手续费 = gasLimit * maxFeePerGas，再按 18 位小数显示
 */
async function quoteEip1559({ rpc, from, to, data, value = 0n, symbol }) {
  const call = { from, to };
  if (value > 0n) call.value = "0x" + value.toString(16);
  if (data && data !== "0x") call.data = data;
  const gasEstimate = hex(await jsonRpc(rpc, "eth_estimateGas", [call]));
  const gasLimit = (gasEstimate * 12n) / 10n;
  const gasPrice = hex(await jsonRpc(rpc, "eth_gasPrice", []));
  let tip = gasPrice / 10n;
  try {
    const parsed = hex(await jsonRpc(rpc, "eth_maxPriorityFeePerGas", []));
    if (parsed > 0n) tip = parsed;
  } catch {
    // 老节点没有这个方法，继续用 gasPrice / 10。
  }
  let maxFee = gasPrice * 2n + tip;
  if (tip > maxFee) tip = maxFee;
  const nonce = hex(await jsonRpc(rpc, "eth_getTransactionCount", [from, "pending"]));
  const chainId = hex(await jsonRpc(rpc, "eth_chainId", []));
  const feeWei = gasLimit * maxFee;
  return {
    rpc,
    chainId: chainId.toString(),
    nonce: nonce.toString(),
    gasEstimate: gasEstimate.toString(),
    gasLimit: gasLimit.toString(),
    gasPrice: gasPrice.toString(),
    maxPriorityFeePerGas: tip.toString(),
    maxFeePerGas: maxFee.toString(),
    fee: { symbol, amount: formatUnits(feeWei, 18), wei: feeWei.toString() },
  };
}

/**
 * 比特币。费率来自 mempool 的 halfHourFee，缺了用 8 sat/vB。
 * 虚拟字节 = (11 + 输入*68 + 输出*31) 再加 10%。只花已确认 UTXO。
 */
async function btcQuote(opt) {
  const api = (opt.api || "https://mempool.space/testnet/api").replace(/\/$/, "");
  const from = required(opt, "from");
  const to = required(opt, "to");
  const amount = required(opt, "amount");
  const sats = parseUnits(amount, 8);
  const fees = await getJson(api + "/v1/fees/recommended");
  let feeRate = 8n;
  if (BigInt(fees.halfHourFee || 0) > 0n) feeRate = BigInt(fees.halfHourFee);
  const rows = await getJson(api + "/address/" + from + "/utxo");
  const utxos = (Array.isArray(rows) ? rows : [])
    .filter((row) => row.status && row.status.confirmed)
    .map((row) => ({ txid: row.txid, vout: row.vout, value: BigInt(row.value) }))
    .sort((a, b) => (a.value > b.value ? -1 : 1));
  if (utxos.length === 0) throw new Error("没有已确认的 UTXO");
  const coins = selectCoins(utxos, sats, feeRate);
  return {
    kind: "bitcoin",
    api,
    from,
    to,
    amount,
    feeRateSatVb: feeRate.toString(),
    inputs: coins.selected.length,
    outputs: coins.change > 0n ? 2 : 1,
    fee: { symbol: "BTC", amount: formatUnits(coins.fee, 8), sats: coins.fee.toString() },
    changeSats: coins.change.toString(),
  };
}

function selectCoins(utxos, amount, feeRate) {
  const selected = [];
  let total = 0n;
  for (const utxo of utxos) {
    if (utxo.value <= 0n) continue;
    selected.push(utxo);
    total += utxo.value;
    const fit = fitCoins(total, amount, feeRate, selected.length);
    if (fit) return { selected, fee: fit.fee, change: fit.change };
  }
  throw new Error("比特币余额不够支付金额和手续费");
}

function fitCoins(total, amount, feeRate, nIn) {
  if (total < amount) return null;
  const feeWithChange = estimateVSize(nIn, 2) * feeRate;
  if (total >= amount + feeWithChange) {
    const change = total - amount - feeWithChange;
    if (change >= 546n) return { fee: feeWithChange, change };
  }
  const feeNoChange = estimateVSize(nIn, 1) * feeRate;
  if (total >= amount + feeNoChange) return { fee: total - amount, change: 0n };
  return null;
}

function estimateVSize(nIn, nOut) {
  let v = 11n + BigInt(nIn) * 68n + BigInt(nOut) * 31n;
  v += v / 10n;
  return v < 1n ? 1n : v;
}

/**
 * Solana 手续费在这个项目里是固定 5000 lamports。节点只提供 blockhash，不提供这笔费用。
 */
async function solQuote(opt) {
  const rpc = opt.rpc || "https://api.devnet.solana.com";
  const blockhash = await jsonRpc(rpc, "getLatestBlockhash", [{ commitment: "confirmed" }]);
  return {
    kind: "solana",
    rpc,
    blockhash: blockhash.value.blockhash,
    fee: { symbol: "SOL", amount: "0.000005", lamports: "5000" },
    note: "5000 lamports 是固定值。向新地址转账时，金额通常还要另加约 0.00089 SOL 租金。",
  };
}

/**
 * TRX 转账先问节点组交易，报价记 0，优先扣带宽。
 * TRC20 问节点组合约调用，报价固定 15 TRX，fee_limit 是 1 亿 sun。
 */
async function tronQuote(opt) {
  const rpc = (opt.rpc || "https://api.shasta.trongrid.io").replace(/\/$/, "");
  const from = required(opt, "from");
  const to = required(opt, "to");
  const amount = required(opt, "amount");
  if (!opt.token) {
    const sun = parseUnits(amount, 6);
    const tx = await postJson(rpc + "/wallet/createtransaction", {
      owner_address: from,
      to_address: to,
      amount: Number(sun),
      visible: true,
    });
    if (!tx.txID) throw new Error(tx.Error || tx.error || "节点没有返回 txID");
    return {
      kind: "tron",
      asset: "TRX",
      rpc,
      txID: tx.txID,
      fee: { symbol: "TRX", amount: "0" },
      resource: "bandwidth",
      note: "带宽不够时才会燃烧少量 TRX。这个 0 不是节点报出的燃烧额。",
    };
  }
  const units = parseUnits(amount, Number(opt.decimals || 6));
  const tx = await postJson(rpc + "/wallet/triggersmartcontract", {
    owner_address: from,
    contract_address: opt.token,
    function_selector: "transfer(address,uint256)",
    parameter: tronAddressWord(to) + wordUint(units),
    fee_limit: 100_000_000,
    call_value: 0,
    visible: true,
  });
  if (!tx.result || !tx.result.result) throw new Error((tx.result && tx.result.message) || "TRC20 组交易失败");
  return {
    kind: "tron",
    asset: "TRC20",
    rpc,
    txID: tx.transaction.txID,
    fee: { symbol: "TRX", amount: "15" },
    feeLimitSun: "100000000",
    note: "15 TRX 是估算，不是节点当场算出的精确值。能量不足时燃烧 TRX，上限是 fee_limit。",
  };
}

function tronAddressWord(address) {
  const alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  let n = 0n;
  for (const ch of address) {
    const i = alphabet.indexOf(ch);
    if (i < 0) throw new Error("TRON 地址不是 Base58: " + address);
    n = n * 58n + BigInt(i);
  }
  let hex = n.toString(16);
  if (hex.length % 2) hex = "0" + hex;
  hex = hex.padStart(50, "0");
  const body = hex.slice(0, -8);
  const checksum = hex.slice(-8);
  const hash = createHash("sha256")
    .update(createHash("sha256").update(Buffer.from(body, "hex")).digest())
    .digest("hex")
    .slice(0, 8);
  if (hash !== checksum) throw new Error("TRON 地址校验失败: " + address);
  if (body.length !== 42 || !body.startsWith("41")) throw new Error("TRON 地址不正确: " + address);
  return body.slice(2).padStart(64, "0");
}

async function jsonRpc(url, method, params) {
  const body = await postJson(url, { jsonrpc: "2.0", id: 1, method, params });
  if (body.error) throw new Error(method + ": " + (body.error.message || JSON.stringify(body.error)));
  return body.result;
}

async function postJson(url, payload) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
  const text = await res.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error(url + " 返回的不是 JSON: " + text.slice(0, 200));
  }
  if (!res.ok && body.error == null) throw new Error(url + " HTTP " + res.status);
  return body;
}

async function getJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(url + " HTTP " + res.status);
  return res.json();
}

function pack(selector, ...words) {
  return "0x" + selector + words.join("");
}

function wordAddress(addr) {
  const hex = String(addr).trim().replace(/^0x/i, "");
  if (!/^[0-9a-fA-F]{40}$/.test(hex)) throw new Error("EVM 地址格式不正确: " + addr);
  return hex.toLowerCase().padStart(64, "0");
}

function wordUint(n) {
  if (n < 0n) throw new Error("数量不能是负数");
  return n.toString(16).padStart(64, "0");
}

function parseUnits(text, decimals) {
  const s = String(text).trim();
  if (!/^\d+(\.\d+)?$/.test(s)) throw new Error("金额格式不对: " + text);
  const [whole, frac = ""] = s.split(".");
  if (frac.length > decimals) throw new Error("小数位超过 " + decimals);
  return BigInt(whole + frac.padEnd(decimals, "0"));
}

function formatUnits(value, decimals) {
  if (value === 0n) return "0";
  const neg = value < 0n;
  let s = (neg ? -value : value).toString();
  if (decimals === 0) return neg ? "-" + s : s;
  if (s.length <= decimals) s = "0".repeat(decimals - s.length + 1) + s;
  const whole = s.slice(0, -decimals);
  const frac = s.slice(-decimals).replace(/0+$/, "");
  const out = frac ? whole + "." + frac : whole;
  return neg ? "-" + out : out;
}

function hex(value) {
  if (value == null || value === "0x") return 0n;
  return BigInt(value);
}

function flags(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (next == null || next.startsWith("--")) out[key] = true;
    else {
      out[key] = next;
      i++;
    }
  }
  return out;
}

function required(opt, key) {
  if (!opt[key]) throw new Error("缺少 --" + key);
  return opt[key];
}

function print(value) {
  console.log(JSON.stringify(value, null, 2));
}

main().catch((err) => {
  console.error(err.message || err);
  process.exit(1);
});
