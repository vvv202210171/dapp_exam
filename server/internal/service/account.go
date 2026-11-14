// 账户同步。客户端提交五条链的地址和派生路径，服务端只保存地址，不接收私钥。
package service

import (
	"context"
	"strings"
	"unicode/utf8"

	"aegis/internal/chain"
	"aegis/internal/domain"
	"aegis/internal/store"
)

type AccountService struct {
	store   *store.Store
	network string
}

// 创建账户服务。
func NewAccount(st *store.Store, network string) *AccountService {
	return &AccountService{store: st, network: network}
}

// 校验名称、派生路径和地址后，把账户同步进数据库。
func (s *AccountService) Sync(ctx context.Context, label string, addrs []domain.Address) (domain.Account, error) {
	label = strings.TrimSpace(label)
	if label == "" {
		label = "主账户"
	}
	if utf8.RuneCountInString(label) > 40 {
		return domain.Account{}, domain.E("INVALID_ARGUMENT", "账户名称太长", "最多 40 个字。")
	}
	if len(addrs) != len(domain.Chains()) {
		return domain.Account{}, domain.E("INVALID_ARGUMENT", "需要同时提交五条链的地址", "一套助记词会派生 Bitcoin、Ethereum、BNB、Solana、TRON。")
	}
	seen := map[domain.ChainID]bool{}
	clean := make([]domain.Address, len(addrs))
	for i, a := range addrs {
		if !a.Chain.Valid() {
			return domain.Account{}, domain.E("INVALID_ARGUMENT", "不支持的链", "")
		}
		if seen[a.Chain] {
			return domain.Account{}, domain.E("INVALID_ARGUMENT", "同一条链提交了两次", "")
		}
		seen[a.Chain] = true
		if err := chain.ValidateAddress(a.Chain, s.network, a.Address); err != nil {
			return domain.Account{}, err
		}
		path := strings.TrimSpace(a.Path)
		if !strings.HasPrefix(path, "m/") || len(path) > 80 {
			return domain.Account{}, domain.E("INVALID_ARGUMENT", "派生路径不正确", "路径应以 m/ 开头，例如 m/44'/60'/0'/0/0。")
		}
		clean[i] = domain.Address{Chain: a.Chain, Address: chain.NormalizeAddress(a.Chain, a.Address), Path: path}
	}
	for _, id := range domain.Chains() {
		if !seen[id] {
			return domain.Account{}, domain.E("INVALID_ARGUMENT", "缺少 "+domain.ChainName(id)+" 地址", "")
		}
	}
	return s.store.SyncAccount(ctx, label, clean)
}

// 按 ID 获取账户。
func (s *AccountService) Get(ctx context.Context, id string) (domain.Account, error) {
	if strings.TrimSpace(id) == "" {
		return domain.Account{}, domain.E("INVALID_ARGUMENT", "缺少账户", "")
	}
	return s.store.GetAccount(ctx, id)
}

// 判断这个地址是不是该账户在这条链上的地址。
func Owns(acc domain.Account, id domain.ChainID, address string) bool {
	for _, a := range acc.Addresses {
		if a.Chain == id && chain.SameAddress(id, a.Address, address) {
			return true
		}
	}
	return false
}
