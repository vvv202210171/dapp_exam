# 链上结算：每一步的操作逻辑和原因

这份文档对着现在的代码讲。页面在 `web/src/pages/Settlement.tsx`，桥在 `server/internal/settlement/`，合约在 `contracts/contracts/CardSettlement.sol`。

先记住一件事：这里有两本账，两把钥匙，两条 EVM 链。混在一起会觉得「怎么点了消费，链上余额没变」。

| | 卡账本 | 结算合约 |
| --- | --- | --- |
| 记什么 | 美元的「分」，整数 | USDC 的最小单位，6 位小数 |
| 存在哪 | Go 服务的 SQLite | 本机 Hardhat，chainId `31337` |
| 谁能改 | 开卡、充值、消费、提现的 API | `deposit` / `settle` / `withdraw` |
| 多快 | 请求回来就入账 | 要等交易回执 |
| 防重复 | 卡流水的 `Idempotency-Key` | 账单号 `bytes32`，同一个号不能结算两次 |

钱包平时的转账签名用的是另一条链：沙箱 chainId `84532026`。那笔签名故意不能在主网或测试网重放，广播也不会进真实网络。结算页签出来的交易 chainId 是 `31337`，只会进本机 Hardhat。

## 三方各拿什么

```text
浏览器                         Go API                         本机 Hardhat
助记词、以太坊私钥              只有地址和已签名交易            部署账户已解锁
  │ 签 approve / deposit / withdraw                              │
  │──────────────── 未签名报价 ──────────────▶│                  │
  │◀────────────── EIP-1559 payload ─────────│                  │
  │ 小狐狸弹窗确认，插件自己广播                                      │
  │                                                            │                  │
  │                                            │                          │
  │ 点「结算」时不签名                            │── eth_sendTransaction ─────▶│
  │                                            │   from = 部署账户          │ settle()
```

用户私钥留在浏览器，是因为谁拿到私钥谁就能转走资产。服务器只保存地址、报价和已经签好的交易。已签名交易本身谁都能广播，所以它按现金看待，但服务器仍然签不出一笔新的用户交易。

运营方不把私钥放进 Go 进程。本地用 `npx hardhat node` 时，部署账户在节点里是解锁的，服务端用 `eth_sendTransaction` 指定 `from` 为这个账户。Hardhat 替它签名。生产里这一步应换成 KMS 或多签，不能靠「节点里躺着一把解锁的私钥」。

结算 RPC 只接受 `127.0.0.1`、`localhost`、`::1`，并且 chainId 必须是 `31337`。只查 chainId 挡不住一个伪装成 31337 的远端节点，所以主机名也要是本机。

## 先把四个进程开起来

顺序有关。Hardhat 是内存链，进程一停，合约、余额、nonce 都没了。部署脚本把新地址写进 `contracts/deployments/local.json`。API 每次请求再读这个文件，所以重新部署后不用重启 API，刷新页面即可。节点本身没开，或地址上已经没有字节码，页面会显示未连接和下一句该执行的命令。

```powershell
cd contracts
npx hardhat node
```

```powershell
cd contracts
npm run deploy
```

```powershell
cd server
go run ./cmd/api
```

```powershell
cd web
npm run dev
```

打开 http://localhost:5173 ，导入公开测试助记词后进「链上结算」。这组词没有真实资金，所有教程都在用：

```text
abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about
```

部署文件里会有四样东西：`chainId`、`rpc`、`settlement`（结算合约）、`usdc`（测试币）、`operator`（Hardhat 账户 0，地址固定是 `0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266`）。测试币是 `MockUSDC`，6 位小数，谁都可以 `mint`。正式 USDC 没有这个方法，所以「领取测试币」只存在于这条本地链。

## 打开页面时

页面加载会请求：

```http
GET /api/v1/accounts/{accountId}/settlement
```

服务端做两件独立的事。

第一件不依赖节点：从 SQLite 查出这个账户所有 `kind = spend` 的卡流水，再左连接 `settlements` 表。有交易哈希的记成已结算。节点没开，这张消费列表仍然在，因为卡账本本来就不在链上。

第二件才问链：读部署文件，核对 chainId 和本机地址，用 `eth_getCode` 看结算合约还在不在。在的话再读四笔余额：

| 页面上的字 | 链上对应 | 调用 |
| --- | --- | --- |
| ETH | 你的地址上的原生币，用来付 gas | `eth_getBalance` |
| 钱包 USDC | 测试币合约里你的 `balanceOf` | 还没存进结算合约 |
| 已授权 | 你对结算合约的 `allowance` | 决定 `deposit` 能不能划走币 |
| 合约里可用 | `available(你, USDC)` | 存进来、还没被结算或提走的部分 |

节点不通时 HTTP 仍是 200，`online` 为 false，`hint` 写下一步。这样页面可以告诉你「先开节点」，而不是整页报错把卡流水也藏掉。

## 第 1 步：领取测试 ETH 和 USDC

按钮调用：

```http
POST /api/v1/accounts/{accountId}/settlement/faucet
```

浏览器不签名。服务端用运营方账户连发两笔：

1. 向你的以太坊地址转 1 ETH。
2. 调用测试币的 `mint(你, 100_000_000)`，也就是 100 USDC。

原因：公开助记词派生出来的地址在 Hardhat 上是空的。没有 ETH，后面的 `eth_estimateGas` 会因 gas 不够失败。没有 USDC，`deposit` 无币可划。这两笔都是教学用的水龙头。正式环境里，用户自己的钱包里已经有 ETH 和 USDC，不存在这一步。

Hardhat 账户 0 自带大量测试 ETH，所以它付得起 gas，也能作为 `mint` 的调用者。两笔都等回执成功才返回。本地节点自动出块，通常一次请求里就能确认。

## 第 2 步：授权，`approve`

ERC-20 的规则是：合约不能直接从你的钱包拿币。你要先对代币合约说「结算合约最多可以划走多少」。这就是 `approve(spender, amount)`。

点「授权并存入」时，页面按同一个金额连做两轮「报价 → 小狐狸确认」。第一轮是授权。小狐狸用 `eth_sendTransaction` 自己签名并广播，页面不再用内存里的私钥签这三笔。

```http
POST /api/v1/accounts/{accountId}/settlement/prepare
{ "action": "approve", "amount": "100" }
```

服务端把 `"100"` 按 6 位小数收成整数 `100000000`，编码 calldata，目标是 USDC 合约，不是结算合约。然后向节点要一份 EIP-1559 报价：

- `eth_estimateGas`，再乘 1.2。估算经常偏低，留出余量避免上链后 out of gas。
- `eth_gasPrice` 和 `eth_maxPriorityFeePerGas` 合成 `maxFeePerGas`。
- nonce 用 `pending`，这样上一笔还在内存池时不会拿到已经用过的号。
- `eth_chainId` 写进 payload。这里必须是 `31337`。

报价里没有私钥。页面把 `to`、`data`、gas 和 nonce 交给小狐狸。小狐狸当前账户必须等于这个钱包的以太坊地址，否则存进去的 `available` 对不上后来要结算的卡。chainId 必须是 `31337`，页面会帮你切到 Hardhat。你在弹窗里拒绝时，页面停在这一步，不会继续存入。

授权成功后，USDC 合约里你对结算合约的 allowance 增加，币还在你的钱包里。小狐狸的代币列表不会自动出现本地测试币，页面上的「在小狐狸里显示 USDC」会调用 `wallet_watchAsset` 把它加进去。

`MockUSDC` 会在真正划转时扣减 allowance。所以每存一次，通常都要重新授权这一次的金额。无限授权能少签一笔，但授权额度被盗就能把币转走，这里用刚好够这次存入的额度。

## 第 3 步：存入，`deposit`

第二轮报价的 `action` 是 `deposit`。calldata 是 `deposit(USDC地址, 金额)`，交易的 `to` 换成结算合约。

合约里的顺序：

1. 金额不能为 0，代币必须在白名单里。部署时只把这张 MockUSDC 放进去了。
2. 用 `transferFrom` 把币从你的地址划进合约。返回空或者 `true` 都算成功，用来兼容 USDT 这种成功时不返回 bool 的代币。
3. 划转成功后才 `available[你][USDC] += amount`，发出 `Deposited`。

先划币再记账，是因为币没进来就不能把账加上。整个函数包着重入锁：进入时把锁从 1 改成 2，返回前改回 1。用 1 和 2 而不是布尔值，是为了避免未初始化的存储槽被当成「已经锁上」。

这一步之后页面上「钱包 USDC」下降，「合约里可用」上升。结算页上的存入只把币放进合约，不改卡余额。给卡加钱走卡片页的「划入 USDC 并入账」：小狐狸先存入，服务端核对交易回执后才加同样的美元。

## 第 4 步：在卡片里记账

到「卡片」开一张虚拟卡，点「划入 USDC 并入账」充 100 美元。小狐狸会把 100 USDC 划进结算合约；确认后卡余额加 100.00。授权不足或签名被拒绝时，卡余额不变。然后再记一笔星巴克消费 40 美元。

这些请求打到卡的 API，不打到链：

```http
POST /api/v1/cards/{cardId}/topup
POST /api/v1/cards/{cardId}/spend
```

卡服务把金额收成 int64 的「分」。100 美元是 10000 分，40 美元是 4000 分。同一把 `Idempotency-Key` 再提交一次，返回第一次的流水，不会扣第二次。虚拟卡创建出来就是 `active`。实体卡在激活前可以充值，不能消费。

消费先落在数据库里，是因为持卡人刷卡时不能等一笔链上交易。企业里的发卡系统也是这样：授权和入账走自己的账本，链上或银行的清算是后一步，用账单号对上。

所以此刻：

- 卡可用余额少了 40 美元。
- 结算页「待结算的消费」多了一行，金额 `$40.00`，链上单位显示 `40`。
- 合约里的 `available` 还是你刚才存入的数。

1 分 = 10000 个 USDC 最小单位，因为 1 美元 = 100 分，也等于 1_000_000 个 6 位小数的最小单位。40 美元 = 4000 分 = 40_000_000 个最小单位 = 40 USDC。页面上的 `units` 用十进制字符串显示，末尾的 0 会去掉，所以你看到的是 `40`，不是整数 `40000000`。

## 第 5 步：运营方结算，`settle`

对那一行点「结算」。请求是：

```http
POST /api/v1/accounts/{accountId}/settlement/settle
{ "entryId": "这笔消费流水的 id" }
```

浏览器仍然不签名。服务端的检查顺序：

1. 这笔流水必须是当前账户的消费。别的账户的流水按不存在处理。
2. 本地 `settlements` 表里已经有交易哈希，就直接返回那次结果，不再发交易。
3. 把「分」乘 10000，得到要结算的 USDC 最小单位。
4. `eth_call` 读 `available`。不够就返回「链上可用余额不够」，提示先存入。卡里那 40 美元不会自动变成链上的币。
5. 账单号是流水 id 的 SHA-256，得到 32 字节。同一笔流水永远得到同一个号。
6. 用运营方账户调用 `settle(你的地址, USDC, 金额, 账单号)`。

合约里：

1. 调用者必须在 `operators` 里。部署时 owner 就是第一个运营方，所以 Hardhat 账户 0 可以调。
2. 这个账单号用过就回滚 `DuplicateRef`。服务端如果看到这个错误，会把本地记录写成已结算，避免按钮永远可点。
3. 先把 `available` 减掉，再把代币 `transfer` 给调用者，也就是运营方。
4. 同样先改账再转币，并套着同一把重入锁。

钱打给运营方，是因为这笔消费已经在卡账本里记成「商户收了 40 美元」。链上这一步是清算：把用户预存的 USDC 按账单划给收单方。它不是退款，也不是再给用户打一笔。

结算成功后，`settlements` 表记下 `entry_id → tx_hash`。页面把这一行标成已结算。合约里的可用余额减少相应的 USDC，运营方的 USDC 余额增加。卡账本不会因为链上成功再改一次。两本账用账单号对上，而不是互相覆盖。

## 第 6 步：提走还没结算的部分，`withdraw`

结算页的「提现」和卡片页的「提现」不是一回事。

| | 卡片页提现 | 结算页提现 |
| --- | --- | --- |
| 动哪本账 | 卡的美元余额，另扣 1% 手续费 | 合约里还没被 `settle` 掉的 USDC |
| 谁签名 | 不需要链上签名 | 你的以太坊私钥 |
| 币去哪 | 只是卡账本上的一笔记账 | USDC 回到你的钱包地址 |

点结算页的「提现」会报价 `withdraw(USDC, 金额)`，签名和广播的路径与存入相同。

合约先减去 `available`，再把代币转回 `msg.sender`。减账在转账之前。测试里有一个恶意代币，会在 `transfer` 里回头再调 `withdraw`。锁还关着，第二次进入直接回滚，用户原来的余额保持不动。

沿用上面的数字：存入 100，结算 40，再提现 60。结束后 `available` 是 0，钱包里回到 60 USDC，运营方拿到 40 USDC。你付 gas 用掉了一点 ETH，所以 ETH 会略少于水龙头打来的 1。

如果先把剩余提走，再去结算更早的消费，链上余额不够，结算会失败。顺序是：先保证合约里的可用余额盖住要结的消费，再提走剩下的。

## 一次点击在代码里经过哪里

以「授权并存入」为例。

```text
Settlement.tsx  signAndSend("approve")
  → POST .../settlement/prepare
      settlement.Service.Prepare
        编码 approve calldata
        RPC.Quote：estimateGas、gas、nonce、chainId
  → 小狐狸 eth_sendTransaction
      插件签名并广播，chainId 31337
  → 页面轮询 eth_getTransactionReceipt
  → signAndSend("deposit")    上一笔回执成功后才开始，nonce 已经前进
  → GET .../settlement         刷新四笔余额和消费列表
```

「结算」不经过小狐狸。它从 `store.SpendForAccount` 读流水，用 `RPC.SendFrom` 让节点以运营方身份发 `eth_sendTransaction`。

## 失败时先看哪一句

| 你看到的 | 含义 | 下一步 |
| --- | --- | --- |
| 还没有部署文件 | `local.json` 不存在 | `npm run deploy` |
| 本地结算链没有打开 | 连不上 `127.0.0.1:8545` | 重新 `npx hardhat node`，再部署 |
| 结算合约不在这个地址上 | 节点重启过，旧地址已经是空账户 | 重新部署，刷新页面 |
| 余额不够支付 gas | 你的地址上没有 ETH | 先点领取测试币 |
| 链上可用余额不够结算 | 卡消费的金额大于 `available` | 先存入足够的 USDC |
| 合约执行被回滚 | 没授权、账单号用过、或代币划转失败 | 看错误里的 hint，再对一下 allowance 和账单状态 |
| Nonce 过低 | 用旧报价签了已经用掉的 nonce | 重新点按钮。页面每次现要报价 |

## 这样设计是为了能讲清楚边界

刷卡必须快，所以消费先写卡账本。清算必须能对账、能防重复，所以链上用账单号和 `available`。用户资产的钥匙不能进服务器，所以存入和提现是浏览器签名。运营方的清算不能拿用户的钥匙去做，所以 `settle` 是另一条身份。两本账不自动互相改写，是为了你能指出「哪一笔卡消费对应哪一笔链上结算」，而不是假装一点消费就已经上链。

生产里还缺这些，面试时直接说：

- API 没有登录。知道 `accountId` 就能操作这张卡和触发结算。
- 运营方签名依赖本机节点里解锁的账户，不是 KMS 或多签。
- 没有索引器。链上事件没有反向改卡账本。对账靠本地 `settlements` 表和账单号。
- 广播接口收到已签名交易后就提交，没有再解码核对 `to` 和函数选择器。本地链把这个风险圈在 31337 里。
- 水龙头的 `mint` 只存在于测试币。正式 USDC 要用户自己持有。
