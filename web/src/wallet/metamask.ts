// 小狐狸走 EIP-1193。用户交易由插件签名并广播，页面不再用内存里的私钥签结算交易。

const HARDHAT_CHAIN_ID = "0x7a69";

type EthereumProvider = {
  request: (args: { method: string; params?: unknown }) => Promise<unknown>;
};

export type PreparedCall = {
  to: string;
  value: string;
  data: string;
  gasLimit: string;
  maxFeePerGas: string;
  maxPriorityFeePerGas: string;
  nonce: string;
};

// 取出小狐狸注入的 provider。普通浏览器窗口里没有这个对象。
export function metamaskProvider(): EthereumProvider | null {
  const ethereum = (window as Window & { ethereum?: EthereumProvider }).ethereum;
  return ethereum?.request ? ethereum : null;
}

// 把十进制数量收成小狐狸要的 0x 十六进制。
export function quantityHex(value: string): string {
  const parsed = BigInt(value || "0");
  return `0x${parsed.toString(16)}`;
}

// 请求账户，并切到本机 Hardhat。返回小狐狸当前选中的地址。
export async function connectMetamask(): Promise<string> {
  const provider = requiredProvider();
  const accounts = (await provider.request({ method: "eth_requestAccounts" })) as string[];
  const account = accounts[0];
  if (!account) throw new Error("小狐狸没有返回账户。");
  await ensureHardhat(provider);
  return account;
}

// 把测试 USDC 加进小狐狸的代币列表。本地链不会自动出现这张合约。
export async function watchUsdc(address: string): Promise<void> {
  const provider = requiredProvider();
  await provider.request({
    method: "wallet_watchAsset",
    params: { type: "ERC20", options: { address, symbol: "USDC", decimals: 6 } },
  });
}

// 把服务端报价交给小狐狸签名并广播，返回交易哈希。
export async function sendWithMetamask(from: string, call: PreparedCall): Promise<string> {
  const provider = requiredProvider();
  await ensureHardhat(provider);
  try {
    const hash = await provider.request({
      method: "eth_sendTransaction",
      params: [{
        from,
        to: call.to,
        data: call.data,
        value: quantityHex(call.value),
        gas: quantityHex(call.gasLimit),
        maxFeePerGas: quantityHex(call.maxFeePerGas),
        maxPriorityFeePerGas: quantityHex(call.maxPriorityFeePerGas),
        nonce: quantityHex(call.nonce),
      }],
    });
    if (typeof hash !== "string" || !hash.startsWith("0x")) throw new Error("小狐狸没有返回交易哈希。");
    return hash;
  } catch (err) {
    throw friendly(err);
  }
}

// 轮询小狐狸所连节点上的回执。本地链通常马上就有。
export async function waitForReceipt(hash: string): Promise<void> {
  const provider = requiredProvider();
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    const receipt = await provider.request({ method: "eth_getTransactionReceipt", params: [hash] }) as { status?: string } | null;
    if (receipt?.status === "0x1") return;
    if (receipt?.status === "0x0") throw new Error("小狐狸已广播，但这笔交易执行失败。");
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  throw new Error("小狐狸已广播，回执还没到。在小狐狸的活动里查看这笔交易。");
}

// 没有注入对象时，告诉用户换到装了小狐狸的窗口。
function requiredProvider(): EthereumProvider {
  const provider = metamaskProvider();
  if (!provider) throw new Error("这个窗口里没有小狐狸。用安装了插件的浏览器打开本页。");
  return provider;
}

// 不在 31337 时先切换；小狐狸还不认识这条链就补上本地 RPC。
async function ensureHardhat(provider: EthereumProvider): Promise<void> {
  const chainId = await provider.request({ method: "eth_chainId" });
  if (chainId === HARDHAT_CHAIN_ID) return;
  try {
    await provider.request({
      method: "wallet_switchEthereumChain",
      params: [{ chainId: HARDHAT_CHAIN_ID }],
    });
  } catch (err) {
    const code = (err as { code?: number }).code;
    if (code !== 4902) throw friendly(err);
    await provider.request({
      method: "wallet_addEthereumChain",
      params: [{
        chainId: HARDHAT_CHAIN_ID,
        chainName: "Hardhat",
        nativeCurrency: { name: "Ether", symbol: "ETH", decimals: 18 },
        rpcUrls: ["http://127.0.0.1:8545"],
      }],
    });
  }
}

// 用户在弹窗里点拒绝时，给出能看懂的下一句。
function friendly(err: unknown): Error {
  const code = (err as { code?: number }).code;
  if (code === 4001) return new Error("你在小狐狸里拒绝了这笔交易。");
  if (err instanceof Error) return err;
  return new Error("小狐狸没有完成这笔交易。");
}
