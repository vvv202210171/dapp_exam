// 结算合约和 MockUSDC 的 calldata。选择器是函数签名的 keccak256 前 4 字节。
package settlement

import (
	"crypto/sha256"
	"encoding/hex"
	"math/big"
	"strings"

	"aegis/internal/domain"
)

const (
	selApprove   = "095ea7b3" // approve(address,uint256)
	selBalanceOf = "70a08231" // balanceOf(address)
	selAllowance = "dd62ed3e" // allowance(address,address)
	selMint      = "40c10f19" // mint(address,uint256)
	selDeposit   = "47e7ef24" // deposit(address,uint256)
	selWithdraw  = "f3fef3a3" // withdraw(address,uint256)
	selSettle    = "d4729c72" // settle(address,address,uint256,bytes32)
	selAvailable = "bbe430de" // available(address,address)
)

// 1 美元 = 100 分 = 1_000_000 个 USDC 最小单位，所以 1 分 = 10_000。
const unitsPerCent = 10_000

// 把卡账本的「分」换成 USDC 的 6 位最小单位。
func CentsToUnits(cents int64) *big.Int {
	return new(big.Int).Mul(big.NewInt(cents), big.NewInt(unitsPerCent))
}

// 账单号取流水 id 的 SHA-256。同一笔流水永远得到同一个 bytes32，合约才能挡住重复结算。
func BillRef(entryID string) string {
	sum := sha256.Sum256([]byte(entryID))
	return "0x" + hex.EncodeToString(sum[:])
}

// 编码 approve(spender, amount)。
func ApproveData(spender string, amount *big.Int) (string, error) {
	word, err := abiAddress(spender)
	if err != nil {
		return "", err
	}
	return pack(selApprove, word, abiUint(amount)), nil
}

// 编码 balanceOf(holder)。
func BalanceOfData(holder string) (string, error) {
	word, err := abiAddress(holder)
	if err != nil {
		return "", err
	}
	return pack(selBalanceOf, word), nil
}

// 编码 allowance(owner, spender)。
func AllowanceData(owner, spender string) (string, error) {
	a, err := abiAddress(owner)
	if err != nil {
		return "", err
	}
	b, err := abiAddress(spender)
	if err != nil {
		return "", err
	}
	return pack(selAllowance, a, b), nil
}

// 编码测试币的 mint(to, amount)。本地水龙头用，正式 USDC 没有这个方法。
func MintData(to string, amount *big.Int) (string, error) {
	word, err := abiAddress(to)
	if err != nil {
		return "", err
	}
	return pack(selMint, word, abiUint(amount)), nil
}

// 从 deposit 的 calldata 里取出代币地址和金额。字的高位必须是 0。
// 交易哈希必须是 32 字节。缺哈希、短哈希不会去问节点。
func NormalizeTxHash(txHash string) (string, error) {
	raw := strings.TrimPrefix(strings.TrimSpace(txHash), "0x")
	if len(raw) != 64 || strings.Trim(raw, "0123456789abcdefABCDEF") != "" {
		return "", domain.E("INVALID_ARGUMENT", "缺少已确认的存入交易", "先在小狐狸里把同样金额的 USDC 存进结算合约。")
	}
	return "0x" + strings.ToLower(raw), nil
}

func ParseDeposit(data string) (string, *big.Int, error) {
	s := strings.TrimPrefix(strings.TrimSpace(data), "0x")
	if len(s) < 8+64+64 || !strings.EqualFold(s[:8], selDeposit) {
		return "", nil, domain.E("INVALID_ARGUMENT", "这不是一笔存入交易", "卡充值只认结算合约的 deposit。")
	}
	tokenWord := s[8:72]
	if strings.Trim(tokenWord[:24], "0") != "" {
		return "", nil, domain.E("INVALID_ARGUMENT", "存入交易里的代币地址不合法", "")
	}
	amount, ok := new(big.Int).SetString(s[72:136], 16)
	if !ok {
		return "", nil, domain.E("INVALID_ARGUMENT", "存入金额无法解析", "")
	}
	return "0x" + tokenWord[24:], amount, nil
}

// 编码 deposit(token, amount)。
func DepositData(token string, amount *big.Int) (string, error) {
	word, err := abiAddress(token)
	if err != nil {
		return "", err
	}
	return pack(selDeposit, word, abiUint(amount)), nil
}

// 编码 withdraw(token, amount)。
func WithdrawData(token string, amount *big.Int) (string, error) {
	word, err := abiAddress(token)
	if err != nil {
		return "", err
	}
	return pack(selWithdraw, word, abiUint(amount)), nil
}

// 编码 settle(user, token, amount, ref)。
func SettleData(user, token string, amount *big.Int, ref string) (string, error) {
	userWord, err := abiAddress(user)
	if err != nil {
		return "", err
	}
	tokenWord, err := abiAddress(token)
	if err != nil {
		return "", err
	}
	refWord, err := abiBytes32(ref)
	if err != nil {
		return "", err
	}
	return pack(selSettle, userWord, tokenWord, abiUint(amount), refWord), nil
}

// 编码 available(user, token)。
func AvailableData(user, token string) (string, error) {
	userWord, err := abiAddress(user)
	if err != nil {
		return "", err
	}
	tokenWord, err := abiAddress(token)
	if err != nil {
		return "", err
	}
	return pack(selAvailable, userWord, tokenWord), nil
}

// 把选择器和若干个 32 字节字拼成 0x calldata。
func pack(selector string, words ...[]byte) string {
	sel, _ := hex.DecodeString(selector)
	out := append([]byte{}, sel...)
	for _, word := range words {
		out = append(out, word...)
	}
	return "0x" + hex.EncodeToString(out)
}

// 把 0x 地址左填充成 ABI 的 32 字节字。
func abiAddress(addr string) ([]byte, error) {
	s := strings.TrimPrefix(strings.TrimSpace(addr), "0x")
	if len(s) != 40 {
		return nil, domain.E("INVALID_ARGUMENT", "EVM 地址格式不正确", "地址应为 0x 开头的 40 位十六进制。")
	}
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != 20 {
		return nil, domain.E("INVALID_ARGUMENT", "EVM 地址格式不正确", "地址应为 0x 开头的 40 位十六进制。")
	}
	out := make([]byte, 32)
	copy(out[12:], b)
	return out, nil
}

// 把 0x 开头的 32 字节十六进制收成 ABI 字。
func abiBytes32(ref string) ([]byte, error) {
	s := strings.TrimPrefix(strings.TrimSpace(ref), "0x")
	if len(s) != 64 {
		return nil, domain.E("INVALID_ARGUMENT", "账单号必须是 32 字节", "结算用流水 id 的 SHA-256。")
	}
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != 32 {
		return nil, domain.E("INVALID_ARGUMENT", "账单号必须是 32 字节", "结算用流水 id 的 SHA-256。")
	}
	return b, nil
}

// 把整数左填充成 ABI 的 32 字节字。
func abiUint(n *big.Int) []byte {
	out := make([]byte, 32)
	if n == nil || n.Sign() <= 0 {
		return out
	}
	b := n.Bytes()
	if len(b) > 32 {
		panic("uint overflow")
	}
	copy(out[32-len(b):], b)
	return out
}
