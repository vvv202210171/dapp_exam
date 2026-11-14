# 面试讲解

用这个项目时，先讲边界，再讲设计。沙箱是为了能离线演示；签名、派生、账本和合约是真的。

## 30 秒

Aegis 是一个非托管钱包加一张支付卡。助记词用 PBKDF2 和 AES-GCM 留在浏览器里，服务器只登记地址。转账分三步：节点报价、本地签名、可选广播。Bitcoin、Ethereum、BNB、Solana、TRON 共用一个适配器接口。卡是另一本账，用整数分和幂等键。链上对应的是 `CardSettlement`，用户存 USDC，运营方按账单号结算，用户提走剩余。

## 为什么私钥不进服务器

已签名交易谁拿到谁就能广播。私钥如果到了服务器，钱包就变成托管。所以同步接口只收 `chain / address / path`。报价返回未签名 payload，签名在 `web/src/wallet/sign.ts`。沙箱的 EVM chain id 固定为 `84532026`，避免演示时签出的交易被拿到 Sepolia 或主网重放。

浏览器做不到真正擦掉字符串。锁定只是丢掉引用。这一点要主动说。

## 派生

一套助记词，五条链五条路径：

| 链 | 路径 | 原因 |
| --- | --- | --- |
| Bitcoin 主网 | `m/84'/0'/0'/0/0` | 原生隔离见证 |
| Bitcoin 测试网 | `m/84'/1'/0'/0/0` | coin type 是 1，地址是 `tb1` |
| Ethereum、BNB | `m/44'/60'/0'/0/0` | EVM 共用 60，所以地址一样 |
| TRON | `m/44'/195'/0'/0/0` | 同样是 secp256k1，地址是 `0x41` + 20 字节再 base58check |
| Solana | `m/44'/501'/0'/0'` | ed25519，走 SLIP-0010，只能硬化派生 |

以太坊地址是 keccak256(非压缩公钥去掉 04) 的后 20 字节，再做 EIP-55。这里的 keccak 是原始 Keccak，不是 NIST SHA3。

USDT 的小数位不是统一的：以太坊和 TRON 是 6，BSC 上的 BSC-USD 是 18。金额用整数最小单位解析，接口上用字符串，避免浮点。

## 转账和 Gas

`Prepare` 做的事：

- EVM：`eth_estimateGas`，再乘 1.2。`eth_gasPrice` 和 `eth_maxPriorityFeePerGas` 组 EIP-1559。nonce 用 `pending`。
- Bitcoin：只花已确认 UTXO，按输入 68 vB、输出 31 vB 估算并留 10%。找零低于 546 sat 就并进矿工费。
- Solana：取最新 blockhash，在服务端拼 legacy message，浏览器只做 ed25519。blockhash 大约一分钟失效。
- TRON：原生转账走 `createtransaction`，TRC20 走 `triggersmartcontract`。浏览器对 `txID` 签名，recovery id 用 27/28，和 TronWeb 一致。

失败不吞掉。超时、429、nonce too low、insufficient funds、revert、账户未激活，都会变成带 `hint` 的错误。链控制台把这些写成排查手册。探针方法是白名单，调用方不能传任意 URL。

交易列表不是链上全量历史。自己的节点按地址倒查很贵，所以本地只记这只钱包发出的交易，再用回执更新状态。全量历史要索引器。

## 卡

金额是 int64 的“分”。充值加余额；消费要求状态是 `active`；提现另扣 1%，最低 1 分，余额要盖住金额加手续费。同一 `Idempotency-Key` 再提交会返回第一次的结果，不会记两笔。

实体卡：`requested → issued → shipped → active`。激活前可以充值，不能消费。虚拟卡创建即 `active`。冻结后不能消费和提现。注销后不能动账。

卡号不落明文。AES-GCM，密钥来自 `CARD_KEY` 或本地文件。生产里这把钥匙应该在 KMS。BIN `990016` 表示这是沙箱卡。

API 目前没有登录。这是学习项目的缺口：生产里卡操作必须绑到登录会话，不能只凭 `accountId`。

## 合约

`CardSettlement` 有三件事：

1. `deposit`：先把代币划进来，再加 `available`。
2. `settle`：运营方结算。同一个 `bytes32` 账单号不能用第二次。先减余额再打币。
3. `withdraw`：用户提走剩余。同样先减余额。

整份合约有重入锁。测试里的 `MaliciousToken` 会在 `transfer` 里再调用 `withdraw`。锁生效时，用户那笔提现整体回滚，两边余额都不变。

沙箱卡账本和这个合约是两本账。页面可以编码 `deposit` calldata，但不会假装已经部署到主网。

## 被追问时

**为什么不用一个大而全的钱包库？**  
派生和签名要能讲清楚。EVM 用 ethers，Bitcoin 用 `@scure/btc-signer`，Solana 和 TRON 的消息在 Go 里组装，浏览器只签哈希或 message。链的 RPC 差异留在适配器。

**节点挂了怎么办？**  
单条链超时不影响其他链的资产返回。错误里有下一步。live 模式可以换 RPC 环境变量。不会在失败时偷偷改成假余额。

**和托管钱包的差别？**  
托管是服务器持有密钥。这里服务器最多持有已签名交易。已签名交易本身就是不记名的，所以离线签名的结果要当现金看待，界面上写了这件事。

**如果做成生产还要补什么？**  
登录和会话、硬件签名或安全元素、自建节点和索引器、SPL 代币账户、UTXO 手续费的精确虚拟字节、PCI 环境下的卡号库、合约的多签运营和链上事件回写卡账本、限额和风控。
