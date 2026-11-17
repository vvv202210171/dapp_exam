const fs = require("fs");
const path = require("path");
const { ethers } = require("hardhat");

// 部署到本机 Hardhat 节点，并把地址写给 Go 服务。节点重启后状态清空，要重新跑这份脚本。
async function main() {
  const [operator] = await ethers.getSigners();
  const usdc = await (await ethers.getContractFactory("MockUSDC")).deploy();
  await usdc.waitForDeployment();
  const settlement = await (await ethers.getContractFactory("CardSettlement")).deploy(operator.address, [await usdc.getAddress()]);
  await settlement.waitForDeployment();
  const net = await ethers.provider.getNetwork();
  const out = {
    chainId: Number(net.chainId),
    rpc: "http://127.0.0.1:8545",
    settlement: await settlement.getAddress(),
    usdc: await usdc.getAddress(),
    operator: operator.address,
  };
  const dir = path.join(__dirname, "..", "deployments");
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, "local.json"), JSON.stringify(out, null, 2));
  console.log(JSON.stringify(out, null, 2));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
