// 资产页按链并行查余额。某条链的原生币失败后，同链后面的代币不再打节点，沿用同一个错误。
package service

import (
	"context"
	"sync"
	"time"

	"aegis/internal/chain"
	"aegis/internal/domain"
	"aegis/internal/platform/fault"
	"aegis/internal/store"
)

type AssetService struct {
	store  *store.Store
	chains *chain.Registry
	prices *Pricer
}

// 创建资产查询服务。
func NewAsset(st *store.Store, chains *chain.Registry, prices *Pricer) *AssetService {
	return &AssetService{store: st, chains: chains, prices: prices}
}

type AssetLine struct {
	Symbol   string        `json:"symbol"`
	Name     string        `json:"name"`
	Kind     string        `json:"kind"`
	Decimals int           `json:"decimals"`
	Contract string        `json:"contract,omitempty"`
	Amount   string        `json:"amount"`
	USD      string        `json:"usd"`
	Note     string        `json:"note,omitempty"`
	OK       bool          `json:"ok"`
	Error    *domain.Error `json:"error,omitempty"`
}

type ChainPortfolio struct {
	Chain   domain.ChainID `json:"chain"`
	Name    string         `json:"name"`
	Address string         `json:"address"`
	Path    string         `json:"path"`
	OK      bool           `json:"ok"`
	Error   *domain.Error  `json:"error,omitempty"`
	Assets  []AssetLine    `json:"assets"`
}

type Portfolio struct {
	AccountID   string           `json:"accountId"`
	Mode        string           `json:"mode"`
	Network     string           `json:"network"`
	PriceSource string           `json:"priceSource"`
	TotalUSD    string           `json:"totalUsd"`
	Chains      []ChainPortfolio `json:"chains"`
}

// 并行查询一个账户在五条链上的余额，并汇总美元估值。
func (s *AssetService) Portfolio(ctx context.Context, accountID string) (Portfolio, error) {
	acc, err := s.store.GetAccount(ctx, accountID)
	if err != nil {
		return Portfolio{}, err
	}
	prices, source := s.prices.Snapshot(ctx)
	out := make([]ChainPortfolio, len(acc.Addresses))
	var wg sync.WaitGroup
	for i, addr := range acc.Addresses {
		wg.Add(1)
		go func(i int, addr domain.Address) {
			defer wg.Done()
			cctx, cancel := context.WithTimeout(ctx, 8*time.Second)
			defer cancel()
			out[i] = s.one(cctx, addr, prices)
		}(i, addr)
	}
	wg.Wait()
	var usd []string
	for _, chainRow := range out {
		for _, asset := range chainRow.Assets {
			if asset.OK {
				usd = append(usd, asset.USD)
			}
		}
	}
	return Portfolio{
		AccountID:   accountID,
		Mode:        s.chains.Mode(),
		Network:     s.chains.Network(),
		PriceSource: source,
		TotalUSD:    domain.SumUSD(usd),
		Chains:      out,
	}, nil
}

// 查询一条链上的原生币和稳定币。
func (s *AssetService) one(ctx context.Context, addr domain.Address, prices map[string]string) ChainPortfolio {
	row := ChainPortfolio{
		Chain:   addr.Chain,
		Name:    domain.ChainName(addr.Chain),
		Address: addr.Address,
		Path:    addr.Path,
		OK:      true,
		Assets:  []AssetLine{},
	}
	adapter, err := s.chains.Get(addr.Chain)
	if err != nil {
		row.OK = false
		row.Error = fault.Explain(err)
		return row
	}
	assets := chain.Assets(addr.Chain, s.chains.Network(), s.chains.Mode())
	for i, asset := range assets {
		line := AssetLine{
			Symbol: asset.Symbol, Name: asset.Name, Kind: asset.Kind,
			Decimals: asset.Decimals, Contract: asset.Contract, Note: asset.Note,
		}
		// 原生币已经失败，代币多半是同一个节点问题，不再继续请求。
		if i > 0 && !row.OK {
			line.OK = false
			line.Amount = "0"
			line.USD = "0.00"
			line.Error = row.Error
			row.Assets = append(row.Assets, line)
			continue
		}
		amount, err := adapter.Balance(ctx, addr.Address, asset)
		if err != nil {
			explained := fault.Explain(err)
			line.OK = false
			line.Amount = "0"
			line.USD = "0.00"
			line.Error = explained
			if i == 0 {
				row.OK = false
				row.Error = explained
			}
			row.Assets = append(row.Assets, line)
			continue
		}
		usd := "0.00"
		if price, ok := prices[asset.Symbol]; ok {
			if quoted, err := domain.MulUSD(amount, price); err == nil {
				usd = quoted
			}
		}
		line.OK = true
		line.Amount = amount
		line.USD = usd
		row.Assets = append(row.Assets, line)
	}
	return row
}
