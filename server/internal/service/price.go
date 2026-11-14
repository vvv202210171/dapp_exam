// 美元估值。沙箱用固定价格，方便演示数字稳定；live 才请求 CoinGecko，并保留 60 秒缓存。
package service

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"aegis/internal/domain"
	"aegis/internal/platform/httpx"
)

type Pricer struct {
	mode   string
	http   *httpx.Client
	mu     sync.Mutex
	at     time.Time
	prices map[string]string
	source string
}

// 创建价格服务。沙箱从一开始就用静态价格。
func NewPricer(mode string, http *httpx.Client) *Pricer {
	return &Pricer{mode: mode, http: http, prices: staticPrices(), source: "static"}
}

// 返回当前价格。沙箱不访问网络，live 过期后才重新拉取。
func (p *Pricer) Snapshot(ctx context.Context) (map[string]string, string) {
	if p.mode == "sandbox" {
		return clonePrices(staticPrices()), "static"
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if time.Since(p.at) < time.Minute && p.prices != nil {
		return clonePrices(p.prices), p.source
	}
	prices, source := p.fetch(ctx)
	p.prices = prices
	p.source = source
	p.at = time.Now()
	return clonePrices(prices), source
}

// 向 CoinGecko 拉价格，失败时退回静态价格。
func (p *Pricer) fetch(ctx context.Context) (map[string]string, string) {
	cctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(cctx, http.MethodGet, "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,binancecoin,solana,tron&vs_currencies=usd", nil)
	if err != nil {
		return staticPrices(), "static-fallback"
	}
	resp, err := p.http.HTTP.Do(req)
	if err != nil {
		return staticPrices(), "static-fallback"
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return staticPrices(), "static-fallback"
	}
	var raw map[string]map[string]float64
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		return staticPrices(), "static-fallback"
	}
	out := staticPrices()
	set := func(id, symbol string) {
		if v, ok := raw[id]["usd"]; ok && v > 0 {
			out[symbol] = trimFloat(v)
		}
	}
	set("bitcoin", "BTC")
	set("ethereum", "ETH")
	set("binancecoin", "BNB")
	set("solana", "SOL")
	set("tron", "TRX")
	return out, "coingecko"
}

// 返回写死的演示价格。
func staticPrices() map[string]string {
	return map[string]string{
		"BTC":  "64000",
		"ETH":  "3200",
		"BNB":  "580",
		"SOL":  "145",
		"TRX":  "0.16",
		"USDT": "1",
		"USDC": "1",
	}
}

// 复制一份价格表，避免调用方改到缓存。
func clonePrices(in map[string]string) map[string]string {
	out := make(map[string]string, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}

// 把接口返回的小数收成两位美元字符串。
func trimFloat(v float64) string {
	cents := int64(v*100 + 0.5)
	return domain.FormatCents(cents)
}
