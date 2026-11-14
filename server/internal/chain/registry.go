// 按 MODE 选择适配器。沙箱失败时不会偷偷改去打真节点，避免把模拟余额说成链上余额。
package chain

import (
	"context"
	"sync"
	"time"

	"aegis/internal/chain/btc"
	"aegis/internal/chain/evm"
	"aegis/internal/chain/sol"
	"aegis/internal/chain/tron"
	"aegis/internal/config"
	"aegis/internal/domain"
	"aegis/internal/platform/httpx"
)

type Registry struct {
	order    []domain.ChainID
	adapters map[domain.ChainID]Adapter
	mode     string
	network  string
}

// 按 MODE 创建五条链的适配器，沙箱和 live 不会混用。
func NewRegistry(cfg config.Config, http *httpx.Client) *Registry {
	order := domain.Chains()
	adapters := make(map[domain.ChainID]Adapter, len(order))
	// live 才连公共 RPC。沙箱五条链都走本地适配器。
	if cfg.Mode == "sandbox" {
		for _, id := range order {
			adapters[id] = sandboxAdapter{chain: id, network: cfg.Network}
		}
	} else {
		adapters[domain.Bitcoin] = btc.New(cfg.Network, cfg.RPCs[domain.Bitcoin], http)
		adapters[domain.Ethereum] = evm.New(domain.Ethereum, cfg.Network, cfg.RPCs[domain.Ethereum], http)
		adapters[domain.BNB] = evm.New(domain.BNB, cfg.Network, cfg.RPCs[domain.BNB], http)
		adapters[domain.Solana] = sol.New(cfg.Network, cfg.RPCs[domain.Solana], http)
		adapters[domain.Tron] = tron.New(cfg.Network, cfg.RPCs[domain.Tron], http)
	}
	return &Registry{order: order, adapters: adapters, mode: cfg.Mode, network: cfg.Network}
}

// 返回当前是 sandbox 还是 live。
func (r *Registry) Mode() string { return r.mode }

// 返回当前是 testnet 还是 mainnet。
func (r *Registry) Network() string { return r.network }

// 按链 ID 取出适配器。
func (r *Registry) Get(id domain.ChainID) (Adapter, error) {
	a, ok := r.adapters[id]
	if !ok {
		return nil, domain.E("INVALID_ARGUMENT", "不支持的链", "")
	}
	return a, nil
}

// 并行检查五条链是否可访问，每条最多等 8 秒。
func (r *Registry) Health(ctx context.Context) []domain.Health {
	out := make([]domain.Health, len(r.order))
	var wg sync.WaitGroup
	for i, id := range r.order {
		wg.Add(1)
		go func(i int, id domain.ChainID) {
			defer wg.Done()
			cctx, cancel := context.WithTimeout(ctx, 8*time.Second)
			defer cancel()
			out[i] = r.adapters[id].Health(cctx)
		}(i, id)
	}
	wg.Wait()
	return out
}

type ChainInfo struct {
	ID         domain.ChainID `json:"id"`
	Name       string         `json:"name"`
	Native     string         `json:"native"`
	ExplorerTx string         `json:"explorerTx"`
	Assets     []domain.Asset `json:"assets"`
}

// 返回给前端的链清单，包括原生币和代币目录。
func (r *Registry) Chains() []ChainInfo {
	out := make([]ChainInfo, 0, len(r.order))
	for _, id := range r.order {
		out = append(out, ChainInfo{
			ID:         id,
			Name:       domain.ChainName(id),
			Native:     domain.NativeSymbol(id),
			ExplorerTx: ExplorerTx(id, r.network),
			Assets:     Assets(id, r.network, r.mode),
		})
	}
	return out
}
