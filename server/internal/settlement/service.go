// 卡账本和链上结算合约之间的桥。用户交易由浏览器签名，运营方交易由本地节点的解锁账户发送。
package settlement

import (
	"context"
	"encoding/json"
	"math/big"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"aegis/internal/domain"
	"aegis/internal/platform/fault"
	"aegis/internal/platform/httpx"
	"aegis/internal/store"
)

// 本地 Hardhat 的 chainId。这条通道拒绝连到别的链，避免误把运营方交易打到公网。
const localChainID int64 = 31337

type Service struct {
	store *store.Store
	http  *httpx.Client
}

// 创建结算服务。部署文件可以晚于进程出现，每次请求再读。
func New(st *store.Store, http *httpx.Client) *Service {
	return &Service{store: st, http: http}
}

type Deployment struct {
	ChainID    int64  `json:"chainId"`
	RPC        string `json:"rpc"`
	Settlement string `json:"settlement"`
	USDC       string `json:"usdc"`
	Operator   string `json:"operator"`
}

type Spend struct {
	EntryID   string       `json:"entryId"`
	CardID    string       `json:"cardId"`
	Last4     string       `json:"last4"`
	Merchant  string       `json:"merchant,omitempty"`
	Amount    domain.Cents `json:"amount"`
	Units     string       `json:"units"`
	Settled   bool         `json:"settled"`
	TxHash    string       `json:"txHash,omitempty"`
	CreatedAt time.Time    `json:"createdAt"`
}

type View struct {
	Online     bool    `json:"online"`
	Hint       string  `json:"hint,omitempty"`
	ChainID    string  `json:"chainId,omitempty"`
	RPC        string  `json:"rpc,omitempty"`
	Settlement string  `json:"settlement,omitempty"`
	USDC       string  `json:"usdc,omitempty"`
	Operator   string  `json:"operator,omitempty"`
	User       string  `json:"user,omitempty"`
	Eth        string  `json:"eth,omitempty"`
	WalletUSDC string  `json:"walletUsdc,omitempty"`
	Allowance  string  `json:"allowance,omitempty"`
	Available  string  `json:"available,omitempty"`
	Spends     []Spend `json:"spends"`
}

type Quote struct {
	Action   string            `json:"action"`
	Amount   string            `json:"amount"`
	Payload  domain.EVMPayload `json:"payload"`
	Fee      domain.Fee        `json:"fee"`
	Warnings []string          `json:"warnings"`
}

type Receipt struct {
	Hash   string `json:"hash"`
	Status string `json:"status"`
}

type FaucetResult struct {
	EthTx  string `json:"ethTx"`
	UsdcTx string `json:"usdcTx"`
	Eth    string `json:"eth"`
	Usdc   string `json:"usdc"`
}

type session struct {
	dep Deployment
	rpc *RPC
}

// 返回链上余额、合约地址，以及还没结算的卡消费。节点没开时仍返回流水。
func (s *Service) Status(ctx context.Context, accountID string) (View, error) {
	if _, err := s.store.GetAccount(ctx, accountID); err != nil {
		return View{}, err
	}
	view := View{Spends: []Spend{}}
	spends, err := s.spends(ctx, accountID)
	if err != nil {
		return View{}, err
	}
	view.Spends = spends
	if user, err := s.userAddress(ctx, accountID); err == nil {
		view.User = user
	}
	sess, err := s.open(ctx)
	if err != nil {
		view.Hint = hintOf(err)
		if dep, loadErr := loadDeployment(); loadErr == nil {
			view.RPC = dep.RPC
			view.Settlement = dep.Settlement
			view.USDC = dep.USDC
			view.Operator = dep.Operator
			view.ChainID = big.NewInt(dep.ChainID).String()
		}
		return view, nil
	}
	view.Online = true
	view.ChainID = big.NewInt(sess.dep.ChainID).String()
	view.RPC = sess.dep.RPC
	view.Settlement = sess.dep.Settlement
	view.USDC = sess.dep.USDC
	view.Operator = sess.dep.Operator
	if view.User == "" {
		view.Hint = "这个账户还没有以太坊地址。重新解锁钱包，让地址同步到服务器。"
		return view, nil
	}
	eth, err := sess.rpc.NativeBalance(ctx, view.User)
	if err != nil {
		return View{}, err
	}
	walletData, err := BalanceOfData(view.User)
	if err != nil {
		return View{}, err
	}
	wallet, err := sess.rpc.CallUint(ctx, sess.dep.USDC, walletData)
	if err != nil {
		return View{}, err
	}
	allowData, err := AllowanceData(view.User, sess.dep.Settlement)
	if err != nil {
		return View{}, err
	}
	allowance, err := sess.rpc.CallUint(ctx, sess.dep.USDC, allowData)
	if err != nil {
		return View{}, err
	}
	availData, err := AvailableData(view.User, sess.dep.USDC)
	if err != nil {
		return View{}, err
	}
	available, err := sess.rpc.CallUint(ctx, sess.dep.Settlement, availData)
	if err != nil {
		return View{}, err
	}
	view.Eth = domain.FormatUnits(eth, 18)
	view.WalletUSDC = domain.FormatUnits(wallet, 6)
	view.Allowance = domain.FormatUnits(allowance, 6)
	view.Available = domain.FormatUnits(available, 6)
	return view, nil
}

// 给 approve、deposit 或 withdraw 报价。返回的 payload 交给浏览器签名。
func (s *Service) Prepare(ctx context.Context, accountID, action, amount string) (Quote, error) {
	sess, err := s.open(ctx)
	if err != nil {
		return Quote{}, err
	}
	user, err := s.userAddress(ctx, accountID)
	if err != nil {
		return Quote{}, err
	}
	units, err := domain.ParseUnits(amount, 6)
	if err != nil || units.Sign() <= 0 {
		return Quote{}, domain.E("INVALID_ARGUMENT", "金额不正确", "按 USDC 填写，最多 6 位小数。例如 25 表示 25 USDC。")
	}
	var to, data string
	switch action {
	case "approve":
		to = sess.dep.USDC
		data, err = ApproveData(sess.dep.Settlement, units)
	case "deposit":
		to = sess.dep.Settlement
		data, err = DepositData(sess.dep.USDC, units)
	case "withdraw":
		to = sess.dep.Settlement
		data, err = WithdrawData(sess.dep.USDC, units)
	default:
		return Quote{}, domain.E("INVALID_ARGUMENT", "不支持这个动作", "用户只能授权、存入或提现。结算由服务端的运营方账户发送。")
	}
	if err != nil {
		return Quote{}, err
	}
	payload, fee, err := sess.rpc.Quote(ctx, user, to, data)
	if err != nil {
		return Quote{}, err
	}
	return Quote{
		Action:  action,
		Amount:  domain.FormatUnits(units, 6),
		Payload: payload,
		Fee:     fee,
		Warnings: []string{
			"签名在浏览器完成，服务器看不到私钥。",
			"chainId 是 31337。这笔交易签给本地链，不能在主网重放。",
		},
	}, nil
}

// 广播一笔已经签好的用户交易，并等待回执。
func (s *Service) Broadcast(ctx context.Context, accountID, signed string) (Receipt, error) {
	if _, err := s.store.GetAccount(ctx, accountID); err != nil {
		return Receipt{}, err
	}
	sess, err := s.open(ctx)
	if err != nil {
		return Receipt{}, err
	}
	if strings.TrimSpace(signed) == "" {
		return Receipt{}, domain.E("INVALID_ARGUMENT", "缺少已签名交易", "先向结算接口报价，再用钱包里的以太坊私钥签名。")
	}
	hash, err := sess.rpc.SendRaw(ctx, signed)
	if err != nil {
		return Receipt{}, err
	}
	status, err := sess.rpc.Wait(ctx, hash)
	if err != nil {
		return Receipt{}, err
	}
	return Receipt{Hash: hash, Status: status}, nil
}

// 从运营方账户打 1 ETH 和 100 测试 USDC。只为本地练习准备 gas 和代币。
func (s *Service) Faucet(ctx context.Context, accountID string) (FaucetResult, error) {
	sess, err := s.open(ctx)
	if err != nil {
		return FaucetResult{}, err
	}
	user, err := s.userAddress(ctx, accountID)
	if err != nil {
		return FaucetResult{}, err
	}
	oneEth, _ := new(big.Int).SetString("1000000000000000000", 10)
	ethTx, err := sess.rpc.SendFrom(ctx, sess.dep.Operator, user, quantityHex(oneEth), "0x")
	if err != nil {
		return FaucetResult{}, explainOperator(err)
	}
	if _, err := sess.rpc.Wait(ctx, ethTx); err != nil {
		return FaucetResult{}, err
	}
	mint, err := MintData(user, big.NewInt(100_000_000))
	if err != nil {
		return FaucetResult{}, err
	}
	usdcTx, err := sess.rpc.SendFrom(ctx, sess.dep.Operator, sess.dep.USDC, "", mint)
	if err != nil {
		return FaucetResult{}, explainOperator(err)
	}
	if _, err := sess.rpc.Wait(ctx, usdcTx); err != nil {
		return FaucetResult{}, err
	}
	return FaucetResult{EthTx: ethTx, UsdcTx: usdcTx, Eth: "1", Usdc: "100"}, nil
}

// 核对一笔已经确认的 deposit：发送人、合约、代币和金额都要与这次充值一致。
func (s *Service) ConfirmDeposit(ctx context.Context, accountID, amount, txHash string) error {
	cents, err := domain.ParseCents(amount)
	if err != nil || cents <= 0 {
		return domain.E("INVALID_ARGUMENT", "金额不正确", "金额用美元，最多两位小数，例如 22.30。")
	}
	hash, err := NormalizeTxHash(txHash)
	if err != nil {
		return err
	}
	user, err := s.userAddress(ctx, accountID)
	if err != nil {
		return err
	}
	sess, err := s.open(ctx)
	if err != nil {
		return err
	}
	status, err := sess.rpc.Wait(ctx, hash)
	if err != nil {
		return err
	}
	if status != "confirmed" {
		return domain.E("RPC_TIMEOUT", "存入交易还没有成功回执", "在小狐狸里确认交易已经上链，再给卡入账。")
	}
	from, to, input, err := sess.rpc.Transaction(ctx, hash)
	if err != nil {
		return err
	}
	if !strings.EqualFold(from, user) {
		return domain.E("INVALID_ARGUMENT", "这笔存入不是当前账户发出的", "小狐狸要选这个钱包的以太坊地址。")
	}
	if !strings.EqualFold(to, sess.dep.Settlement) {
		return domain.E("INVALID_ARGUMENT", "这笔交易没有打到结算合约", "只有 deposit 进结算合约才能给卡入账。")
	}
	token, got, err := ParseDeposit(input)
	if err != nil {
		return err
	}
	if !strings.EqualFold(token, sess.dep.USDC) {
		return domain.E("INVALID_ARGUMENT", "存入的不是结算用的 USDC", "")
	}
	if got.Cmp(CentsToUnits(cents)) != 0 {
		return domain.E("INVALID_ARGUMENT", "链上存入金额和要入账的美元不一致", "卡加多少钱，合约里就要存入多少 USDC。")
	}
	return nil
}

// 把一笔卡消费结算到链上。调用者是运营方账户，浏览器不签名。
func (s *Service) Settle(ctx context.Context, accountID, entryID string) (Receipt, error) {
	sess, err := s.open(ctx)
	if err != nil {
		return Receipt{}, err
	}
	user, err := s.userAddress(ctx, accountID)
	if err != nil {
		return Receipt{}, err
	}
	row, err := s.store.SpendForAccount(ctx, accountID, entryID)
	if err != nil {
		return Receipt{}, err
	}
	if row.TxHash != "" {
		return Receipt{Hash: row.TxHash, Status: "confirmed"}, nil
	}
	units := CentsToUnits(int64(row.Entry.Amount))
	if units.Sign() <= 0 {
		return Receipt{}, domain.E("INVALID_ARGUMENT", "这笔消费金额不正确", "")
	}
	availData, err := AvailableData(user, sess.dep.USDC)
	if err != nil {
		return Receipt{}, err
	}
	available, err := sess.rpc.CallUint(ctx, sess.dep.Settlement, availData)
	if err != nil {
		return Receipt{}, err
	}
	if available.Cmp(units) < 0 {
		return Receipt{}, domain.E("INSUFFICIENT_FUNDS", "链上可用余额不够结算这笔消费", "先在结算页把 USDC 存进合约。存入额要盖住还没结算的消费。")
	}
	ref := BillRef(entryID)
	data, err := SettleData(user, sess.dep.USDC, units, ref)
	if err != nil {
		return Receipt{}, err
	}
	hash, err := sess.rpc.SendFrom(ctx, sess.dep.Operator, sess.dep.Settlement, "", data)
	if err != nil {
		if strings.Contains(err.Error(), "DuplicateRef") {
			_ = s.store.PutSettlement(ctx, entryID, accountID, "already-on-chain", units.String())
			return Receipt{Hash: "already-on-chain", Status: "confirmed"}, nil
		}
		return Receipt{}, explainOperator(err)
	}
	status, err := sess.rpc.Wait(ctx, hash)
	if err != nil {
		return Receipt{}, err
	}
	if status == "confirmed" {
		if err := s.store.PutSettlement(ctx, entryID, accountID, hash, units.String()); err != nil {
			return Receipt{}, err
		}
	}
	return Receipt{Hash: hash, Status: status}, nil
}

// 读部署文件、核对 chainId，并确认结算合约还在这个地址上。
func (s *Service) open(ctx context.Context) (session, error) {
	dep, err := loadDeployment()
	if err != nil {
		return session{}, err
	}
	if dep.ChainID != localChainID {
		return session{}, domain.E("INVALID_ARGUMENT", "结算部署文件里的 chainId 不是 31337", "只连接本地 Hardhat。不要把这份配置指到主网或测试网。")
	}
	if !loopback(dep.RPC) {
		return session{}, domain.E("INVALID_ARGUMENT", "结算节点必须在本机", "只接受 127.0.0.1 或 localhost。运营方交易走节点里解锁的账户，不能指到公网 RPC。")
	}
	rpc := newRPC(dep.RPC, s.http)
	id, err := rpc.ChainID(ctx)
	if err != nil {
		return session{}, domain.E("RPC_UNREACHABLE", "本地结算链没有打开", "在 contracts 目录执行 npx hardhat node，另开一个终端执行 npm run deploy，然后刷新。")
	}
	if id.Cmp(big.NewInt(localChainID)) != 0 {
		return session{}, domain.E("INVALID_ARGUMENT", "节点 chainId 不是 31337", "结算通道只接受本地 Hardhat，避免运营方交易被发到公网。")
	}
	code, err := rpc.Code(ctx, dep.Settlement)
	if err != nil {
		return session{}, err
	}
	if code == "" || code == "0x" || code == "0x0" {
		return session{}, domain.E("NOT_FOUND", "结算合约不在部署文件写的地址上", "Hardhat 重启后链是空的。在 contracts 目录重新执行 npm run deploy。")
	}
	return session{dep: dep, rpc: rpc}, nil
}

// 取出这个账户同步过的以太坊地址。用户交易只能从这个地址发出。
func (s *Service) userAddress(ctx context.Context, accountID string) (string, error) {
	acc, err := s.store.GetAccount(ctx, accountID)
	if err != nil {
		return "", err
	}
	for _, addr := range acc.Addresses {
		if addr.Chain == domain.Ethereum && strings.TrimSpace(addr.Address) != "" {
			return addr.Address, nil
		}
	}
	return "", domain.E("INVALID_ARGUMENT", "这个账户没有以太坊地址", "重新解锁钱包。同步接口会把派生地址登记到服务器。")
}

// 把未结算的消费和已经记下的交易哈希拼在一起。
func (s *Service) spends(ctx context.Context, accountID string) ([]Spend, error) {
	rows, err := s.store.AccountSpends(ctx, accountID)
	if err != nil {
		return nil, err
	}
	out := make([]Spend, 0, len(rows))
	for _, row := range rows {
		out = append(out, Spend{
			EntryID:   row.Entry.ID,
			CardID:    row.Entry.CardID,
			Last4:     row.Last4,
			Merchant:  row.Entry.Merchant,
			Amount:    row.Entry.Amount,
			Units:     domain.FormatUnits(CentsToUnits(int64(row.Entry.Amount)), 6),
			Settled:   row.TxHash != "",
			TxHash:    row.TxHash,
			CreatedAt: row.Entry.CreatedAt,
		})
	}
	return out, nil
}

// 结算 RPC 只能打到本机。chainId 检查挡不住一个伪装成 31337 的远端节点。
func loopback(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil {
		return false
	}
	host := strings.Trim(strings.ToLower(u.Hostname()), "[]")
	return host == "127.0.0.1" || host == "localhost" || host == "::1"
}

// 读取部署文件。SETTLEMENT_FILE 和 SETTLEMENT_RPC 可以覆盖默认位置和节点。
func loadDeployment() (Deployment, error) {
	path := os.Getenv("SETTLEMENT_FILE")
	if strings.TrimSpace(path) == "" {
		candidates := []string{
			filepath.Join("..", "contracts", "deployments", "local.json"),
			filepath.Join("contracts", "deployments", "local.json"),
		}
		path = candidates[0]
		for _, candidate := range candidates {
			if _, err := os.Stat(candidate); err == nil {
				path = candidate
				break
			}
		}
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return Deployment{}, domain.E("NOT_FOUND", "还没有结算合约的部署文件", "在 contracts 目录先执行 npx hardhat node，再执行 npm run deploy。")
	}
	var dep Deployment
	if err := json.Unmarshal(raw, &dep); err != nil {
		return Deployment{}, domain.E("INVALID_ARGUMENT", "部署文件不是合法 JSON", "重新执行 npm run deploy。")
	}
	if v := strings.TrimSpace(os.Getenv("SETTLEMENT_RPC")); v != "" {
		dep.RPC = v
	}
	if dep.RPC == "" || dep.Settlement == "" || dep.USDC == "" || dep.Operator == "" {
		return Deployment{}, domain.E("INVALID_ARGUMENT", "部署文件缺少地址", "重新执行 npm run deploy。")
	}
	return dep, nil
}

// 取出给界面看的下一步。
func hintOf(err error) string {
	de := fault.Explain(err)
	if de == nil {
		return ""
	}
	if de.Hint != "" {
		return de.Hint
	}
	return de.Message
}

// 运营方交易失败时，把「账户没解锁」说成下一步。
func explainOperator(err error) error {
	if err == nil {
		return nil
	}
	low := strings.ToLower(err.Error())
	if strings.Contains(low, "unknown account") || strings.Contains(low, "locked") {
		return domain.E("CHAIN_ERROR", "节点没有解锁运营方账户", "本地用 npx hardhat node，部署账户会自动解锁。生产环境应换成 KMS 签名，而不是把运营方私钥放进 API 进程。")
	}
	return err
}
