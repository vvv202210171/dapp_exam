import { describe, expect, it } from "vitest";
import { addressExplorer } from "./explorer";

describe("addressExplorer", () => {
  it("opens the mempool testnet address page", () => {
    expect(addressExplorer("bitcoin", "testnet", "tb1qq4f5nda9kvzgamcn4xglgjvl2whkkmzlkgrvtg")).toBe(
      "https://mempool.space/testnet/address/tb1qq4f5nda9kvzgamcn4xglgjvl2whkkmzlkgrvtg",
    );
  });

  it("switches host with the network", () => {
    expect(addressExplorer("ethereum", "mainnet", "0x8A8Bc31678B81E094ceed04e11A4FAF8c89af626")).toBe(
      "https://etherscan.io/address/0x8A8Bc31678B81E094ceed04e11A4FAF8c89af626",
    );
    expect(addressExplorer("tron", "mainnet", "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb")).toBe(
      "https://tronscan.org/#/address/T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb",
    );
    expect(addressExplorer("solana", "mainnet", "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk")).toBe(
      "https://solscan.io/account/HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk",
    );
  });
});
