# Aegis

学习用的多链钱包和支付卡 DApp。默认是沙箱：余额是模拟的，签名用的 EVM chain id 是 `84532026`，广播不会进主网或测试网。不要把存有真实资金的助记词导入这里。

它覆盖这几块：

- 账户、资产、转账、交易记录、助记词备份、离线签名
- Bitcoin、Ethereum、BNB、Solana、TRON，以及 USDT / USDC
- 余额查询、交易状态、Gas 估算、节点探针、错误排查
- 虚拟卡、实体卡、充值、消费、提现、月账单
- Solidity 结算合约：`deposit` / `settle` / `withdraw`

## 架构

浏览器掌握助记词和私钥。Go 服务只保存地址、报价、已签名交易和卡账本。链的差异收在适配器后面。

```text
浏览器
  BIP39 助记词 → BIP32 / SLIP-0010 派生 → 本地签名
        │  只上传地址和已签名交易
        ▼
Go API
  账户 / 资产 / 转账 / 卡账本
        │
        ▼
链适配器  Bitcoin · Ethereum · BNB · Solana · TRON
        │
        ▼
公共节点（仅 MODE=live）

Solidity CardSettlement
  用户存 USDC，运营方按账单结算，用户提走剩余
```

| 目录 | 作用 |
| --- | --- |
| `web/` | React 钱包。保险库、派生、签名、页面 |
| `web-vue/` | 同一套钱包的 Vue 版，端口 5174。助记词和 React 版存在同一个 localStorage 键 |
| `server/` | Go API。适配器、SQLite、卡账本 |
| `server-java/` | 同一套 `/api/v1` 的 Spring Boot 版，按 Controller、Service、DAO 分层。和 Go 共用 `server/data` 里的库，不要同时监听 8080 |
| `contracts/` | Hardhat 合约和测试 |
| `docs/interview.md` | 面试时可以怎么讲 |
| `docs/settlement.md` | 链上结算每一步的操作逻辑和原因 |

## 本地运行

需要 Go 1.22+ 和 Node 20+。开两个终端：

```powershell
cd server
go test ./...
go run ./cmd/api
```

```powershell
cd web
npm install
npm test
npm run dev
```

打开 http://localhost:5173 。

Vue 版接口相同，页面用 Vue 3 重写。助记词保险库和 React 版共用 `aegis.vault.v1`，不要两个页面同时开着同一套钱包：

```powershell
cd web-vue
npm install
npm test
npm run dev
```

打开 http://localhost:5174 。

Java 版是 Spring Boot，接口和 Go 版相同。两边默认都读写 `server/data/aegis.db`，卡号密钥也是同一份 `server/data/card.key`。不要同时启动：端口都是 8080，SQLite 也不能两个进程一起写。本机没有 Maven 时 `build.ps1` 会自己下载：

```powershell
cd server-java
.\build.ps1
.\build.ps1 run
```

合约和本地链。Hardhat 节点要一直开着；它重启后状态清空，需要重新部署：

```powershell
cd contracts
npm install
npm test
npx hardhat node
```

另开一个终端：

```powershell
cd contracts
npm run deploy
```

## 建议走一遍

1. 导入助记词，或创建一套新的并核对 3 个词。密码至少 8 位。
2. 资产页看五条链。沙箱余额由地址稳定生成，刷新不会乱跳。
3. 转账页选 Ethereum，点“填入本钱包的下一个地址”，先“只签名”，再去记录页广播。
4. 开一张虚拟卡，充值、消费、看当月账单。实体卡在激活前不能消费。
5. 链控制台看每条链的健康状态，并翻一遍排查手册。
6. 链上结算页：领取测试 ETH 和 USDC，授权并存入，到卡片里充值并记一笔消费，回到结算页点结算，再提走剩余。每一步谁签名、账怎么对，写在 `docs/settlement.md`。

公开测试助记词（没有资金，所有教程都在用）：

```text
abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about
```

主网地址应为：

| 链 | 路径 | 地址 |
| --- | --- | --- |
| Ethereum / BNB | `m/44'/60'/0'/0/0` | `0x9858EfFD232B4033E47d90003D41EC34EcaEda94` |
| Bitcoin | `m/84'/0'/0'/0/0` | `bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu` |
| Solana | `m/44'/501'/0'/0'` | `HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk` |

## 切到真实节点

在 `server` 目录：

```powershell
$env:MODE="live"
$env:NETWORK="testnet"   # 或 mainnet，主网只做小额
go run ./cmd/api
```

Go 仍用环境变量：`ETH_RPC`、`BNB_RPC`、`SOL_RPC`、`TRON_RPC`、`BTC_API` 换节点，`CARD_KEY` 不设就读写 `server/data/card.key`，`DATA_DIR` 另开一份库。Java 的同一批配置在 `server-java/src/main/resources/application.properties`。接口监听是 `server.address` 和 `server.port`。结算适配器是 `aegis.adapter.settlement`（`http` 或 `web3j`）和 `aegis.adapter.settlement.rpc`。五条链适配器的节点在 `aegis.adapter.testnet.*` 和 `aegis.adapter.mainnet.*`。同名环境变量如果还设着，会盖过文件。

`MODE=sandbox` 时价格是写死的。`MODE=live` 会试着读 CoinGecko，失败就退回静态价格。

## 已知边界

这些是故意留着的，面试时直接说：

- API 没有登录。知道 `accountId` 就能操作这张卡。生产环境要绑会话。
- Solana 的 USDT/USDC 能查余额，转账只做了原生 SOL。SPL 还要关联代币账户。
- Bitcoin 手续费按 P2WPKH 体积估算，并加了 10%。不是精确的虚拟字节。
- 卡号以 `990016` 开头，只存在于这个沙箱。
- 交易历史是这只钱包自己发出的，不是链上全量流水。
1
snow wool denial delay repair admit onion solid confirm room gas pattern