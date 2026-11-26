// 五条链共用的适配器接口和地址校验。业务层只依赖这个接口，不关心节点协议。
package chain

import (
	"context"
	"math/big"
	"strings"

	"aegis/internal/domain"
	"aegis/internal/platform"
)

// Adapter 是一条链的最小能力：健康检查、余额、报价、广播、状态、白名单探针。
type Adapter interface {
	ID() domain.ChainID
	Health(ctx context.Context) domain.Health
	Balance(ctx context.Context, address string, asset domain.Asset) (string, error)
	Prepare(ctx context.Context, from, to, amount string, units *big.Int, asset domain.Asset) (domain.PrepareResult, error)
	Broadcast(ctx context.Context, signed string) (string, error)
	Status(ctx context.Context, hash string) (domain.TxState, error)
	Probes() []domain.Probe
	RunProbe(ctx context.Context, name string, args map[string]string) (any, error)
}

// EVM 和比特币地址大小写不敏感。Solana、TRON 的 base58 区分大小写，不能转小写。
func NormalizeAddress(id domain.ChainID, address string) string {
	address = strings.TrimSpace(address)
	switch id {
	case domain.Ethereum, domain.BNB, domain.Bitcoin:
		return strings.ToLower(address)
	default:
		return address
	}
}

// RejectZero 拒绝代币合约不接受的零地址。T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb 是 20 个 0 的波场写法。
func RejectZero(id domain.ChainID, address string) error {
	address = strings.TrimSpace(address)
	zero := false
	switch id {
	case domain.Ethereum, domain.BNB:
		zero = strings.EqualFold(address, "0x0000000000000000000000000000000000000000")
	case domain.Tron:
		raw, err := platform.Base58CheckDecode(address)
		if err == nil && len(raw) == 21 && raw[0] == 0x41 {
			zero = true
			for _, b := range raw[1:] {
				if b != 0 {
					zero = false
					break
				}
			}
		}
	}
	if zero {
		return domain.E("INVALID_ARGUMENT", "不能转到零地址", "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb 是 20 个 0 的波场写法。0x 后面 40 个 0 是 EVM 的零地址。代币合约会直接拒绝。")
	}
	return nil
}

// 按链和网络检查地址格式，不通过就返回可读错误。
func ValidateAddress(id domain.ChainID, network, address string) error {
	address = strings.TrimSpace(address)
	switch id {
	case domain.Ethereum, domain.BNB:
		if !isHexAddress(address) {
			return domain.E("INVALID_ARGUMENT", "EVM 地址格式不正确", "地址应为 0x 开头的 40 位十六进制。")
		}
	case domain.Bitcoin:
		hrp := "bc1q"
		if network == "testnet" {
			hrp = "tb1q"
		}
		low := strings.ToLower(address)
		if !strings.HasPrefix(low, hrp) || len(low) < 20 || len(low) > 90 || !bech32Charset(low[len(hrp):]) {
			return domain.E("INVALID_ARGUMENT", "比特币地址格式不正确", "当前只收原生隔离见证地址，主网 bc1q…，测试网 tb1q…。")
		}
	case domain.Solana:
		raw, err := platform.Base58Decode(address)
		if err != nil || len(raw) != 32 {
			return domain.E("INVALID_ARGUMENT", "Solana 地址格式不正确", "地址是 32 字节 ed25519 公钥的 base58。")
		}
	case domain.Tron:
		raw, err := platform.Base58CheckDecode(address)
		if err != nil || len(raw) != 21 || raw[0] != 0x41 {
			return domain.E("INVALID_ARGUMENT", "TRON 地址格式不正确", "地址是带 0x41 版本字节的 base58check，一般以 T 开头。")
		}
	default:
		return domain.E("INVALID_ARGUMENT", "不支持的链", "")
	}
	return nil
}

// 检查是不是 0x 加 40 位十六进制。
func isHexAddress(address string) bool {
	if len(address) != 42 || !strings.HasPrefix(address, "0x") && !strings.HasPrefix(address, "0X") {
		return false
	}
	for _, r := range address[2:] {
		switch {
		case r >= '0' && r <= '9':
		case r >= 'a' && r <= 'f':
		case r >= 'A' && r <= 'F':
		default:
			return false
		}
	}
	return true
}

// 检查隔离见证地址里有没有 bech32 不允许的字符。
func bech32Charset(s string) bool {
	const alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
	for _, r := range s {
		if !strings.ContainsRune(alphabet, r) {
			return false
		}
	}
	return s != ""
}

// 按链的大小写规则比较两个地址是不是同一个。
func SameAddress(id domain.ChainID, a, b string) bool {
	return NormalizeAddress(id, a) == NormalizeAddress(id, b)
}
