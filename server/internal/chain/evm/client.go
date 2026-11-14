// 以太坊和 BNB 共用这套 JSON-RPC。报价走 EIP-1559：estimateGas 乘 1.2，maxFee 取两倍 gasPrice 再加 tip，nonce 用 pending。
package evm

import (
	"context"
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
	chain   domain.ChainID
	network string
	rpc     string
	http    *httpx.Client
}

// 创建一个以太坊或 BNB 的 JSON-RPC 客户端。
func New(chain domain.ChainID, network, rpc string, http *httpx.Client) *Client {
	return &Client{chain: chain, network: network, rpc: rpc, http: http}
}

// 返回这条客户端对应的链。
func (c *Client) ID() domain.ChainID { return c.chain }

// 用 eth_blockNumber 看节点通不通，并记下耗时。
func (c *Client) Health(ctx context.Context) domain.Health {
	start := time.Now()
	h := domain.Health{Chain: c.chain, Name: domain.ChainName(c.chain), Endpoint: httpx.Host(c.rpc), Mode: "live"}
	raw, err := c.call(ctx, "eth_blockNumber", []any{})
	h.LatencyMS = time.Since(start).Milliseconds()
	if err != nil {
		h.Error = fault.Explain(err)
		return h
	}
	h.OK = true
	h.Block = hexQuantity(raw)
	return h
}

// 查原生币余额，或用 balanceOf 查 ERC-20。
func (c *Client) Balance(ctx context.Context, address string, asset domain.Asset) (string, error) {
	if asset.Kind == "token" {
		if asset.Contract == "" {
			return "0", nil
		}
		data, err := txbuild.ERC20BalanceOfData(address)
		if err != nil {
			return "", err
		}
		raw, err := c.call(ctx, "eth_call", []any{map[string]string{"to": asset.Contract, "data": data}, "latest"})
		if err != nil {
			return "", err
		}
		n, err := parseHex(raw)
		if err != nil {
			return "", err
		}
		return domain.FormatUnits(n, asset.Decimals), nil
	}
	raw, err := c.call(ctx, "eth_getBalance", []any{address, "latest"})
	if err != nil {
		return "", err
	}
	n, err := parseHex(raw)
	if err != nil {
		return "", err
	}
	return domain.FormatUnits(n, asset.Decimals), nil
}

// 估算 gas、费用和 pending nonce，拼出未签名的 EIP-1559 交易。
func (c *Client) Prepare(ctx context.Context, from, to, amount string, units *big.Int, asset domain.Asset) (domain.PrepareResult, error) {
	var warnings []string
	call := map[string]string{"from": from}
	value := big.NewInt(0)
	data := "0x"
	target := to
	if asset.Kind == "native" {
		value = units
		call["value"] = quantityHex(value)
	} else {
		if asset.Contract == "" {
			return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "当前网络不能转这个代币", asset.Note)
		}
		encoded, err := txbuild.ERC20TransferData(to, units)
		if err != nil {
			return domain.PrepareResult{}, err
		}
		data = encoded
		target = asset.Contract
		warnings = append(warnings, "代币转账的 gas 用原生币支付，目标地址要有对应代币余额。")
	}
	call["to"] = target
	if data != "0x" {
		call["data"] = data
	}
	gasRaw, err := c.call(ctx, "eth_estimateGas", []any{call})
	if err != nil {
		return domain.PrepareResult{}, err
	}
	gas, err := parseHex(gasRaw)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	// 估算值再留 20%。代币转账的真实消耗常比 eth_estimateGas 略高。
	gas = new(big.Int).Mul(gas, big.NewInt(12))
	gas.Div(gas, big.NewInt(10))
	if gas.Cmp(big.NewInt(2_000_000)) > 0 {
		warnings = append(warnings, "估算的 gas 很高，先确认没有转到错误的合约。")
	}

	gasPriceRaw, err := c.call(ctx, "eth_gasPrice", []any{})
	if err != nil {
		return domain.PrepareResult{}, err
	}
	gasPrice, err := parseHex(gasPriceRaw)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	tip := new(big.Int).Div(new(big.Int).Set(gasPrice), big.NewInt(10))
	if tipRaw, tipErr := c.call(ctx, "eth_maxPriorityFeePerGas", []any{}); tipErr == nil {
		if parsed, err := parseHex(tipRaw); err == nil && parsed.Sign() > 0 {
			tip = parsed
		}
	}
	maxFee := new(big.Int).Add(new(big.Int).Mul(gasPrice, big.NewInt(2)), tip)
	if tip.Cmp(maxFee) > 0 {
		tip = new(big.Int).Set(maxFee)
	}
	feeWei := new(big.Int).Mul(gas, maxFee)

	nonceRaw, err := c.call(ctx, "eth_getTransactionCount", []any{from, "pending"})
	if err != nil {
		return domain.PrepareResult{}, err
	}
	nonce, err := parseHex(nonceRaw)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	chainRaw, err := c.call(ctx, "eth_chainId", []any{})
	if err != nil {
		return domain.PrepareResult{}, err
	}
	chainID, err := parseHex(chainRaw)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	warnings = append(warnings, "报价来自节点。签名在浏览器完成，服务器看不到私钥。")
	return domain.PrepareResult{
		Chain:   c.chain,
		Network: c.network,
		Mode:    "live",
		Asset:   asset.Symbol,
		From:    from,
		To:      to,
		Amount:  amount,
		Fee: domain.Fee{
			Symbol: domain.NativeSymbol(c.chain),
			Amount: domain.FormatUnits(feeWei, 18),
			Detail: map[string]string{
				"gasLimit":             gas.String(),
				"maxFeePerGas":         maxFee.String(),
				"maxPriorityFeePerGas": tip.String(),
			},
		},
		Payload: domain.EVMPayload{
			Type:                 2,
			ChainID:              chainID.String(),
			Nonce:                nonce.String(),
			To:                   target,
			Value:                value.String(),
			Data:                 data,
			GasLimit:             gas.String(),
			MaxFeePerGas:         maxFee.String(),
			MaxPriorityFeePerGas: tip.String(),
		},
		Warnings: warnings,
	}, nil
}

// 调用 eth_sendRawTransaction 广播已签名交易。
func (c *Client) Broadcast(ctx context.Context, signed string) (string, error) {
	if !strings.HasPrefix(signed, "0x") {
		signed = "0x" + signed
	}
	raw, err := c.call(ctx, "eth_sendRawTransaction", []any{signed})
	if err != nil {
		return "", err
	}
	var hash string
	if err := json.Unmarshal(raw, &hash); err != nil {
		return "", err
	}
	if hash == "" {
		return "", fmt.Errorf("节点没有返回交易哈希")
	}
	return hash, nil
}

// 用回执判断成功、失败、待打包或查无此交易。
func (c *Client) Status(ctx context.Context, hash string) (domain.TxState, error) {
	raw, err := c.call(ctx, "eth_getTransactionReceipt", []any{hash})
	if err != nil {
		return domain.TxState{}, err
	}
	if len(raw) == 0 || string(raw) == "null" {
		tx, txErr := c.call(ctx, "eth_getTransactionByHash", []any{hash})
		if txErr == nil && len(tx) > 0 && string(tx) != "null" {
			return domain.TxState{State: "pending", Hash: hash, Detail: "交易已进内存池，还没有回执。"}, nil
		}
		return domain.TxState{State: "not_found", Hash: hash, Detail: "节点还看不到这笔交易。"}, nil
	}
	var receipt struct {
		Status      string `json:"status"`
		BlockNumber string `json:"blockNumber"`
		GasUsed     string `json:"gasUsed"`
	}
	if err := json.Unmarshal(raw, &receipt); err != nil {
		return domain.TxState{}, err
	}
	if receipt.Status == "0x0" {
		return domain.TxState{State: "failed", Hash: hash, Block: hexToBig(receipt.BlockNumber).String(), Detail: "回执状态为 0，执行失败。"}, nil
	}
	return domain.TxState{State: "confirmed", Hash: hash, Block: hexToBig(receipt.BlockNumber).String(), Detail: "回执状态为 1。"}, nil
}

// 列出允许调用的节点方法，不接受任意 URL。
func (c *Client) Probes() []domain.Probe {
	return []domain.Probe{
		{Name: "blockNumber", Title: "最新区块", Description: "eth_blockNumber"},
		{Name: "chainId", Title: "链 ID", Description: "eth_chainId，用来确认没有连错网"},
		{Name: "gasPrice", Title: "Gas 价格", Description: "eth_gasPrice"},
		{Name: "balance", Title: "地址余额", Description: "eth_getBalance，需要 address"},
	}
}

// 按白名单执行一次节点查询。
func (c *Client) RunProbe(ctx context.Context, name string, args map[string]string) (any, error) {
	switch name {
	case "blockNumber":
		return c.call(ctx, "eth_blockNumber", []any{})
	case "chainId":
		return c.call(ctx, "eth_chainId", []any{})
	case "gasPrice":
		return c.call(ctx, "eth_gasPrice", []any{})
	case "balance":
		address := strings.TrimSpace(args["address"])
		if address == "" {
			return nil, domain.E("INVALID_ARGUMENT", "缺少 address", "填一个 0x 地址再查余额。")
		}
		return c.call(ctx, "eth_getBalance", []any{address, "latest"})
	default:
		return nil, domain.E("NOT_FOUND", "没有这个探针", "只开放白名单方法，避免把后端变成任意 RPC 代理。")
	}
}

// 发送一次 JSON-RPC，并把节点错误原样带回。
func (c *Client) call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	var envelope struct {
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Code    int    `json:"code"`
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

// 把节点返回的 0x 数量解析成整数。
func parseHex(raw json.RawMessage) (*big.Int, error) {
	var s string
	if err := json.Unmarshal(raw, &s); err != nil {
		return nil, fmt.Errorf("节点返回的不是十六进制数量")
	}
	return hexToBig(s), nil
}

// quantityHex 必须输出 0x 十六进制。big.Int 的 JSON 是十进制，不能直接塞进 eth_call。
func quantityHex(n *big.Int) string {
	if n == nil || n.Sign() == 0 {
		return "0x0"
	}
	return "0x" + n.Text(16)
}

// 把十六进制字符串转成大整数，空值当 0。
func hexToBig(s string) *big.Int {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, "0x")
	if s == "" {
		return big.NewInt(0)
	}
	n, ok := new(big.Int).SetString(s, 16)
	if !ok {
		return big.NewInt(0)
	}
	return n
}

// 从 JSON 里取出 0x 数量字符串。
func hexQuantity(raw json.RawMessage) string {
	n, err := parseHex(raw)
	if err != nil {
		return strings.Trim(string(raw), `"`)
	}
	return n.String()
}
