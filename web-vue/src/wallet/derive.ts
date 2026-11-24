// 从助记词派生五条链的地址。私钥只留在调用方内存里，这个文件不写存储、也不发网络请求。
import { generateMnemonic, mnemonicToSeedSync, validateMnemonic } from "@scure/bip39";
import { wordlist } from "@scure/bip39/wordlists/english";
import { HDKey } from "@scure/bip32";
import { base58, base58check, bech32 } from "@scure/base";
import { hmac } from "@noble/hashes/hmac";
import { sha256 } from "@noble/hashes/sha256";
import { sha512 } from "@noble/hashes/sha512";
import { ripemd160 } from "@noble/hashes/ripemd160";
import { keccak_256 } from "@noble/hashes/sha3";
import { secp256k1 } from "@noble/curves/secp256k1";
import { ed25519 } from "@noble/curves/ed25519";
import { bytesToHex, utf8ToBytes } from "@noble/hashes/utils";
import type { Chain, Network } from "../types";

export type Derived = {
  chain: Chain;
  address: string;
  path: string;
  privateKey: Uint8Array;
};

const ED_SEED = utf8ToBytes("ed25519 seed");
const tronCodec = base58check(sha256);

// 把助记词收成小写、单词之间单空格。
export function normalizeMnemonic(value: string): string {
  return value.trim().toLowerCase().split(/\s+/).filter(Boolean).join(" ");
}

// 生成 12 个英文助记词。128是强度，强度越高，助记词越长，安全性越高。
export function createMnemonic(): string {
  return generateMnemonic(wordlist, 128);
}

// 检查是不是合法的 BIP-39 英文助记词。
export function mnemonicValid(value: string): boolean {
  return validateMnemonic(normalizeMnemonic(value), wordlist);
}

// 路径里的 ' 表示硬化派生。比特币测试网 coin type 是 1，主网是 0，所以同一助记词地址不同。
export function pathFor(chain: Chain, network: Network, index = 0): string {
  switch (chain) {
    case "bitcoin":
      return network === "mainnet" ? `m/84'/0'/0'/0/${index}` : `m/84'/1'/0'/0/${index}`;
    case "ethereum":
    case "bnb":
      return `m/44'/60'/0'/0/${index}`;
    case "solana":
      // Phantom 习惯：账户序号本身硬化。ed25519 不能做普通 BIP32 派生。
      return `m/44'/501'/${index}'/0'`;
    case "tron":
      return `m/44'/195'/0'/0/${index}`;
  }
}

// 为五条链各派生 index 0 的地址和私钥。
export function deriveAll(mnemonic: string, network: Network): Record<Chain, Derived> {
  return {
    bitcoin: deriveAddress(mnemonic, "bitcoin", network, 0),
    ethereum: deriveAddress(mnemonic, "ethereum", network, 0),
    bnb: deriveAddress(mnemonic, "bnb", network, 0),
    solana: deriveAddress(mnemonic, "solana", network, 0),
    tron: deriveAddress(mnemonic, "tron", network, 0),
  };
}

// 按链、网络和序号从助记词派生一把私钥和对应地址。
export function deriveAddress(mnemonic: string, chain: Chain, network: Network, index = 0): Derived {
  const phrase = normalizeMnemonic(mnemonic);
  if (!validateMnemonic(phrase, wordlist)) {
    throw new Error("助记词无效");
  }
  const path = pathFor(chain, network, index);
  // 根据助记词生成种子。mnemonicToSeedSync是同步方法，会阻塞主线程。
  const seed = mnemonicToSeedSync(phrase);
  if (chain === "solana") {
    // 根据种子和路径派生私钥。
    // 根据种子和路径派生私钥。slip10是SLIP-0010的实现，用于派生ed25519私钥。
    const privateKey = slip10(seed, path);
    // 返回派生结果。
    return { chain, path, privateKey, address: base58.encode(ed25519.getPublicKey(privateKey)) };
  }
  // 根据种子和路径派生私钥。HDKey.fromMasterSeed是BIP32的实现，用于派生私钥。
  const node = HDKey.fromMasterSeed(seed).derive(path);
  if (!node.privateKey || !node.publicKey) throw new Error("派生失败");
  const privateKey = node.privateKey;
  // 如果私钥为空，抛出错误。
  // 如果派生失败，抛出错误。
  // 如果链是比特币，返回派生结果。
  if (chain === "bitcoin") {
    return {
      chain,
      path,
      privateKey,
      address: p2wpkh(node.publicKey, network === "mainnet" ? "bc" : "tb"),
    };
  }
  const evm = evmAddress(privateKey);
  if (chain === "tron") {
    // TRON 地址 = 0x41 + 以太坊那 20 字节，再做 base58check。私钥和 ETH 同一条 secp256k1。
    const payload = new Uint8Array(21);
    payload[0] = 0x41;
    payload.set(evm.bytes, 1);
    return { chain, path, privateKey, address: tronCodec.encode(payload) };
  }
  return { chain, path, privateKey, address: evm.checksum };
}

// 用未压缩公钥的 keccak 后 20 字节得到地址，并做 EIP-55 校验和。
function evmAddress(privateKey: Uint8Array): { bytes: Uint8Array; checksum: string } {
  const uncompressed = secp256k1.getPublicKey(privateKey, false).slice(1);
  const bytes = keccak_256(uncompressed).slice(-20);
  const lower = bytesToHex(bytes);
  const hash = bytesToHex(keccak_256(utf8ToBytes(lower)));
  let checksum = "0x";
  for (let i = 0; i < lower.length; i++) {
    checksum += parseInt(hash[i], 16) >= 8 ? lower[i].toUpperCase() : lower[i];
  }
  return { bytes, checksum };
}

// 由压缩公钥生成原生隔离见证地址。
function p2wpkh(publicKey: Uint8Array, hrp: "bc" | "tb"): string {
  // 隔离见证 v0：witness program 是 HASH160(压缩公钥)，bech32 人类可读部分主网 bc、测试网 tb。
  const program = ripemd160(sha256(publicKey));
  return bech32.encode(hrp, [0, ...bech32.toWords(program)]);
}

// 按 SLIP-0010 从种子派生 ed25519 私钥。
function slip10(seed: Uint8Array, path: string): Uint8Array {
  // SLIP-0010：HMAC-SHA512，key 为 "ed25519 seed"。每层都是硬化索引，数据以 0x00 开头。
  let node = hmac(sha512, ED_SEED, seed);
  let key = node.slice(0, 32);
  let chain = node.slice(32);
  for (const index of hardenedIndexes(path)) {
    const data = new Uint8Array(37);
    data[0] = 0;
    data.set(key, 1);
    data.set(uint32(index), 33);
    node = hmac(sha512, chain, data);
    key = node.slice(0, 32);
    chain = node.slice(32);
  }
  return key;
}

// 解析 ed25519 路径。每一层都必须是硬化索引。
function hardenedIndexes(path: string): number[] {
  if (!path.startsWith("m/")) throw new Error("路径应以 m/ 开头");
  return path
    .slice(2)
    .split("/")
    .map((part) => {
      if (!part.endsWith("'")) throw new Error("ed25519 只支持硬化派生");
      const n = Number(part.slice(0, -1));
      if (!Number.isInteger(n) || n < 0) throw new Error("路径索引不正确");
      return (n + 0x80000000) >>> 0;
    });
}

// 把索引写成 4 字节大端。
function uint32(value: number): Uint8Array {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, value);
  return out;
}
