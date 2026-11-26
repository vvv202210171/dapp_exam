package chain

import (
	"crypto/sha256"
	"encoding/binary"
	"math/big"
	"strings"

	"aegis/internal/domain"
)

type TokenSpec struct {
	Symbol    string
	Name      string
	Decimals  int
	Contracts map[string]string
	Notes     map[string]string
}

// 组装一条代币目录项。
func token(symbol, name string, decimals int, contracts, notes map[string]string) TokenSpec {
	return TokenSpec{Symbol: symbol, Name: name, Decimals: decimals, Contracts: contracts, Notes: notes}
}

// 返回这条链支持的 USDT、USDC 及各自精度和合约地址。
func Catalog(id domain.ChainID) []TokenSpec {
	switch id {
	case domain.Ethereum:
		return []TokenSpec{
			token("USDT", "Tether USD", 6, map[string]string{"mainnet": "0xdAC17F958D2ee523a2206206994597C13D831ec7", "testnet": "0xaA8E23Fb1079EA71e0a56F48a2aA51851D8433D0"}, map[string]string{"testnet": "Sepolia 上名为 USDT 的测试币，6 位小数。"}),
			token("USDC", "USD Coin", 6, map[string]string{"mainnet": "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "testnet": "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"}, map[string]string{"testnet": "Circle 在 Sepolia 上的 USDC，6 位小数。"}),
		}
	case domain.BNB:
		// BSC 上的 USDT、USDC 是 18 位小数。同名代币跨链单位不同，填错会差 10^12。
		return []TokenSpec{
			token("USDT", "Tether USD", 18, map[string]string{"mainnet": "0x55d398326f99059fF775485246999027B3197955", "testnet": "0x337610d27c682E347C9cD60BD4b3b107C9d34dDd"}, map[string]string{"testnet": "BSC 测试网 USDT，18 位小数。"}),
			token("USDC", "USD Coin", 18, map[string]string{"mainnet": "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d"}, nil),
		}
	case domain.Tron:
		return []TokenSpec{
			token("USDT", "Tether USD", 6, map[string]string{"mainnet": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "testnet": "TG3XXyExBkPp9nzdajDZsozEu4BkaSJozs"}, map[string]string{"testnet": "Shasta 水龙头发放的测试 USDT，6 位小数。"}),
			token("USDC", "USD Coin", 6, map[string]string{"mainnet": "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8"}, nil),
		}
	case domain.Solana:
		return []TokenSpec{
			token("USDT", "Tether USD", 6, map[string]string{"mainnet": "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"}, nil),
			token("USDC", "USD Coin", 6, map[string]string{"mainnet": "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "testnet": "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"}, map[string]string{"testnet": "Circle 在 devnet 上的 USDC，6 位小数。"}),
		}
	default:
		return nil
	}
}

// 拼出资产页要查的列表：先是原生币，再是稳定币。
func Assets(id domain.ChainID, network, mode string) []domain.Asset {
	native := domain.Asset{
		Symbol:   domain.NativeSymbol(id),
		Name:     domain.ChainName(id),
		Decimals: nativeDecimals(id),
		Kind:     "native",
	}
	out := []domain.Asset{native}
	for _, spec := range Catalog(id) {
		contract := spec.Contracts[network]
		note := ""
		if contract == "" {
			if mode == "sandbox" {
				contract = spec.Contracts["mainnet"]
				note = "沙箱展示主网合约地址，余额是模拟值。"
			} else {
				note = "当前网络没有配置该代币的官方合约，余额按 0 处理。"
			}
		} else if spec.Notes != nil {
			note = spec.Notes[network]
		}
		out = append(out, domain.Asset{
			Symbol:   spec.Symbol,
			Name:     spec.Name,
			Decimals: spec.Decimals,
			Kind:     "token",
			Contract: contract,
			Note:     note,
		})
	}
	return out
}

// 按符号找出要转账的那种资产。
func FindAsset(id domain.ChainID, network, mode, symbol string) (domain.Asset, error) {
	symbol = strings.ToUpper(strings.TrimSpace(symbol))
	for _, asset := range Assets(id, network, mode) {
		if asset.Symbol == symbol {
			return asset, nil
		}
	}
	return domain.Asset{}, domain.E("INVALID_ARGUMENT", "这条链不支持该资产", "Bitcoin 原生链没有 USDT/USDC 合约。稳定币在 EVM、TRON 和 Solana 上。")
}

// 返回原生币小数位：BTC 8、SOL 9、TRX 6，EVM 是 18。
func nativeDecimals(id domain.ChainID) int {
	switch id {
	case domain.Bitcoin:
		return 8
	case domain.Solana:
		return 9
	case domain.Tron:
		return 6
	default:
		return 18
	}
}

// 用地址和符号的哈希生成稳定的模拟余额，同一地址每次相同。
func SandboxAmount(address, symbol string, decimals int) string {
	sum := sha256.Sum256([]byte(strings.ToLower(address) + "|" + symbol))
	n := int(binary.BigEndian.Uint16(sum[:2]))
	switch symbol {
	case "BTC":
		sats := int64(1_000_000 + (n%750)*100_000)
		return domain.FormatUnits(big.NewInt(sats), 8)
	case "ETH":
		wei := int64(50_000_000_000_000_000) + int64(n%1500)*1_000_000_000_000_000
		return domain.FormatUnits(big.NewInt(wei), 18)
	case "BNB":
		wei := int64(20_000_000_000_000_000) + int64(n%800)*10_000_000_000_000_000
		return domain.FormatUnits(big.NewInt(wei), 18)
	case "SOL":
		lamports := int64(500_000_000) + int64(n%19_500)*1_000_000
		return domain.FormatUnits(big.NewInt(lamports), 9)
	case "TRX":
		sun := int64(100_000_000) + int64(n)*75_000
		return domain.FormatUnits(big.NewInt(sun), 6)
	case "USDT", "USDC":
		whole := int64(20 + n%1980)
		scale := domain.Pow10(decimals)
		return domain.FormatUnits(new(big.Int).Mul(big.NewInt(whole), scale), decimals)
	default:
		return "0"
	}
}

// 返回这条链浏览器的交易链接前缀。
func ExplorerTx(id domain.ChainID, network string) string {
	main := network == "mainnet"
	switch id {
	case domain.Ethereum:
		if main {
			return "https://etherscan.io/tx/"
		}
		return "https://sepolia.etherscan.io/tx/"
	case domain.BNB:
		if main {
			return "https://bscscan.com/tx/"
		}
		return "https://testnet.bscscan.com/tx/"
	case domain.Bitcoin:
		if main {
			return "https://mempool.space/tx/"
		}
		return "https://mempool.space/testnet/tx/"
	case domain.Solana:
		if main {
			return "https://solscan.io/tx/"
		}
		return "https://solscan.io/tx/"
	case domain.Tron:
		if main {
			return "https://tronscan.org/#/transaction/"
		}
		return "https://shasta.tronscan.org/#/transaction/"
	default:
		return ""
	}
}
