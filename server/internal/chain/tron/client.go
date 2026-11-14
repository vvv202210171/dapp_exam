// TRON 的 HTTP 钱包接口。TRC20 用 triggersmartcontract，浏览器对 txID 做 secp256k1 签名。
package tron

import (
	"context"
	"encoding/hex"
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
	base    string
	http    *httpx.Client
}

// 创建一个 TRON HTTP 钱包客户端。
func New(network, base string, http *httpx.Client) *Client {
	return &Client{network: network, base: strings.TrimRight(base, "/"), http: http}
}

// 返回 TRON。
func (c *Client) ID() domain.ChainID { return domain.Tron }

// 读取当前区块高度。
func (c *Client) Health(ctx context.Context) domain.Health {
	start := time.Now()
	h := domain.Health{Chain: domain.Tron, Name: "TRON", Endpoint: httpx.Host(c.base), Mode: "live"}
	var block struct {
		BlockHeader struct {
			RawData struct {
				Number int64 `json:"number"`
			} `json:"raw_data"`
		} `json:"block_header"`
	}
	err := c.http.PostJSON(ctx, c.base+"/wallet/getnowblock", map[string]any{}, &block)
	h.LatencyMS = time.Since(start).Milliseconds()
	if err != nil {
		h.Error = fault.Explain(err)
		return h
	}
	if block.BlockHeader.RawData.Number == 0 {
		h.Error = domain.E("CHAIN_ERROR", "节点没有返回区块", "确认 TRON API 地址，主网和 Shasta 不能混。")
		return h
	}
	h.OK = true
	h.Block = fmt.Sprintf("%d", block.BlockHeader.RawData.Number)
	return h
}

// 查 TRX 余额，或用 balanceOf 查 TRC-20。
func (c *Client) Balance(ctx context.Context, address string, asset domain.Asset) (string, error) {
	if asset.Kind == "native" {
		var account struct {
			Balance int64 `json:"balance"`
		}
		if err := c.http.PostJSON(ctx, c.base+"/wallet/getaccount", map[string]any{"address": address, "visible": true}, &account); err != nil {
			return "", err
		}
		return domain.FormatUnits(big.NewInt(account.Balance), 6), nil
	}
	if asset.Contract == "" {
		return "0", nil
	}
	param, err := txbuild.TRC20Parameter(address, big.NewInt(0))
	if err != nil {
		// balanceOf uses the holder address in the same ABI word layout.
		return "", err
	}
	// TRC20Parameter encodes transfer(address,uint256). For balanceOf we only need the address word.
	addrWord := param[:64]
	var out struct {
		ConstantResult []string `json:"constant_result"`
		Result         struct {
			Result  bool   `json:"result"`
			Message string `json:"message"`
		} `json:"result"`
	}
	body := map[string]any{
		"owner_address":     address,
		"contract_address":  asset.Contract,
		"function_selector": "balanceOf(address)",
		"parameter":         addrWord,
		"visible":           true,
	}
	if err := c.http.PostJSON(ctx, c.base+"/wallet/triggerconstantcontract", body, &out); err != nil {
		return "", err
	}
	if len(out.ConstantResult) == 0 {
		return "0", nil
	}
	n, ok := new(big.Int).SetString(out.ConstantResult[0], 16)
	if !ok {
		return "0", nil
	}
	return domain.FormatUnits(n, asset.Decimals), nil
}

// 创建 TRX 转账或 TRC-20 合约调用，返回待签名的 txID。
func (c *Client) Prepare(ctx context.Context, from, to, amount string, units *big.Int, asset domain.Asset) (domain.PrepareResult, error) {
	if asset.Kind == "native" {
		sun, err := txbuild.MustInt64(units)
		if err != nil || sun <= 0 {
			return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "金额超出范围", "")
		}
		var tx map[string]any
		err = c.http.PostJSON(ctx, c.base+"/wallet/createtransaction", map[string]any{
			"owner_address": from,
			"to_address":    to,
			"amount":        sun,
			"visible":       true,
		}, &tx)
		if err != nil {
			return domain.PrepareResult{}, err
		}
		if msg, bad := tronFailed(tx); bad {
			return domain.PrepareResult{}, fmt.Errorf("%s", msg)
		}
		raw, _ := json.Marshal(tx)
		return c.finish("TRX", "0", from, to, amount, raw, txIDOf(tx), map[string]string{"resource": "bandwidth"}, []string{
			"TRX 转账优先消耗带宽。带宽不够时才会燃烧少量 TRX。",
			"付款地址如果从未收过 TRX，需要先激活。",
		})
	}
	if asset.Contract == "" {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "当前网络不能转这个代币", asset.Note)
	}
	param, err := txbuild.TRC20Parameter(to, units)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	var wrapper struct {
		Result struct {
			Result  bool   `json:"result"`
			Message string `json:"message"`
		} `json:"result"`
		Transaction map[string]any `json:"transaction"`
	}
	err = c.http.PostJSON(ctx, c.base+"/wallet/triggersmartcontract", map[string]any{
		"owner_address":     from,
		"contract_address":  asset.Contract,
		"function_selector": "transfer(address,uint256)",
		"parameter":         param,
		"fee_limit":         100_000_000, // 100 TRX，能量不够时燃烧 TRX 的上限
		"call_value":        0,
		"visible":           true,
	}, &wrapper)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	if !wrapper.Result.Result {
		return domain.PrepareResult{}, fmt.Errorf("%s", decodeMaybeHex(wrapper.Result.Message))
	}
	raw, _ := json.Marshal(wrapper.Transaction)
	return c.finish(asset.Symbol, "15", from, to, amount, raw, txIDOf(wrapper.Transaction), map[string]string{"feeLimitSun": "100000000"}, []string{
		"TRC20 手续费取决于能量。15 TRX 是常见估算，不是链上报价。能量不足时会燃烧 TRX，上限是 fee_limit。",
	})
}

// 把节点返回的交易收成统一的报价结果。
func (c *Client) finish(asset, fee, from, to, amount string, raw []byte, txID string, detail map[string]string, warnings []string) (domain.PrepareResult, error) {
	if txID == "" {
		return domain.PrepareResult{}, fmt.Errorf("节点没有返回 txID")
	}
	warnings = append(warnings, "浏览器对 txID 做 secp256k1 签名，再把 signature 填回交易。节点负责验签。")
	return domain.PrepareResult{
		Chain:   domain.Tron,
		Network: c.network,
		Mode:    "live",
		Asset:   asset,
		From:    from,
		To:      to,
		Amount:  amount,
		Fee: domain.Fee{
			Symbol: "TRX",
			Amount: fee,
			Detail: detail,
		},
		Payload:  domain.TRONPayload{TxID: txID, Transaction: raw},
		Warnings: warnings,
	}, nil
}

// 提交带签名的交易 JSON。
func (c *Client) Broadcast(ctx context.Context, signed string) (string, error) {
	var tx map[string]any
	if err := json.Unmarshal([]byte(signed), &tx); err != nil {
		return "", domain.E("INVALID_ARGUMENT", "TRON 签名结果必须是交易 JSON", "应包含 txID 和 signature。")
	}
	var out struct {
		Result  bool   `json:"result"`
		TxID    string `json:"txid"`
		Code    string `json:"code"`
		Message string `json:"message"`
	}
	if err := c.http.PostJSON(ctx, c.base+"/wallet/broadcasttransaction", tx, &out); err != nil {
		return "", err
	}
	if !out.Result {
		msg := decodeMaybeHex(out.Message)
		if msg == "" {
			msg = out.Code
		}
		return "", fmt.Errorf("%s", msg)
	}
	if out.TxID == "" {
		if id, _ := tx["txID"].(string); id != "" {
			return id, nil
		}
	}
	return out.TxID, nil
}

// 按交易号判断成功、失败或仍未上链。
func (c *Client) Status(ctx context.Context, hash string) (domain.TxState, error) {
	var info struct {
		ID          string `json:"id"`
		BlockNumber int64  `json:"blockNumber"`
		Result      string `json:"result"`
		Receipt     struct {
			Result string `json:"result"`
		} `json:"receipt"`
	}
	if err := c.http.PostJSON(ctx, c.base+"/wallet/gettransactioninfobyid", map[string]any{"value": hash}, &info); err != nil {
		return domain.TxState{}, err
	}
	if info.ID == "" && info.BlockNumber == 0 {
		return domain.TxState{State: "not_found", Hash: hash, Detail: "节点还没有这笔交易的回执。"}, nil
	}
	result := strings.ToUpper(info.Result + info.Receipt.Result)
	if strings.Contains(result, "FAIL") || strings.Contains(result, "REVERT") {
		return domain.TxState{State: "failed", Hash: hash, Block: fmt.Sprintf("%d", info.BlockNumber), Detail: "链上执行失败。"}, nil
	}
	if info.BlockNumber > 0 {
		return domain.TxState{State: "confirmed", Hash: hash, Block: fmt.Sprintf("%d", info.BlockNumber)}, nil
	}
	return domain.TxState{State: "pending", Hash: hash}, nil
}

// 列出 TRON 控制台允许的查询。
func (c *Client) Probes() []domain.Probe {
	return []domain.Probe{
		{Name: "block", Title: "最新区块", Description: "wallet/getnowblock"},
		{Name: "account", Title: "账户", Description: "wallet/getaccount，需要 address"},
	}
}

// 执行白名单里的 TRON 查询。
func (c *Client) RunProbe(ctx context.Context, name string, args map[string]string) (any, error) {
	switch name {
	case "block":
		var out any
		err := c.http.PostJSON(ctx, c.base+"/wallet/getnowblock", map[string]any{}, &out)
		return out, err
	case "account":
		address := strings.TrimSpace(args["address"])
		if address == "" {
			return nil, domain.E("INVALID_ARGUMENT", "缺少 address", "填一个 T 开头的地址。")
		}
		var out any
		err := c.http.PostJSON(ctx, c.base+"/wallet/getaccount", map[string]any{"address": address, "visible": true}, &out)
		return out, err
	default:
		return nil, domain.E("NOT_FOUND", "没有这个探针", "")
	}
}

// 从交易 JSON 里取出 txID。
func txIDOf(tx map[string]any) string {
	id, _ := tx["txID"].(string)
	return id
}

// 看交易结果里有没有失败原因。
func tronFailed(tx map[string]any) (string, bool) {
	if tx == nil {
		return "空响应", true
	}
	if errText, ok := tx["Error"].(string); ok && errText != "" {
		return errText, true
	}
	if _, ok := tx["txID"]; !ok {
		return "节点没有返回可签名交易", true
	}
	return "", false
}

// 节点有时返回十六进制错误信息，这里尽量解成可读文本。
func decodeMaybeHex(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if b, err := hex.DecodeString(s); err == nil && len(b) > 0 {
		text := string(b)
		if isMostlyPrintable(text) {
			return text
		}
	}
	return s
}

// 判断一段文本是不是适合直接展示。
func isMostlyPrintable(s string) bool {
	for _, r := range s {
		if r < 32 || r > 126 {
			return false
		}
	}
	return true
}
