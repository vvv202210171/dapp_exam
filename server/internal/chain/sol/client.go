// Solana JSON-RPC。报价只构造原生 SOL 转账；SPL 代币要先有关联账户，这里只查余额。
package sol

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"strings"
	"time"

	"aegis/internal/domain"
	"aegis/internal/platform/fault"
	"aegis/internal/platform/httpx"
	"aegis/internal/txbuild"
)

type Client struct {
	network string
	rpc     string
	http    *httpx.Client
}

// 创建一个 Solana JSON-RPC 客户端。
func New(network, rpc string, http *httpx.Client) *Client {
	return &Client{network: network, rpc: rpc, http: http}
}

// 返回 Solana。
func (c *Client) ID() domain.ChainID { return domain.Solana }

// 用 getSlot 看节点通不通。
func (c *Client) Health(ctx context.Context) domain.Health {
	start := time.Now()
	h := domain.Health{Chain: domain.Solana, Name: "Solana", Endpoint: httpx.Host(c.rpc), Mode: "live"}
	raw, err := c.call(ctx, "getSlot", []any{})
	h.LatencyMS = time.Since(start).Milliseconds()
	if err != nil {
		h.Error = fault.Explain(err)
		return h
	}
	h.OK = true
	h.Block = strings.Trim(string(raw), `"`)
	return h
}

// 查 SOL 余额；代币则按 mint 汇总关联账户。
func (c *Client) Balance(ctx context.Context, address string, asset domain.Asset) (string, error) {
	if asset.Kind == "native" {
		raw, err := c.call(ctx, "getBalance", []any{address})
		if err != nil {
			return "", err
		}
		var parsed struct {
			Value uint64 `json:"value"`
		}
		if err := json.Unmarshal(raw, &parsed); err != nil {
			return "", err
		}
		return domain.FormatUnits(new(big.Int).SetUint64(parsed.Value), 9), nil
	}
	if asset.Contract == "" {
		return "0", nil
	}
	raw, err := c.call(ctx, "getTokenAccountsByOwner", []any{
		address,
		map[string]string{"mint": asset.Contract},
		map[string]string{"encoding": "jsonParsed"},
	})
	if err != nil {
		return "", err
	}
	var parsed struct {
		Value []struct {
			Account struct {
				Data struct {
					Parsed struct {
						Info struct {
							TokenAmount struct {
								Amount string `json:"amount"`
							} `json:"tokenAmount"`
						} `json:"info"`
					} `json:"parsed"`
				} `json:"data"`
			} `json:"account"`
		} `json:"value"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return "", err
	}
	total := big.NewInt(0)
	for _, row := range parsed.Value {
		n, ok := new(big.Int).SetString(row.Account.Data.Parsed.Info.TokenAmount.Amount, 10)
		if ok {
			total.Add(total, n)
		}
	}
	return domain.FormatUnits(total, asset.Decimals), nil
}

// 取最新 blockhash，组装一笔原生 SOL 转账消息。
func (c *Client) Prepare(ctx context.Context, from, to, amount string, units *big.Int, asset domain.Asset) (domain.PrepareResult, error) {
	if asset.Kind != "native" {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "Solana 代币转账还没接到签名器", "USDT/USDC 可以查余额。转账教学路径先覆盖 SOL 原生转账，SPL Token 需要关联账户。")
	}
	lamports, err := txbuild.MustUint64(units)
	if err != nil || lamports == 0 {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "金额超出范围", "")
	}
	// blockhash 大约一分钟过期。过期后要重新报价再签，不能拿旧 message 反复广播。
	raw, err := c.call(ctx, "getLatestBlockhash", []any{map[string]string{"commitment": "confirmed"}})
	if err != nil {
		return domain.PrepareResult{}, err
	}
	var parsed struct {
		Value struct {
			Blockhash string `json:"blockhash"`
		} `json:"value"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil || parsed.Value.Blockhash == "" {
		return domain.PrepareResult{}, fmt.Errorf("节点没有返回 blockhash")
	}
	msg, err := txbuild.BuildSOLTransfer(from, to, lamports, parsed.Value.Blockhash)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	return domain.PrepareResult{
		Chain:   domain.Solana,
		Network: c.network,
		Mode:    "live",
		Asset:   "SOL",
		From:    from,
		To:      to,
		Amount:  amount,
		Fee: domain.Fee{
			Symbol: "SOL",
			Amount: "0.000005",
			Detail: map[string]string{"lamports": "5000"},
		},
		Payload: domain.SOLPayload{
			Message:   base64.StdEncoding.EncodeToString(msg),
			Blockhash: parsed.Value.Blockhash,
			Lamports:  units.String(),
			From:      from,
			To:        to,
		},
		Warnings: []string{
			"blockhash 大约一分钟失效，签完尽快广播。",
			"向新地址转账时，金额通常还要覆盖租金豁免（约 0.00089 SOL），否则节点会拒绝。",
		},
	}, nil
}

// 把 base64 交易发给 sendTransaction。
func (c *Client) Broadcast(ctx context.Context, signed string) (string, error) {
	raw, err := c.call(ctx, "sendTransaction", []any{signed, map[string]any{"encoding": "base64"}})
	if err != nil {
		return "", err
	}
	var sig string
	if err := json.Unmarshal(raw, &sig); err != nil || sig == "" {
		return "", fmt.Errorf("节点没有返回签名")
	}
	return sig, nil
}

// 用 getSignatureStatuses 看确认进度。
func (c *Client) Status(ctx context.Context, hash string) (domain.TxState, error) {
	raw, err := c.call(ctx, "getSignatureStatuses", []any{[]string{hash}, map[string]any{"searchTransactionHistory": true}})
	if err != nil {
		return domain.TxState{}, err
	}
	var parsed struct {
		Value []*struct {
			ConfirmationStatus string `json:"confirmationStatus"`
			Err                any    `json:"err"`
			Slot               uint64 `json:"slot"`
		} `json:"value"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return domain.TxState{}, err
	}
	if len(parsed.Value) == 0 || parsed.Value[0] == nil {
		return domain.TxState{State: "not_found", Hash: hash, Detail: "节点还没有这个签名。blockhash 过期时也会这样。"}, nil
	}
	row := parsed.Value[0]
	if row.Err != nil {
		return domain.TxState{State: "failed", Hash: hash, Detail: fmt.Sprint(row.Err)}, nil
	}
	return domain.TxState{State: "confirmed", Hash: hash, Block: fmt.Sprintf("%d", row.Slot), Detail: row.ConfirmationStatus}, nil
}

// 列出 Solana 控制台允许的查询。
func (c *Client) Probes() []domain.Probe {
	return []domain.Probe{
		{Name: "slot", Title: "当前 slot", Description: "getSlot"},
		{Name: "blockhash", Title: "最新 blockhash", Description: "getLatestBlockhash"},
		{Name: "balance", Title: "地址余额", Description: "getBalance，需要 address"},
	}
}

// 执行白名单里的 Solana 查询。
func (c *Client) RunProbe(ctx context.Context, name string, args map[string]string) (any, error) {
	switch name {
	case "slot":
		return c.call(ctx, "getSlot", []any{})
	case "blockhash":
		return c.call(ctx, "getLatestBlockhash", []any{map[string]string{"commitment": "confirmed"}})
	case "balance":
		address := strings.TrimSpace(args["address"])
		if address == "" {
			return nil, domain.E("INVALID_ARGUMENT", "缺少 address", "填一个 Solana 地址。")
		}
		return c.call(ctx, "getBalance", []any{address})
	default:
		return nil, domain.E("NOT_FOUND", "没有这个探针", "")
	}
}

// 发送一次 Solana JSON-RPC。
func (c *Client) call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	var envelope struct {
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	body := map[string]any{"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
	if err := c.http.PostJSON(ctx, c.rpc, body, &envelope); err != nil {
		return nil, err
	}
	if envelope.Error != nil {
		return nil, fmt.Errorf("%s: %s", method, envelope.Error.Message)
	}
	return envelope.Result, nil
}
