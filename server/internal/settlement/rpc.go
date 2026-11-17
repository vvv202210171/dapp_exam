// 只连本地 Hardhat。用户交易走已签名的 raw tx；运营方交易走节点已解锁的部署账户。
package settlement

import (
	"context"
	"encoding/json"
	"fmt"
	"math/big"
	"strings"
	"time"

	"aegis/internal/domain"
	"aegis/internal/platform/httpx"
)

type RPC struct {
	url  string
	http *httpx.Client
}

// 绑定一个 JSON-RPC 地址。
func newRPC(url string, http *httpx.Client) *RPC {
	return &RPC{url: url, http: http}
}

// 读取 chainId，用来确认没有连到主网。
func (c *RPC) ChainID(ctx context.Context) (*big.Int, error) {
	raw, err := c.call(ctx, "eth_chainId", []any{})
	if err != nil {
		return nil, err
	}
	return parseHex(raw)
}

// 读取地址上的合约字节码。空字节码说明 Hardhat 重启后旧地址已经失效。
func (c *RPC) Code(ctx context.Context, address string) (string, error) {
	raw, err := c.call(ctx, "eth_getCode", []any{address, "latest"})
	if err != nil {
		return "", err
	}
	var code string
	if err := json.Unmarshal(raw, &code); err != nil {
		return "", err
	}
	return code, nil
}

// 读取原生币余额。
func (c *RPC) NativeBalance(ctx context.Context, address string) (*big.Int, error) {
	raw, err := c.call(ctx, "eth_getBalance", []any{address, "latest"})
	if err != nil {
		return nil, err
	}
	return parseHex(raw)
}

// 用 eth_call 读一个 uint256。
func (c *RPC) CallUint(ctx context.Context, to, data string) (*big.Int, error) {
	raw, err := c.call(ctx, "eth_call", []any{map[string]string{"to": to, "data": data}, "latest"})
	if err != nil {
		return nil, err
	}
	return parseHex(raw)
}

// 按 EIP-1559 给一笔 calldata 报价。签名仍在浏览器完成。
func (c *RPC) Quote(ctx context.Context, from, to, data string) (domain.EVMPayload, domain.Fee, error) {
	call := map[string]string{"from": from, "to": to, "data": data}
	gasRaw, err := c.call(ctx, "eth_estimateGas", []any{call})
	if err != nil {
		return domain.EVMPayload{}, domain.Fee{}, err
	}
	gas, err := parseHex(gasRaw)
	if err != nil {
		return domain.EVMPayload{}, domain.Fee{}, err
	}
	gas = new(big.Int).Mul(gas, big.NewInt(12))
	gas.Div(gas, big.NewInt(10))

	gasPriceRaw, err := c.call(ctx, "eth_gasPrice", []any{})
	if err != nil {
		return domain.EVMPayload{}, domain.Fee{}, err
	}
	gasPrice, err := parseHex(gasPriceRaw)
	if err != nil {
		return domain.EVMPayload{}, domain.Fee{}, err
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

	nonceRaw, err := c.call(ctx, "eth_getTransactionCount", []any{from, "pending"})
	if err != nil {
		return domain.EVMPayload{}, domain.Fee{}, err
	}
	nonce, err := parseHex(nonceRaw)
	if err != nil {
		return domain.EVMPayload{}, domain.Fee{}, err
	}
	chainID, err := c.ChainID(ctx)
	if err != nil {
		return domain.EVMPayload{}, domain.Fee{}, err
	}
	feeWei := new(big.Int).Mul(gas, maxFee)
	return domain.EVMPayload{
			Type:                 2,
			ChainID:              chainID.String(),
			Nonce:                nonce.String(),
			To:                   to,
			Value:                "0",
			Data:                 data,
			GasLimit:             gas.String(),
			MaxFeePerGas:         maxFee.String(),
			MaxPriorityFeePerGas: tip.String(),
		}, domain.Fee{
			Symbol: "ETH",
			Amount: domain.FormatUnits(feeWei, 18),
			Detail: map[string]string{
				"gasLimit":             gas.String(),
				"maxFeePerGas":         maxFee.String(),
				"maxPriorityFeePerGas": tip.String(),
			},
		}, nil
}

// 读取已上链交易的发送人、目标和 calldata。
func (c *RPC) Transaction(ctx context.Context, hash string) (from, to, input string, err error) {
	raw, err := c.call(ctx, "eth_getTransactionByHash", []any{hash})
	if err != nil {
		return "", "", "", err
	}
	if len(raw) == 0 || string(raw) == "null" {
		return "", "", "", domain.E("NOT_FOUND", "节点上看不到这笔交易", "等小狐狸广播完成后再入账。")
	}
	var tx struct {
		From  string `json:"from"`
		To    string `json:"to"`
		Input string `json:"input"`
	}
	if err := json.Unmarshal(raw, &tx); err != nil {
		return "", "", "", err
	}
	return tx.From, tx.To, tx.Input, nil
}

// 广播浏览器签好的交易。
func (c *RPC) SendRaw(ctx context.Context, signed string) (string, error) {
	if !strings.HasPrefix(signed, "0x") {
		signed = "0x" + signed
	}
	raw, err := c.call(ctx, "eth_sendRawTransaction", []any{signed})
	if err != nil {
		return "", err
	}
	return jsonString(raw)
}

// 用节点上已解锁的运营方账户发交易。私钥不进这个进程，Hardhat 在本地替它保管。
func (c *RPC) SendFrom(ctx context.Context, from, to, value, data string) (string, error) {
	tx := map[string]string{"from": from, "to": to}
	if value != "" && value != "0x0" {
		tx["value"] = value
	}
	if data != "" && data != "0x" {
		tx["data"] = data
	}
	// eth_estimateGas 估算的 gas 可能不够，留点余量。
	gasRaw, err := c.call(ctx, "eth_estimateGas", []any{tx})
	if err != nil {
		return "", err
	}
	gas, err := parseHex(gasRaw)
	if err != nil {
		return "", err
	}
	// 留 20% 余量，避免 Hardhat 报错。
	gas = new(big.Int).Mul(gas, big.NewInt(12))
	gas.Div(gas, big.NewInt(10))
	//
	tx["gas"] = quantityHex(gas)
	//执行交易
	raw, err := c.call(ctx, "eth_sendTransaction", []any{tx})
	if err != nil {
		return "", err
	}
	return jsonString(raw)
}

// 等到回执。本地链出块很快，超时就告诉调用方还在内存池。
func (c *RPC) Wait(ctx context.Context, hash string) (string, error) {
	deadline := time.Now().Add(8 * time.Second)
	for {
		raw, err := c.call(ctx, "eth_getTransactionReceipt", []any{hash})
		if err != nil {
			return "", err
		}
		if len(raw) > 0 && string(raw) != "null" {
			var rec struct {
				Status string `json:"status"`
			}
			if err := json.Unmarshal(raw, &rec); err != nil {
				return "", err
			}
			if rec.Status == "0x1" {
				return "confirmed", nil
			}
			return "failed", domain.E("REVERTED", "交易已上链但执行失败", "常见原因：没 approve、USDC 不够、链上可用余额不够，或这张账单已经结算过。")
		}
		if time.Now().After(deadline) {
			return "pending", nil
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(250 * time.Millisecond):
		}
	}
}

// 发送一次 JSON-RPC，并把节点错误原样带回。
func (c *RPC) call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	var envelope struct {
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	body := map[string]any{"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
	if err := c.http.PostJSON(ctx, c.url, body, &envelope); err != nil {
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
	s = strings.TrimSpace(strings.TrimPrefix(s, "0x"))
	if s == "" {
		return big.NewInt(0), nil
	}
	n, ok := new(big.Int).SetString(s, 16)
	if !ok {
		return nil, fmt.Errorf("节点返回的不是十六进制数量")
	}
	return n, nil
}

// 把整数写成 0x 数量。
func quantityHex(n *big.Int) string {
	if n == nil || n.Sign() == 0 {
		return "0x0"
	}
	return "0x" + n.Text(16)
}

// 从 JSON 字符串里取出交易哈希。
func jsonString(raw json.RawMessage) (string, error) {
	var s string
	if err := json.Unmarshal(raw, &s); err != nil || s == "" {
		return "", fmt.Errorf("节点没有返回交易哈希")
	}
	return s, nil
}
