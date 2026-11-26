// 转账服务只收已签名 payload。报价、广播、状态查询走链适配器，私钥不经过这里。
package service

import (
	"context"
	"math/big"
	"strings"
	"time"

	"aegis/internal/chain"
	"aegis/internal/domain"
	"aegis/internal/platform"
	"aegis/internal/platform/fault"
	"aegis/internal/store"
)

type TransferService struct {
	store   *store.Store
	chains  *chain.Registry
	prices  *Pricer
	network string
	mode    string
}

// 创建转账服务。
func NewTransfer(st *store.Store, chains *chain.Registry, prices *Pricer, network, mode string) *TransferService {
	return &TransferService{store: st, chains: chains, prices: prices, network: network, mode: mode}
}

type PrepareInput struct {
	AccountID string
	Chain     domain.ChainID
	Asset     string
	From      string
	To        string
	Amount    string
}

// 校验后向链适配器要一份未签名报价，并补上手续费的美元估值。
func (s *TransferService) Prepare(ctx context.Context, in PrepareInput) (domain.PrepareResult, error) {
	_, adapter, asset, units, err := s.ready(ctx, in.AccountID, in.Chain, in.Asset, in.From, in.To, in.Amount)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	from := chain.NormalizeAddress(in.Chain, in.From)
	to := chain.NormalizeAddress(in.Chain, in.To)
	result, err := adapter.Prepare(ctx, from, to, strings.TrimSpace(in.Amount), units, asset)
	if err != nil {
		return domain.PrepareResult{}, fault.Explain(err)
	}
	prices, _ := s.prices.Snapshot(ctx)
	if price, ok := prices[result.Fee.Symbol]; ok {
		if usd, err := domain.MulUSD(result.Fee.Amount, price); err == nil {
			result.Fee.USD = usd
		}
	}
	return result, nil
}

type SubmitInput struct {
	AccountID     string
	Chain         domain.ChainID
	Asset         string
	From          string
	To            string
	Amount        string
	Fee           string
	SignedPayload string
	Broadcast     bool
}

// 保存浏览器签好的交易；如果要求广播，就立刻提交。
func (s *TransferService) Submit(ctx context.Context, in SubmitInput) (domain.Transfer, error) {
	if _, _, _, _, err := s.ready(ctx, in.AccountID, in.Chain, in.Asset, in.From, in.To, in.Amount); err != nil {
		return domain.Transfer{}, err
	}
	signed := strings.TrimSpace(in.SignedPayload)
	// 服务器不能代签。空签名直接拒绝，避免把未签名交易记成已签名。
	if signed == "" {
		return domain.Transfer{}, domain.E("INVALID_ARGUMENT", "缺少签名结果", "先在浏览器里签名。服务器不保存助记词，也不能代签。")
	}
	if len(signed) > 64*1024 {
		return domain.Transfer{}, domain.E("INVALID_ARGUMENT", "签名结果太大", "")
	}
	now := time.Now().UTC()
	rec := domain.Transfer{
		ID:            platform.NewID(),
		AccountID:     in.AccountID,
		Chain:         in.Chain,
		Asset:         strings.ToUpper(strings.TrimSpace(in.Asset)),
		From:          chain.NormalizeAddress(in.Chain, in.From),
		To:            chain.NormalizeAddress(in.Chain, in.To),
		Amount:        strings.TrimSpace(in.Amount),
		Fee:           strings.TrimSpace(in.Fee),
		SignedPayload: signed,
		Status:        "signed",
		CreatedAt:     now,
		UpdatedAt:     now,
	}
	if !in.Broadcast {
		rec.Detail = "已在本地签名，还没有广播。已签名交易等同于持票，任何拿到它的人都能广播。"
		if err := s.store.InsertTransfer(ctx, rec); err != nil {
			return domain.Transfer{}, err
		}
		return rec, nil
	}
	if err := s.broadcast(ctx, &rec); err != nil {
		_ = s.store.InsertTransfer(ctx, rec)
		return rec, err
	}
	if err := s.store.InsertTransfer(ctx, rec); err != nil {
		return domain.Transfer{}, err
	}
	return rec, nil
}

// 把一笔已经签好、尚未广播的交易提交出去。
func (s *TransferService) Broadcast(ctx context.Context, id string) (domain.Transfer, error) {
	rec, err := s.store.GetTransfer(ctx, id)
	if err != nil {
		return domain.Transfer{}, err
	}
	if rec.Status != "signed" {
		return domain.Transfer{}, domain.E("INVALID_ARGUMENT", "这笔交易不能再次广播", "只有停在“已签名”的记录可以稍后广播。")
	}
	if err := s.broadcast(ctx, &rec); err != nil {
		_ = s.store.UpdateTransfer(ctx, rec)
		return rec, err
	}
	if err := s.store.UpdateTransfer(ctx, rec); err != nil {
		return domain.Transfer{}, err
	}
	return rec, nil
}

// 向节点核对交易状态并写回数据库。
func (s *TransferService) Refresh(ctx context.Context, id string) (domain.Transfer, error) {
	rec, err := s.store.GetTransfer(ctx, id)
	if err != nil {
		return domain.Transfer{}, err
	}
	if rec.TxHash == "" {
		return rec, nil
	}
	adapter, err := s.chains.Get(rec.Chain)
	if err != nil {
		return domain.Transfer{}, err
	}
	state, err := adapter.Status(ctx, rec.TxHash)
	if err != nil {
		return domain.Transfer{}, fault.Explain(err)
	}
	switch state.State {
	case "confirmed":
		rec.Status = "confirmed"
		rec.Detail = state.Detail
	case "failed":
		rec.Status = "failed"
		rec.Detail = state.Detail
	case "pending":
		rec.Status = "broadcast"
		rec.Detail = state.Detail
	case "not_found":
		rec.Detail = state.Detail
	}
	rec.UpdatedAt = time.Now().UTC()
	if err := s.store.UpdateTransfer(ctx, rec); err != nil {
		return domain.Transfer{}, err
	}
	return rec, nil
}

// 列出某账户的转账记录。
func (s *TransferService) List(ctx context.Context, accountID string) ([]domain.Transfer, error) {
	if _, err := s.store.GetAccount(ctx, accountID); err != nil {
		return nil, err
	}
	return s.store.ListTransfers(ctx, accountID)
}

// 按 ID 读取一笔转账。
func (s *TransferService) Get(ctx context.Context, id string) (domain.Transfer, error) {
	return s.store.GetTransfer(ctx, id)
}

// 调用适配器广播，并把成功或失败写进这笔记录。
func (s *TransferService) broadcast(ctx context.Context, rec *domain.Transfer) error {
	adapter, err := s.chains.Get(rec.Chain)
	if err != nil {
		return err
	}
	hash, err := adapter.Broadcast(ctx, rec.SignedPayload)
	rec.UpdatedAt = time.Now().UTC()
	if err != nil {
		explained := fault.Explain(err)
		rec.Status = "failed"
		rec.Detail = explained.Message
		return explained
	}
	rec.TxHash = hash
	rec.Status = "broadcast"
	rec.Detail = "已提交给节点。"
	state, statusErr := adapter.Status(ctx, hash)
	if statusErr != nil {
		return nil
	}
	switch state.State {
	case "confirmed":
		rec.Status = "confirmed"
		rec.Detail = state.Detail
	case "failed":
		rec.Status = "failed"
		rec.Detail = state.Detail
	case "pending":
		rec.Status = "broadcast"
		rec.Detail = state.Detail
	}
	return nil
}

// 转账前检查账户归属、地址、资产和金额，返回最小单位。
func (s *TransferService) ready(ctx context.Context, accountID string, id domain.ChainID, assetSymbol, from, to, amount string) (domain.Account, chain.Adapter, domain.Asset, *big.Int, error) {
	if !id.Valid() {
		return domain.Account{}, nil, domain.Asset{}, nil, domain.E("INVALID_ARGUMENT", "不支持的链", "")
	}
	acc, err := s.store.GetAccount(ctx, accountID)
	if err != nil {
		return domain.Account{}, nil, domain.Asset{}, nil, err
	}
	if !Owns(acc, id, from) {
		return domain.Account{}, nil, domain.Asset{}, nil, domain.E("INVALID_ARGUMENT", "付款地址不属于这个账户", "只能从当前助记词派生出的地址转出。")
	}
	if err := chain.ValidateAddress(id, s.network, to); err != nil {
		return domain.Account{}, nil, domain.Asset{}, nil, err
	}
	if err := chain.RejectZero(id, to); err != nil {
		return domain.Account{}, nil, domain.Asset{}, nil, err
	}
	asset, err := chain.FindAsset(id, s.network, s.mode, assetSymbol)
	if err != nil {
		return domain.Account{}, nil, domain.Asset{}, nil, err
	}
	units, err := domain.ParseUnits(amount, asset.Decimals)
	if err != nil {
		return domain.Account{}, nil, domain.Asset{}, nil, domain.E("INVALID_ARGUMENT", err.Error(), "小数位不能超过该资产的精度。例如 BTC 最多 8 位，ETH 最多 18 位，BSC 上的 USDT 是 18 位而以太坊上的 USDT 是 6 位。")
	}
	if units.Sign() == 0 {
		return domain.Account{}, nil, domain.Asset{}, nil, domain.E("INVALID_ARGUMENT", "金额必须大于 0", "")
	}
	adapter, err := s.chains.Get(id)
	if err != nil {
		return domain.Account{}, nil, domain.Asset{}, nil, err
	}
	return acc, adapter, asset, units, nil
}
