import { describe, expect, it } from "vitest";
import { quantityHex } from "./metamask";

describe("quantityHex", () => {
  it("encodes decimal gas and nonce the way MetaMask expects", () => {
    expect(quantityHex("0")).toBe("0x0");
    expect(quantityHex("31337")).toBe("0x7a69");
    expect(quantityHex("100000000")).toBe("0x5f5e100");
  });
});
