package domain

import (
	"encoding/json"
	"fmt"
	"time"
)

type ChainID string

const (
	Bitcoin  ChainID = "bitcoin"
	Ethereum ChainID = "ethereum"
	BNB      ChainID = "bnb"
	Solana   ChainID = "solana"
	Tron     ChainID = "tron"
)

// 判断是不是本项目支持的五条链之一。
func (c ChainID) Valid() bool {
	switch c {
	case Bitcoin, Ethereum, BNB, Solana, Tron:
		return true
	default:
		return false
	}
}

// 按固定顺序返回 Bitcoin、Ethereum、BNB、Solana、TRON。
func Chains() []ChainID {
	return []ChainID{Bitcoin, Ethereum, BNB, Solana, Tron}
}

// 返回这条链的原生币符号，例如 ETH、TRX。
func NativeSymbol(c ChainID) string {
	switch c {
	case Bitcoin:
		return "BTC"
	case Ethereum:
		return "ETH"
	case BNB:
		return "BNB"
	case Solana:
		return "SOL"
	case Tron:
		return "TRX"
	default:
		return ""
	}
}

// 返回给界面看的链名称。
func ChainName(c ChainID) string {
	switch c {
	case Bitcoin:
		return "Bitcoin"
	case Ethereum:
		return "Ethereum"
	case BNB:
		return "BNB Smart Chain"
	case Solana:
		return "Solana"
	case Tron:
		return "TRON"
	default:
		return string(c)
	}
}

// Error 是接口统一的错误形状。Hint 告诉调用方下一步查什么，属于产品的一部分。
type Error struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Hint      string `json:"hint,omitempty"`
	Retryable bool   `json:"retryable"`
}

// 让 Error 能当作普通 error 返回。
func (e *Error) Error() string {
	if e == nil {
		return ""
	}
	if e.Message != "" {
		return e.Message
	}
	return e.Code
}

// 构造一个带错误码和提示的业务错误。
func E(code, message, hint string) *Error {
	return &Error{Code: code, Message: message, Hint: hint}
}

// Cents 是卡账本的最小单位：1 美元 = 100 分。JSON 输出成 "0.00"，内部始终是整数。
type Cents int64

// 把「分」输出成两位小数字符串，例如 2550 变成 "25.50"。
func (c Cents) MarshalJSON() ([]byte, error) {
	return json.Marshal(FormatCents(int64(c)))
}

// 取出里面的整数分。
func (c Cents) Int64() int64 { return int64(c) }

// 把整数分格式化成带符号的两位小数。
func FormatCents(c int64) string {
	sign := ""
	if c < 0 {
		sign = "-"
		c = -c
	}
	return fmt.Sprintf("%s%d.%02d", sign, c/100, c%100)
}

type Asset struct {
	Symbol   string `json:"symbol"`
	Name     string `json:"name"`
	Decimals int    `json:"decimals"`
	Kind     string `json:"kind"`
	Contract string `json:"contract,omitempty"`
	Note     string `json:"note,omitempty"`
}

type Address struct {
	Chain   ChainID `json:"chain"`
	Address string  `json:"address"`
	Path    string  `json:"path"`
}

type Account struct {
	ID        string    `json:"id"`
	Label     string    `json:"label"`
	CreatedAt time.Time `json:"createdAt"`
	Addresses []Address `json:"addresses"`
}

type Fee struct {
	Symbol string            `json:"symbol"`
	Amount string            `json:"amount"`
	USD    string            `json:"usd,omitempty"`
	Detail map[string]string `json:"detail,omitempty"`
}

type PrepareResult struct {
	Chain    ChainID  `json:"chain"`
	Network  string   `json:"network"`
	Mode     string   `json:"mode"`
	Asset    string   `json:"asset"`
	From     string   `json:"from"`
	To       string   `json:"to"`
	Amount   string   `json:"amount"`
	Fee      Fee      `json:"fee"`
	Payload  any      `json:"payload"`
	Warnings []string `json:"warnings"`
}

type EVMPayload struct {
	Type                 int    `json:"type"`
	ChainID              string `json:"chainId"`
	Nonce                string `json:"nonce"`
	To                   string `json:"to"`
	Value                string `json:"value"`
	Data                 string `json:"data"`
	GasLimit             string `json:"gasLimit"`
	MaxFeePerGas         string `json:"maxFeePerGas"`
	MaxPriorityFeePerGas string `json:"maxPriorityFeePerGas"`
}

type BTCInput struct {
	TxID  string `json:"txid"`
	Vout  uint32 `json:"vout"`
	Value int64  `json:"value"`
}

type BTCOutput struct {
	Address string `json:"address"`
	Value   int64  `json:"value"`
}

type BTCPayload struct {
	Network string      `json:"network"`
	Inputs  []BTCInput  `json:"inputs"`
	Outputs []BTCOutput `json:"outputs"`
	FeeSats int64       `json:"feeSats"`
}

type SOLPayload struct {
	Message   string `json:"message"`
	Blockhash string `json:"blockhash"`
	Lamports  string `json:"lamports"`
	From      string `json:"from"`
	To        string `json:"to"`
}

type TRONPayload struct {
	TxID        string          `json:"txID"`
	Transaction json.RawMessage `json:"transaction"`
}

type Transfer struct {
	ID            string    `json:"id"`
	AccountID     string    `json:"accountId"`
	Chain         ChainID   `json:"chain"`
	Asset         string    `json:"asset"`
	From          string    `json:"from"`
	To            string    `json:"to"`
	Amount        string    `json:"amount"`
	Fee           string    `json:"fee"`
	TxHash        string    `json:"txHash"`
	SignedPayload string    `json:"signedPayload"`
	Status        string    `json:"status"`
	Detail        string    `json:"detail,omitempty"`
	CreatedAt     time.Time `json:"createdAt"`
	UpdatedAt     time.Time `json:"updatedAt"`
}

type TxState struct {
	State  string `json:"state"`
	Hash   string `json:"hash"`
	Block  string `json:"block,omitempty"`
	Detail string `json:"detail,omitempty"`
}

type Health struct {
	Chain     ChainID `json:"chain"`
	Name      string  `json:"name"`
	OK        bool    `json:"ok"`
	LatencyMS int64   `json:"latencyMs"`
	Block     string  `json:"block,omitempty"`
	Endpoint  string  `json:"endpoint"`
	Mode      string  `json:"mode"`
	Error     *Error  `json:"error,omitempty"`
}

type Probe struct {
	Name        string `json:"name"`
	Title       string `json:"title"`
	Description string `json:"description"`
}

type Card struct {
	ID        string    `json:"id"`
	AccountID string    `json:"accountId"`
	Kind      string    `json:"kind"`
	Status    string    `json:"status"`
	Last4     string    `json:"last4"`
	Expiry    string    `json:"expiry"`
	Holder    string    `json:"holder"`
	Currency  string    `json:"currency"`
	Available Cents     `json:"available"`
	CreatedAt time.Time `json:"createdAt"`
}

type LedgerEntry struct {
	ID           string    `json:"id"`
	CardID       string    `json:"cardId"`
	Kind         string    `json:"kind"`
	Amount       Cents     `json:"amount"`
	BalanceAfter Cents     `json:"balanceAfter"`
	Merchant     string    `json:"merchant,omitempty"`
	MCC          string    `json:"mcc,omitempty"`
	Ref          string    `json:"ref"`
	Note         string    `json:"note,omitempty"`
	CreatedAt    time.Time `json:"createdAt"`
}

type Bill struct {
	CardID   string        `json:"cardId"`
	Month    string        `json:"month"`
	Currency string        `json:"currency"`
	Opening  Cents         `json:"opening"`
	Topup    Cents         `json:"topup"`
	Spend    Cents         `json:"spend"`
	Withdraw Cents         `json:"withdraw"`
	Fees     Cents         `json:"fees"`
	Closing  Cents         `json:"closing"`
	Entries  []LedgerEntry `json:"entries"`
}

type MoveResult struct {
	Card     Card          `json:"card"`
	Entries  []LedgerEntry `json:"entries"`
	Replayed bool          `json:"replayed"`
}
