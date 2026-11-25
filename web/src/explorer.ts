import type { Chain, Network } from "./types";

// 地址页。网络和节点配置一致：测试网分别是比特币 mempool testnet、Sepolia、BSC 测试网、Solana devnet、TRON Shasta。
export function addressExplorer(chain: Chain, network: Network, address: string): string {
  const main = network === "mainnet";
  const id = encodeURIComponent(address);
  switch (chain) {
    case "bitcoin":
      return (main ? "https://mempool.space/address/" : "https://mempool.space/testnet/address/") + id;
    case "ethereum":
      return (main ? "https://etherscan.io/address/" : "https://sepolia.etherscan.io/address/") + id;
    case "bnb":
      return (main ? "https://bscscan.com/address/" : "https://testnet.bscscan.com/address/") + id;
    case "solana":
      return "https://solscan.io/account/" + id + (main ? "" : "?cluster=devnet");
    case "tron":
      return (main ? "https://tronscan.org/#/address/" : "https://shasta.tronscan.org/#/address/") + id;
  }
}
