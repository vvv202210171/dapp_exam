// 支付卡账本。虚拟卡创建即可用；实体卡要走申请、制卡、寄出、激活。完整卡号只在创建和显式查看时返回明文。
package service

import (
	"context"
	"crypto/rand"
	"math/big"
	"strings"
	"time"
	"unicode/utf8"

	"aegis/internal/domain"
	"aegis/internal/platform"
	"aegis/internal/store"
)

type CardService struct {
	store *store.Store
	key   []byte
}

// 创建卡服务，密钥只用于加解密卡号。
func NewCard(st *store.Store, key []byte) *CardService {
	return &CardService{store: st, key: key}
}

type IssuedCard struct {
	Card domain.Card `json:"card"`
	PAN  string      `json:"pan"`
	CVV  string      `json:"cvv"`
}

// 开一张虚拟卡或实体卡，并返回一次明文卡号和 CVV。
func (s *CardService) Create(ctx context.Context, accountID, kind, holder string) (IssuedCard, error) {
	kind = strings.TrimSpace(kind)
	if kind != "virtual" && kind != "physical" {
		return IssuedCard{}, domain.E("INVALID_ARGUMENT", "卡类型不正确", "virtual 是虚拟卡，physical 是实体卡。")
	}
	holder = strings.TrimSpace(holder)
	if holder == "" || utf8.RuneCountInString(holder) > 40 {
		return IssuedCard{}, domain.E("INVALID_ARGUMENT", "持卡人姓名不正确", "填写 1 到 40 个字符。")
	}
	if _, err := s.store.GetAccount(ctx, accountID); err != nil {
		return IssuedCard{}, err
	}
	pan := newPAN()
	cvv := newCVV()
	panEnc, err := platform.Encrypt(s.key, []byte(pan))
	if err != nil {
		return IssuedCard{}, err
	}
	cvvEnc, err := platform.Encrypt(s.key, []byte(cvv))
	if err != nil {
		return IssuedCard{}, err
	}
	// 虚拟卡直接可用。实体卡从 requested 起步，激活前不能消费或提现。
	status := "active"
	if kind == "physical" {
		status = "requested"
	}
	exp := time.Now().UTC().AddDate(4, 0, 0)
	card, err := s.store.CreateCard(ctx, store.CardInput{
		AccountID: accountID,
		Kind:      kind,
		Status:    status,
		Last4:     pan[len(pan)-4:],
		PANEnc:    panEnc,
		CVVEnc:    cvvEnc,
		Expiry:    exp.Format("01/06"),
		Holder:    holder,
	})
	if err != nil {
		return IssuedCard{}, err
	}
	return IssuedCard{Card: card, PAN: pan, CVV: cvv}, nil
}

// 列出某账户的卡。
func (s *CardService) List(ctx context.Context, accountID string) ([]domain.Card, error) {
	if _, err := s.store.GetAccount(ctx, accountID); err != nil {
		return nil, err
	}
	return s.store.ListCards(ctx, accountID)
}

// 读取一张卡的展示信息。
func (s *CardService) Get(ctx context.Context, id string) (domain.Card, error) {
	return s.store.GetCard(ctx, id)
}

type Secret struct {
	PAN    string `json:"pan"`
	CVV    string `json:"cvv"`
	Expiry string `json:"expiry"`
}

// 解密并返回完整卡号和 CVV。
func (s *CardService) Reveal(ctx context.Context, id string) (Secret, error) {
	card, err := s.store.GetCard(ctx, id)
	if err != nil {
		return Secret{}, err
	}
	panEnc, cvvEnc, err := s.store.Secrets(ctx, id)
	if err != nil {
		return Secret{}, err
	}
	pan, err := platform.Decrypt(s.key, panEnc)
	if err != nil {
		return Secret{}, err
	}
	cvv, err := platform.Decrypt(s.key, cvvEnc)
	if err != nil {
		return Secret{}, err
	}
	return Secret{PAN: string(pan), CVV: string(cvv), Expiry: card.Expiry}, nil
}

type MoveInput struct {
	Amount         string
	Merchant       string
	MCC            string
	Note           string
	IdempotencyKey string
}

// 用链上存入交易的哈希给卡入账。同一笔哈希不能给第二张卡加余额。
func (s *CardService) CreditDeposit(ctx context.Context, cardID string, in MoveInput) (domain.MoveResult, error) {
	ref := strings.TrimPrefix(strings.ToLower(strings.TrimSpace(in.IdempotencyKey)), "0x")
	if ref == "" {
		return domain.MoveResult{}, domain.E("INVALID_ARGUMENT", "缺少存入交易", "先在小狐狸里把 USDC 划进结算合约。")
	}
	other, err := s.store.TopupCardByRef(ctx, ref)
	if err != nil {
		return domain.MoveResult{}, err
	}
	if other != "" && other != cardID {
		return domain.MoveResult{}, domain.E("CONFLICT", "这笔存入已经入过账", "同一笔链上存入只能给一张卡加余额。")
	}
	in.IdempotencyKey = ref
	if strings.TrimSpace(in.Note) == "" {
		in.Note = "USDC 已存入结算合约"
	}
	return s.Topup(ctx, cardID, in)
}

// 给卡充值。注销后的卡不能充。
func (s *CardService) Topup(ctx context.Context, cardID string, in MoveInput) (domain.MoveResult, error) {
	amount, ref, err := moneyRef(in)
	if err != nil {
		return domain.MoveResult{}, err
	}
	note := strings.TrimSpace(in.Note)
	if note == "" {
		note = "充值"
	}
	return s.store.Move(ctx, cardID, []store.Draft{{
		Kind: "topup", Amount: amount, Note: note, Ref: ref,
	}}, allowTopup)
}

// 记一笔消费。卡必须是可使用状态。
func (s *CardService) Spend(ctx context.Context, cardID string, in MoveInput) (domain.MoveResult, error) {
	amount, ref, err := moneyRef(in)
	if err != nil {
		return domain.MoveResult{}, err
	}
	merchant := strings.TrimSpace(in.Merchant)
	if merchant == "" || utf8.RuneCountInString(merchant) > 80 {
		return domain.MoveResult{}, domain.E("INVALID_ARGUMENT", "需要商户名称", "消费记一笔商户，方便账单对账。")
	}
	mcc := strings.TrimSpace(in.MCC)
	if mcc != "" && !isMCC(mcc) {
		return domain.MoveResult{}, domain.E("INVALID_ARGUMENT", "MCC 应为 4 位数字", "例如餐饮 5814。可以留空。")
	}
	return s.store.Move(ctx, cardID, []store.Draft{{
		Kind: "spend", Amount: amount, Merchant: merchant, MCC: mcc, Note: strings.TrimSpace(in.Note), Ref: ref,
	}}, allowSpend)
}

// 从卡里提现，并额外扣 1% 手续费，最低 1 分。
func (s *CardService) Withdraw(ctx context.Context, cardID string, in MoveInput) (domain.MoveResult, error) {
	amount, ref, err := moneyRef(in)
	if err != nil {
		return domain.MoveResult{}, err
	}
	// 手续费外扣：用户要付 amount + fee。1% 向下取整，最低 1 分。
	fee := amount / 100
	if fee < 1 {
		fee = 1
	}
	note := strings.TrimSpace(in.Note)
	if note == "" {
		note = "提现到钱包余额"
	}
	return s.store.Move(ctx, cardID, []store.Draft{
		{Kind: "withdraw", Amount: amount, Note: note, Ref: ref},
		{Kind: "fee", Amount: fee, Note: "提现手续费 1%，最低 0.01 美元", Ref: ref + ":fee"},
	}, allowSpend)
}

// 执行制卡、寄出、激活、冻结或注销。
func (s *CardService) Transition(ctx context.Context, cardID, action string) (domain.Card, error) {
	action = strings.TrimSpace(action)
	if action == "" {
		return domain.Card{}, domain.E("INVALID_ARGUMENT", "缺少动作", "实体卡可用 issue、ship、activate。所有卡都可以 freeze、unfreeze、close。")
	}
	return s.store.Transition(ctx, cardID, action)
}

// 汇总某个月的充值、消费、提现和手续费。
func (s *CardService) Bill(ctx context.Context, cardID, month string) (domain.Bill, error) {
	card, err := s.store.GetCard(ctx, cardID)
	if err != nil {
		return domain.Bill{}, err
	}
	month = strings.TrimSpace(month)
	if month == "" {
		month = time.Now().UTC().Format("2006-01")
	}
	start, err := time.Parse("2006-01", month)
	if err != nil {
		return domain.Bill{}, domain.E("INVALID_ARGUMENT", "月份格式应为 YYYY-MM", "")
	}
	entries, err := s.store.ListEntries(ctx, cardID)
	if err != nil {
		return domain.Bill{}, err
	}
	end := start.AddDate(0, 1, 0)
	var opening int64
	var inMonth []domain.LedgerEntry
	for _, e := range entries {
		if e.CreatedAt.Before(start) {
			opening = int64(e.BalanceAfter)
			continue
		}
		if e.CreatedAt.Before(end) {
			inMonth = append(inMonth, e)
		}
	}
	if inMonth == nil {
		inMonth = []domain.LedgerEntry{}
	}
	bill := domain.Bill{
		CardID: cardID, Month: month, Currency: card.Currency,
		Opening: domain.Cents(opening), Entries: inMonth, Closing: domain.Cents(opening),
	}
	var topup, spend, withdraw, fees int64
	closing := opening
	for _, e := range inMonth {
		switch e.Kind {
		case "topup":
			topup += int64(e.Amount)
		case "spend":
			spend += int64(e.Amount)
		case "withdraw":
			withdraw += int64(e.Amount)
		case "fee":
			fees += int64(e.Amount)
		}
		closing = int64(e.BalanceAfter)
	}
	bill.Topup = domain.Cents(topup)
	bill.Spend = domain.Cents(spend)
	bill.Withdraw = domain.Cents(withdraw)
	bill.Fees = domain.Cents(fees)
	bill.Closing = domain.Cents(closing)
	return bill, nil
}

// 判断当前状态能不能充值。
func allowTopup(kind, status string) error {
	if status == "closed" {
		return domain.E("CARD_INACTIVE", "卡已注销", "注销后不能再充值。")
	}
	_ = kind
	return nil
}

// 只有 active 能消费或提现。充值走 allowTopup，注销前都可以充。
func allowSpend(kind, status string) error {
	if status != "active" {
		hint := "先把卡解冻。"
		if kind == "physical" && (status == "requested" || status == "issued" || status == "shipped") {
			hint = "实体卡要激活后才能消费或提现。可以先充值。"
		}
		if status == "closed" {
			hint = "注销后的卡不能再动账。"
		}
		return domain.E("CARD_INACTIVE", "卡当前状态不能消费或提现", hint)
	}
	return nil
}

// 把美元金额解析成分，并整理幂等键。
func moneyRef(in MoveInput) (int64, string, error) {
	amount, err := domain.ParseCents(in.Amount)
	if err != nil {
		return 0, "", domain.E("INVALID_ARGUMENT", err.Error(), "金额用美元，最多两位小数，例如 25.50。")
	}
	ref := strings.TrimSpace(in.IdempotencyKey)
	if ref == "" {
		ref = platform.NewID()
	}
	if len(ref) > 64 || strings.Contains(ref, ":") {
		return 0, "", domain.E("INVALID_ARGUMENT", "幂等键不合法", "使用 UUID，不要包含冒号。")
	}
	return amount, ref, nil
}

// 检查商户类别码是不是 4 位数字。
func isMCC(s string) bool {
	if len(s) != 4 {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// BIN 990016 只属于这个沙箱，后面 9 位随机，最后一位是 Luhn 校验。
func newPAN() string {
	var b strings.Builder
	b.WriteString("990016")
	for i := 0; i < 9; i++ {
		b.WriteByte(byte('0' + cryptoInt(10)))
	}
	base := b.String()
	return base + string(rune('0'+luhnCheck(base)))
}

// 计算卡号最后一位 Luhn 校验位。
func luhnCheck(base string) int {
	sum := 0
	alt := true
	for i := len(base) - 1; i >= 0; i-- {
		d := int(base[i] - '0')
		if alt {
			d *= 2
			if d > 9 {
				d -= 9
			}
		}
		sum += d
		alt = !alt
	}
	return (10 - (sum % 10)) % 10
}

// 生成 3 位随机安全码。
func newCVV() string {
	return string([]byte{byte('0' + cryptoInt(10)), byte('0' + cryptoInt(10)), byte('0' + cryptoInt(10))})
}

// 生成 0 到 n-1 的密码学随机整数。
func cryptoInt(n int) int {
	v, err := rand.Int(rand.Reader, big.NewInt(int64(n)))
	if err != nil {
		return 0
	}
	return int(v.Int64())
}
