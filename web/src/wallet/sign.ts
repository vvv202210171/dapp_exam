// 离线签名。输入是服务端报价，输出是可广播的已签名字节。私钥不出这个模块。
import { Transaction, Wallet, hexlify } from "ethers";
import * as btc from "@scure/btc-signer";
import { secp256k1 } from "@noble/curves/secp256k1";
import { ed25519 } from "@noble/curves/ed25519";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils";
import type { Chain } from "../types";

type EvmPayload = {
  chainId: string;
  nonce: string;
  to: string;
  value: string;
  data: string;
  gasLimit: string;
  maxFeePerGas: string;
  maxPriorityFeePerGas: string;
};

type BtcPayload = {
  network: "mainnet" | "testnet";
  inputs: { txid: string; vout: number; value: number }[];
  outputs: { address: string; value: number }[];
};

type SolPayload = { message: string };
type TronPayload = { txID: string; transaction: Record<string, unknown> };

// 按链把报价交给对应的签名函数。
export async function signPayload(chain: Chain, privateKey: Uint8Array, payload: Record<string, unknown>): Promise<string> {
  if (chain === "ethereum" || chain === "bnb") return signEvm(privateKey, payload as EvmPayload);
  if (chain === "bitcoin") return signBtc(privateKey, payload as BtcPayload);
  if (chain === "solana") return signSol(privateKey, payload as SolPayload);
  return signTron(privateKey, payload as TronPayload);
}

// 签一笔 EIP-1559 交易，chainId 写进签名里。
async function signEvm(privateKey: Uint8Array, payload: EvmPayload): Promise<string> {
  // type 2 是 EIP-1559。chainId 写进签名，沙箱的 84532026 因此不能在公链重放。
  const wallet = new Wallet(hexlify(privateKey));
  return wallet.signTransaction(
    Transaction.from({
      type: 2,
      chainId: BigInt(payload.chainId),
      nonce: Number(payload.nonce),
      to: payload.to,
      value: BigInt(payload.value),
      data: payload.data || "0x",
      gasLimit: BigInt(payload.gasLimit),
      maxFeePerGas: BigInt(payload.maxFeePerGas),
      maxPriorityFeePerGas: BigInt(payload.maxPriorityFeePerGas),
    }),
  );
}

// 签一笔原生隔离见证交易，输出可广播的原始十六进制。
function signBtc(privateKey: Uint8Array, payload: BtcPayload): string {
  // P2WPKH 必须带 witnessUtxo，签名要知道被花掉的那笔输出的脚本和金额。
  const network = payload.network === "testnet" ? btc.TEST_NETWORK : btc.NETWORK;
  const publicKey = secp256k1.getPublicKey(privateKey, true);
  const payment = btc.p2wpkh(publicKey, network);
  if (!payment.script) throw new Error("无法构造 P2WPKH 脚本");
  const tx = new btc.Transaction();
  for (const input of payload.inputs) {
    tx.addInput({
      txid: input.txid,
      index: input.vout,
      witnessUtxo: { script: payment.script, amount: BigInt(input.value) },
    });
  }
  for (const output of payload.outputs) {
    tx.addOutputAddress(output.address, BigInt(output.value), network);
  }
  tx.sign(privateKey);
  tx.finalize();
  return bytesToHex(tx.extract());
}

// 对 message 做 ed25519 签名，并拼成可广播的交易。
function signSol(privateKey: Uint8Array, payload: SolPayload): string {
  // 签名盖在 message 前面。首字节 1 是 compact-u16 的签名数量，教学范围里小于 128 就一个字节。
  const message = fromB64(payload.message);
  const signature = ed25519.sign(message, privateKey);
  const out = new Uint8Array(1 + signature.length + message.length);
  out[0] = 1;
  out.set(signature, 1);
  out.set(message, 1 + signature.length);
  return toB64(out);
}

// 对 txID 做 secp256k1 签名，恢复字节是 27 加 recovery。
function signTron(privateKey: Uint8Array, payload: TronPayload): string {
  // 签的是 txID，不是整段 JSON。恢复字节用 27+rec，和 TronWeb 的 65 字节签名一致。
  const signature = secp256k1.sign(hexToBytes(payload.txID), privateKey, { lowS: true });
  const compact = signature.toCompactRawBytes();
  const full = new Uint8Array(65);
  full.set(compact, 0);
  full[64] = 27 + signature.recovery;
  return JSON.stringify({ ...payload.transaction, signature: [bytesToHex(full)] });
}

// 把字节编成 base64。
function toB64(bytes: Uint8Array): string {
  let text = "";
  for (const byte of bytes) text += String.fromCharCode(byte);
  return btoa(text);
}

// 把 base64 解回字节。
function fromB64(value: string): Uint8Array {
  const text = atob(value);
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i);
  return out;
}
