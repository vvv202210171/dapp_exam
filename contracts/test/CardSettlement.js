const { expect } = require("chai");
const { ethers } = require("hardhat");

describe("CardSettlement", function () {
  async function deploy() {
    const [owner, user, stranger] = await ethers.getSigners();
    const Token = await ethers.getContractFactory("MockUSDC");
    const usdc = await Token.deploy();
    const Settlement = await ethers.getContractFactory("CardSettlement");
    const settlement = await Settlement.deploy(owner.address, [await usdc.getAddress()]);
    await usdc.mint(user.address, 1_000_000n);
    await usdc.connect(user).approve(await settlement.getAddress(), 1_000_000n);
    return { owner, user, stranger, usdc, settlement };
  }

  it("deposits, settles once, and withdraws the remainder", async function () {
    const { owner, user, usdc, settlement } = await deploy();
    const token = await usdc.getAddress();
    await settlement.connect(user).deposit(token, 100_000n);
    expect(await settlement.available(user.address, token)).to.equal(100_000n);

    const ref = ethers.id("bill-1");
    await settlement.connect(owner).settle(user.address, token, 40_000n, ref);
    await expect(settlement.connect(owner).settle(user.address, token, 1n, ref)).to.be.reverted;

    await settlement.connect(user).withdraw(token, 60_000n);
    expect(await settlement.available(user.address, token)).to.equal(0n);
    expect(await usdc.balanceOf(user.address)).to.equal(960_000n);
    expect(await usdc.balanceOf(owner.address)).to.equal(40_000n);
  });

  it("rejects settle from a non-operator and blocks reentrancy", async function () {
    const { owner, user, stranger, usdc, settlement } = await deploy();
    const token = await usdc.getAddress();
    await settlement.connect(user).deposit(token, 10_000n);
    await expect(
      settlement.connect(stranger).settle(user.address, token, 1n, ethers.id("nope")),
    ).to.be.reverted;
    await expect(settlement.connect(stranger).setOperator(stranger.address, true)).to.be.reverted;

    const Mal = await ethers.getContractFactory("MaliciousToken");
    const mal = await Mal.deploy();
    await settlement.connect(owner).setToken(await mal.getAddress(), true);
    await mal.configure(await settlement.getAddress(), user.address);
    await mal.fundSettlement(50n);
    await mal.mint(user.address, 100n);
    await mal.connect(user).approve(await settlement.getAddress(), 100n);
    await settlement.connect(user).deposit(await mal.getAddress(), 100n);
    await mal.setArmed(true);

    await expect(settlement.connect(user).withdraw(await mal.getAddress(), 100n)).to.be.reverted;
    expect(await settlement.available(user.address, await mal.getAddress())).to.equal(100n);
    expect(await settlement.available(await mal.getAddress(), await mal.getAddress())).to.equal(50n);
  });
});
