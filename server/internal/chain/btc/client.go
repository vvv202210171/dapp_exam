// 比特币走 mempool.space 的 REST，不用 JSON-RPC。只花已确认 UTXO，未确认的找零不能当输入。
package btc

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
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

// 创建一个走 mempool.space REST 的比特币客户端。
func New(network, base string, http *httpx.Client) *Client {
	return &Client{network: network, base: strings.TrimRight(base, "/"), http: http}
}

// 返回 Bitcoin。
func (c *Client) ID() domain.ChainID { return domain.Bitcoin }

// 读取最新区块高度，用来判断接口是否可用。
func (c *Client) Health(ctx context.Context) domain.Health {
	start := time.Now()
	h := domain.Health{Chain: domain.Bitcoin, Name: "Bitcoin", Endpoint: httpx.Host(c.base), Mode: "live"}
	height, err := c.http.GetText(ctx, c.base+"/blocks/tip/height")
	h.LatencyMS = time.Since(start).Milliseconds()
	if err != nil {
		h.Error = fault.Explain(err)
		return h
	}
	h.OK = true
	h.Block = height
	return h
}

// 把已确认 UTXO 加总成 BTC 余额。
func (c *Client) Balance(ctx context.Context, address string, asset domain.Asset) (string, error) {
	if asset.Kind != "native" {
		return "", domain.E("INVALID_ARGUMENT", "Bitcoin 适配器只查询原生 BTC", "USDT 不在比特币主链的这个接口里。")
	}
	utxos, err := c.utxos(ctx, address, false)
	if err != nil {
		return "", err
	}
	var total int64
	for _, u := range utxos {
		total += u.Value
	}
	return domain.FormatUnits(big.NewInt(total), 8), nil
}

// 选出已确认 UTXO，算手续费和找零，给出未签名输入输出。
func (c *Client) Prepare(ctx context.Context, from, to, amount string, units *big.Int, asset domain.Asset) (domain.PrepareResult, error) {
	if asset.Kind != "native" {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "不能在比特币主链上直接转 USDT", "稳定币请换 Ethereum、BNB、TRON 或 Solana。")
	}
	sats, err := txbuild.MustInt64(units)
	if err != nil || sats <= 0 {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "金额超出范围", "")
	}
	confirmed, err := c.utxos(ctx, from, true)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	if len(confirmed) == 0 {
		return domain.PrepareResult{}, domain.E("INSUFFICIENT_FUNDS", "没有已确认的 UTXO", "刚收到的比特币要等确认后才能转出。未确认的找零也不能花。")
	}
	var feeRate int64 = 8
	var fees struct {
		HalfHourFee int64 `json:"halfHourFee"`
	}
	if err := c.http.GetJSON(ctx, c.base+"/v1/fees/recommended", &fees); err != nil {
		return domain.PrepareResult{}, err
	}
	if fees.HalfHourFee > 0 {
		feeRate = fees.HalfHourFee
	}
	selected, fee, change, err := txbuild.SelectCoins(confirmed, sats, feeRate)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	outputs := []domain.BTCOutput{{Address: to, Value: sats}}
	if change > 0 {
		outputs = append(outputs, domain.BTCOutput{Address: from, Value: change})
	}
	return domain.PrepareResult{
		Chain:   domain.Bitcoin,
		Network: c.network,
		Mode:    "live",
		Asset:   "BTC",
		From:    from,
		To:      to,
		Amount:  amount,
		Fee: domain.Fee{
			Symbol: "BTC",
			Amount: domain.FormatUnits(big.NewInt(fee), 8),
			Detail: map[string]string{"feeRateSatVb": fmt.Sprintf("%d", feeRate), "inputs": fmt.Sprintf("%d", len(selected))},
		},
		Payload: domain.BTCPayload{Network: c.network, Inputs: selected, Outputs: outputs, FeeSats: fee},
		Warnings: []string{
			"手续费按 P2WPKH 体积估算并留了 10% 余量。只花费已确认 UTXO。",
			"签名在浏览器里完成。已签名交易任何人都可以广播。",
		},
	}, nil
}

// 把签名后的原始交易以纯文本提交到 /tx。
func (c *Client) Broadcast(ctx context.Context, signed string) (string, error) {
	signed = strings.TrimPrefix(strings.TrimSpace(signed), "0x")
	txid, err := c.http.PostText(ctx, c.base+"/tx", signed)
	if err != nil {
		return "", err
	}
	if len(txid) != 64 {
		return "", fmt.Errorf("节点返回的不是交易哈希: %s", txid)
	}
	return txid, nil
}

// 按交易哈希查询确认数和所在区块。
func (c *Client) Status(ctx context.Context, hash string) (domain.TxState, error) {
	var body struct {
		Confirmed   bool  `json:"confirmed"`
		BlockHeight int64 `json:"block_height"`
	}
	err := c.http.GetJSON(ctx, c.base+"/tx/"+hash+"/status", &body)
	if err != nil {
		if strings.Contains(err.Error(), "404") {
			return domain.TxState{State: "not_found", Hash: hash, Detail: "区块浏览器还没有这笔交易。"}, nil
		}
		return domain.TxState{}, err
	}
	if body.Confirmed {
		return domain.TxState{State: "confirmed", Hash: hash, Block: fmt.Sprintf("%d", body.BlockHeight)}, nil
	}
	return domain.TxState{State: "pending", Hash: hash, Detail: "交易已被看到，尚未确认。"}, nil
}

// 列出比特币控制台允许的查询。
func (c *Client) Probes() []domain.Probe {
	return []domain.Probe{
		{Name: "tip", Title: "链尖高度", Description: "GET /blocks/tip/height"},
		{Name: "fees", Title: "推荐费率", Description: "GET /v1/fees/recommended"},
		{Name: "utxo", Title: "地址 UTXO", Description: "GET /address/{address}/utxo"},
	}
}

// 执行白名单里的比特币查询。
func (c *Client) RunProbe(ctx context.Context, name string, args map[string]string) (any, error) {
	switch name {
	case "tip":
		return c.http.GetText(ctx, c.base+"/blocks/tip/height")
	case "fees":
		var out any
		err := c.http.GetJSON(ctx, c.base+"/v1/fees/recommended", &out)
		return out, err
	case "utxo":
		address := strings.TrimSpace(args["address"])
		if address == "" {
			return nil, domain.E("INVALID_ARGUMENT", "缺少 address", "填一个 bc1q 或 tb1q 地址。")
		}
		return c.utxos(ctx, address, false)
	default:
		return nil, domain.E("NOT_FOUND", "没有这个探针", "比特币不是 JSON-RPC 这条路径，走的是区块浏览器 HTTP API。")
	}
}

// 拉取地址的 UTXO，可按是否已确认过滤。
func (c *Client) utxos(ctx context.Context, address string, confirmedOnly bool) ([]domain.BTCInput, error) {
	var rows []struct {
		TxID   string `json:"txid"`
		Vout   uint32 `json:"vout"`
		Value  int64  `json:"value"`
		Status struct {
			Confirmed bool `json:"confirmed"`
		} `json:"status"`
	}
	if err := c.http.GetJSON(ctx, c.base+"/address/"+address+"/utxo", &rows); err != nil {
		return nil, err
	}
	out := make([]domain.BTCInput, 0, len(rows))
	for _, row := range rows {
		// 未确认 UTXO 不能花，否则交易会在内存池里因为输入不存在而失败。
		if confirmedOnly && !row.Status.Confirmed {
			continue
		}
		out = append(out, domain.BTCInput{TxID: row.TxID, Vout: row.Vout, Value: row.Value})
	}
	return out, nil
}

// 用地址哈希生成一个沙箱里的假交易号。
func SandboxTxID(address string) string {
	sum := sha256.Sum256([]byte("sandbox-utxo|" + strings.ToLower(address)))
	return hex.EncodeToString(sum[:])
}
