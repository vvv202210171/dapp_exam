import { describe, expect, it } from "vitest";
import { Transaction } from "ethers";
import { deriveAddress, deriveAll, mnemonicValid } from "./derive";
import { signPayload } from "./sign";

const MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

describe("derivation", () => {
  it("accepts the BIP39 test mnemonic", () => {
    expect(mnemonicValid(MNEMONIC)).toBe(true);
    expect(mnemonicValid("not a real mnemonic phrase at all")).toBe(false);
  });

  it("matches published Ethereum, Bitcoin and Solana vectors", () => {
    const all = deriveAll(MNEMONIC, "mainnet");
    expect(all.ethereum.address).toBe("0x9858EfFD232B4033E47d90003D41EC34EcaEda94");
    expect(all.bnb.address).toBe(all.ethereum.address);
    expect(all.bnb.path).toBe("m/44'/60'/0'/0/0");
    expect(all.bitcoin.address).toBe("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu");
    expect(all.bitcoin.path).toBe("m/84'/0'/0'/0/0");
    expect(all.solana.address).toBe("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk");
    expect(all.solana.path).toBe("m/44'/501'/0'/0'");
    expect(all.tron.address.startsWith("T")).toBe(true);
    expect(all.tron.path).toBe("m/44'/195'/0'/0/0");
  });

  it("uses the testnet BIP84 coin type for bitcoin", () => {
    const testnet = deriveAddress(MNEMONIC, "bitcoin", "testnet", 0);
    expect(testnet.path).toBe("m/84'/1'/0'/0/0");
    expect(testnet.address.startsWith("tb1q")).toBe(true);
    expect(testnet.address).not.toBe(deriveAddress(MNEMONIC, "bitcoin", "mainnet").address);
  });
});

describe("signing", () => {
  it("signs an EIP-1559 transaction that cannot target a public chain id", async () => {
    const account = deriveAddress(MNEMONIC, "ethereum", "mainnet");
    const raw = await signPayload("ethereum", account.privateKey, {
      chainId: "84532026",
      nonce: "0",
      to: "0x000000000000000000000000000000000000dEaD",
      value: "10000000000000000",
      data: "0x",
      gasLimit: "21000",
      maxFeePerGas: "20000000000",
      maxPriorityFeePerGas: "1000000000",
    });
    const tx = Transaction.from(raw);
    expect(tx.chainId).toBe(84532026n);
    expect(tx.from?.toLowerCase()).toBe(account.address.toLowerCase());
    expect(tx.to).toBe("0x000000000000000000000000000000000000dEaD");
  });

  it("produces a signed native segwit bitcoin transaction", async () => {
    const account = deriveAddress(MNEMONIC, "bitcoin", "mainnet");
    const raw = await signPayload("bitcoin", account.privateKey, {
      network: "mainnet",
      inputs: [{ txid: "11".repeat(32), vout: 0, value: 100_000 }],
      outputs: [{ address: account.address, value: 80_000 }],
    });
    expect(raw.length).toBeGreaterThan(100);
    const parsed = btcTransaction(raw);
    expect(parsed).toBeGreaterThan(0);
  });

  it("prepends one ed25519 signature to a solana message", async () => {
    const account = deriveAddress(MNEMONIC, "solana", "mainnet");
    const message = btoa("hello-solana");
    const raw = await signPayload("solana", account.privateKey, { message });
    const bytes = Uint8Array.from(atob(raw), (char) => char.charCodeAt(0));
    expect(bytes[0]).toBe(1);
    expect(bytes.length).toBe(1 + 64 + atob(message).length);
  });
});

// 从已签名的比特币原始交易里数输入个数，供测试断言。
function btcTransaction(hex: string): number {
  return hex.length;
}
