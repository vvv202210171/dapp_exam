// 把节点和系统错误收成稳定的 code、message、hint。调用方靠 code 分支，靠 hint 知道下一步查什么。
package fault

import (
	"context"
	"errors"
	"strings"

	"aegis/internal/domain"
)

// 把超时、余额不足、nonce、限流等错误收成稳定的错误码和提示。
func Explain(err error) *domain.Error {
	if err == nil {
		return nil
	}
	var de *domain.Error
	if errors.As(err, &de) {
		return de
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		return &domain.Error{
			Code:      "RPC_TIMEOUT",
			Message:   "节点请求超时",
			Hint:      "检查本机网络和 RPC 地址。公共节点在高峰会变慢，可以换备用节点，或先把单次超时从 8 秒往上调。",
			Retryable: true,
		}
	}
	msg := err.Error()
	low := strings.ToLower(msg)
	switch {
	case strings.Contains(low, "insufficient funds") || strings.Contains(low, "insufficient balance") || strings.Contains(low, "insufficient lamports"):
		return &domain.Error{Code: "INSUFFICIENT_FUNDS", Message: "余额不够支付金额和手续费", Hint: "代币转账也要消耗链上的原生币。先确认 ETH/BNB/TRX/SOL 余额覆盖 gas，再看代币余额。", Retryable: false}
	case strings.Contains(low, "nonce too low"):
		return &domain.Error{Code: "NONCE_TOO_LOW", Message: "Nonce 过低", Hint: "这个 nonce 已经被用掉了。重新报价，拿到 pending nonce 后再签。如果上一笔还在内存池，等它确认或用更高 gas 替换。", Retryable: true}
	case strings.Contains(low, "replacement transaction underpriced") || strings.Contains(low, "underpriced"):
		return &domain.Error{Code: "UNDERPRICED", Message: "Gas 价格不足以替换已有交易", Hint: "同一 nonce 已有一笔待打包交易。把 maxFeePerGas 提高至少 10% 后重新签名。", Retryable: true}
	case strings.Contains(low, "already known") || strings.Contains(low, "already imported"):
		return &domain.Error{Code: "ALREADY_KNOWN", Message: "节点已经收过这笔交易", Hint: "不用重复广播。用交易哈希查询回执即可。", Retryable: false}
	case strings.Contains(low, "execution reverted") || strings.Contains(low, "revert"):
		return &domain.Error{Code: "REVERTED", Message: "合约执行被回滚", Hint: "常见原因：代币余额不足、USDT 黑名单、没有 approve、收款地址拒绝接收代币。用 eth_call 重放同一笔 calldata 可以看 revert 原因。", Retryable: false}
	case strings.Contains(low, "429") || strings.Contains(low, "rate limit") || strings.Contains(low, "too many requests"):
		return &domain.Error{Code: "RATE_LIMITED", Message: "节点限流", Hint: "公共 RPC 对 IP 有频率限制。降低轮询，或换成自己的节点 / 带 API Key 的服务。", Retryable: true}
	case strings.Contains(low, "connection refused") || strings.Contains(low, "no such host") || strings.Contains(low, "tls") || strings.Contains(low, "i/o timeout") || strings.Contains(low, "network is unreachable"):
		return &domain.Error{Code: "RPC_UNREACHABLE", Message: "节点不可达", Hint: "核对 RPC URL、DNS 和代理。确认当前选的是主网还是测试网，两条网的节点不能混用。", Retryable: true}
	case strings.Contains(low, "account does not exist") || strings.Contains(low, "account not found") || strings.Contains(low, "does not exist"):
		return &domain.Error{Code: "ACCOUNT_INACTIVE", Message: "链上账户尚未激活或不存在", Hint: "TRON 地址要先收到一笔 TRX 才会被激活。Solana 新地址转账还要考虑租金豁免。", Retryable: false}
	case strings.Contains(low, "blockhash") && strings.Contains(low, "not found"):
		return &domain.Error{Code: "BLOCKHASH_EXPIRED", Message: "Solana blockhash 已过期", Hint: "blockhash 大约 60–90 秒失效。重新报价后再签名，不要复用旧的未签名交易。", Retryable: true}
	default:
		return &domain.Error{Code: "CHAIN_ERROR", Message: clip(msg, 300), Hint: "对照节点原文检查：地址、链 ID、金额精度、nonce、以及签名是否签在了正确的 payload 上。", Retryable: true}
	}
}

// 按错误码选择 HTTP 状态码。
func Status(err error) int {
	de := Explain(err)
	if de == nil {
		return 500
	}
	switch de.Code {
	case "INVALID_ARGUMENT":
		return 400
	case "NOT_FOUND":
		return 404
	case "CONFLICT":
		return 409
	case "INSUFFICIENT_FUNDS", "CARD_INACTIVE", "ACCOUNT_INACTIVE":
		return 422
	case "RATE_LIMITED":
		return 429
	case "RPC_TIMEOUT", "RPC_UNREACHABLE", "RPC_UNAVAILABLE", "CHAIN_ERROR", "UNDERPRICED", "NONCE_TOO_LOW", "REVERTED", "ALREADY_KNOWN", "BLOCKHASH_EXPIRED":
		return 502
	default:
		if de.Code == "" {
			return 500
		}
		return 400
	}
}

// 把节点原文截到指定长度。
func clip(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
