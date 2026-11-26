// Solana JSON-RPC。原生 SOL 走系统程序，USDT 和 USDC 走 SPL 关联账户。
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
		return c.prepareToken(ctx, from, to, amount, units, asset)
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

// 报价一笔 SPL 转账。收款人没有关联账户时，消息里会先创建它。
func (c *Client) prepareToken(ctx context.Context, from, to, amount string, units *big.Int, asset domain.Asset) (domain.PrepareResult, error) {
	if asset.Contract == "" {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "当前网络不能转这个代币", asset.Note)
	}
	value, err := txbuild.MustUint64(units)
	if err != nil || value == 0 {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "金额超出范围", "")
	}
	if from == to {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "不能把代币转给自己", "付款钱包和收款钱包要是两个地址。")
	}
	sourceAta, err := txbuild.AssociatedTokenAddress(from, asset.Contract)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	destAta, err := txbuild.AssociatedTokenAddress(to, asset.Contract)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	raw, err := c.call(ctx, "getTokenAccountsByOwner", []any{from, map[string]string{"mint": asset.Contract}, map[string]string{"encoding": "jsonParsed"}})
	if err != nil {
		return domain.PrepareResult{}, err
	}
	var rows struct {
		Value []struct {
			Pubkey  string `json:"pubkey"`
			Account struct {
				Owner string `json:"owner"`
				Data  struct {
					Parsed struct {
						Info struct {
							TokenAmount struct {
								Amount   string `json:"amount"`
								Decimals int    `json:"decimals"`
							} `json:"tokenAmount"`
						} `json:"info"`
					} `json:"parsed"`
				} `json:"data"`
			} `json:"account"`
		} `json:"value"`
	}
	if err := json.Unmarshal(raw, &rows); err != nil {
		return domain.PrepareResult{}, err
	}
	source := ""
	decimals := asset.Decimals
	fallback := ""
	fallbackDecimals := decimals
	for _, row := range rows.Value {
		if row.Account.Owner != txbuild.TokenProgram {
			continue
		}
		balance, ok := new(big.Int).SetString(row.Account.Data.Parsed.Info.TokenAmount.Amount, 10)
		if !ok || balance.Cmp(units) < 0 {
			continue
		}
		if row.Pubkey == sourceAta {
			source = row.Pubkey
			decimals = row.Account.Data.Parsed.Info.TokenAmount.Decimals
			break
		}
		if fallback == "" {
			fallback = row.Pubkey
			fallbackDecimals = row.Account.Data.Parsed.Info.TokenAmount.Decimals
		}
	}
	if source == "" {
		source = fallback
		decimals = fallbackDecimals
	}
	if source == "" {
		return domain.PrepareResult{}, domain.E("INSUFFICIENT_FUNDS", "代币余额不够", "先确认这个地址的 "+asset.Symbol+" 余额。手续费另用 SOL 支付。")
	}
	if decimals != asset.Decimals {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "链上小数位和配置不一致", fmt.Sprintf("这条代币在链上是 %d 位小数。", decimals))
	}
	info, err := c.call(ctx, "getAccountInfo", []any{destAta, map[string]string{"encoding": "base64"}})
	if err != nil {
		return domain.PrepareResult{}, err
	}
	var account struct {
		Value *json.RawMessage `json:"value"`
	}
	if err := json.Unmarshal(info, &account); err != nil {
		return domain.PrepareResult{}, err
	}
	create := account.Value == nil || string(*account.Value) == "null"
	hashRaw, err := c.call(ctx, "getLatestBlockhash", []any{map[string]string{"commitment": "confirmed"}})
	if err != nil {
		return domain.PrepareResult{}, err
	}
	var parsed struct {
		Value struct {
			Blockhash string `json:"blockhash"`
		} `json:"value"`
	}
	if err := json.Unmarshal(hashRaw, &parsed); err != nil || parsed.Value.Blockhash == "" {
		return domain.PrepareResult{}, fmt.Errorf("节点没有返回 blockhash")
	}
	msg, err := txbuild.BuildSOLTokenTransfer(from, source, destAta, to, asset.Contract, value, decimals, create, parsed.Value.Blockhash)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	warnings := []string{"blockhash 大约一分钟失效，签完尽快广播。", "代币转账的手续费用 SOL 支付。"}
	if create {
		warnings = append(warnings, "对方还没有这个币的关联账户，这笔交易会创建它，大约再花 0.00204 SOL。")
	}
	return domain.PrepareResult{
		Chain:   domain.Solana,
		Network: c.network,
		Mode:    "live",
		Asset:   asset.Symbol,
		From:    from,
		To:      to,
		Amount:  amount,
		Fee:     domain.Fee{Symbol: "SOL", Amount: "0.000005", Detail: map[string]string{"lamports": "5000"}},
		Payload: domain.SOLPayload{
			Message:   base64.StdEncoding.EncodeToString(msg),
			Blockhash: parsed.Value.Blockhash,
			Lamports:  "0",
			From:      from,
			To:        to,
		},
		Warnings: warnings,
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
