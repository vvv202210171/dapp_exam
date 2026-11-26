package chain

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"math/big"
	"strings"
	"time"

	"aegis/internal/domain"
	"aegis/internal/platform"
	"aegis/internal/txbuild"
)

// 沙箱 chainId 故意不是任何公链。用它签出的 EIP-1559 交易不能在主网或 Sepolia 重放。
const sandboxEVMChainID = "84532026"

type sandboxAdapter struct {
	chain   domain.ChainID
	network string
}

// 返回这条沙箱适配器代表的链。
func (a sandboxAdapter) ID() domain.ChainID { return a.chain }

// 沙箱永远报健康，不访问网络。
func (a sandboxAdapter) Health(context.Context) domain.Health {
	return domain.Health{
		Chain:     a.chain,
		Name:      domain.ChainName(a.chain),
		OK:        true,
		LatencyMS: 1,
		Block:     "sandbox",
		Endpoint:  "sandbox://" + string(a.chain),
		Mode:      "sandbox",
	}
}

// 返回按地址推导的模拟余额。
func (a sandboxAdapter) Balance(_ context.Context, address string, asset domain.Asset) (string, error) {
	return SandboxAmount(address, asset.Symbol, asset.Decimals), nil
}

// 按链分发到对应的沙箱报价。
func (a sandboxAdapter) Prepare(_ context.Context, from, to, amount string, units *big.Int, asset domain.Asset) (domain.PrepareResult, error) {
	switch a.chain {
	case domain.Bitcoin:
		return a.prepareBTC(from, to, amount, units, asset)
	case domain.Solana:
		return a.prepareSOL(from, to, amount, units, asset)
	case domain.Tron:
		return a.prepareTRON(from, to, amount, units, asset)
	default:
		return a.prepareEVM(from, to, amount, units, asset)
	}
}

// 构造不能在公链重放的 EIP-1559 报价。
func (a sandboxAdapter) prepareEVM(from, to, amount string, units *big.Int, asset domain.Asset) (domain.PrepareResult, error) {
	gas := big.NewInt(21_000)
	value := units
	data := "0x"
	target := to
	warnings := []string{"沙箱 chainId 是 84532026，签出来的交易不能在主网或测试网重放。"}
	if asset.Kind != "native" {
		gas = big.NewInt(65_000)
		value = big.NewInt(0)
		encoded, err := txbuild.ERC20TransferData(to, units)
		if err != nil {
			return domain.PrepareResult{}, err
		}
		data = encoded
		target = asset.Contract
		warnings = append(warnings, "代币转账的 to 是合约地址，金额在 calldata 里。")
	}
	maxFee := big.NewInt(20_000_000_000)
	tip := big.NewInt(1_000_000_000)
	fee := new(big.Int).Mul(gas, maxFee)
	return domain.PrepareResult{
		Chain:   a.chain,
		Network: a.network,
		Mode:    "sandbox",
		Asset:   asset.Symbol,
		From:    from,
		To:      to,
		Amount:  amount,
		Fee: domain.Fee{
			Symbol: domain.NativeSymbol(a.chain),
			Amount: domain.FormatUnits(fee, 18),
			Detail: map[string]string{
				"gasLimit":             gas.String(),
				"maxFeePerGas":         maxFee.String(),
				"maxPriorityFeePerGas": tip.String(),
			},
		},
		Payload: domain.EVMPayload{
			Type:                 2,
			ChainID:              sandboxEVMChainID,
			Nonce:                "0",
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

// 用假 UTXO 做一次真实结构的 P2WPKH 选币。
func (a sandboxAdapter) prepareBTC(from, to, amount string, units *big.Int, asset domain.Asset) (domain.PrepareResult, error) {
	if asset.Kind != "native" {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "不能在比特币主链上直接转这个资产", "稳定币请换 Ethereum、BNB、TRON 或 Solana。")
	}
	sats, err := txbuild.MustInt64(units)
	if err != nil || sats <= 0 {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "金额超出范围", "")
	}
	sum := sha256.Sum256([]byte("sandbox-utxo|" + from))
	utxo := domain.BTCInput{TxID: hex.EncodeToString(sum[:]), Vout: 0, Value: sats + 500_000}
	selected, fee, change, err := txbuild.SelectCoins([]domain.BTCInput{utxo}, sats, 15)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	outputs := []domain.BTCOutput{{Address: to, Value: sats}}
	if change > 0 {
		outputs = append(outputs, domain.BTCOutput{Address: from, Value: change})
	}
	return domain.PrepareResult{
		Chain:   domain.Bitcoin,
		Network: a.network,
		Mode:    "sandbox",
		Asset:   "BTC",
		From:    from,
		To:      to,
		Amount:  amount,
		Fee: domain.Fee{
			Symbol: "BTC",
			Amount: domain.FormatUnits(big.NewInt(fee), 8),
			Detail: map[string]string{"feeRateSatVb": "15"},
		},
		Payload:  domain.BTCPayload{Network: a.network, Inputs: selected, Outputs: outputs, FeeSats: fee},
		Warnings: []string{"沙箱 UTXO 是假的。签名结构是真的 P2WPKH，但这笔交易不会被比特币网络接受。"},
	}, nil
}

// 构造固定 blockhash 的原生 SOL 转账消息。
func (a sandboxAdapter) prepareSOL(from, to, amount string, units *big.Int, asset domain.Asset) (domain.PrepareResult, error) {
	block := make([]byte, 32)
	for i := range block {
		block[i] = 7
	}
	blockhash := platform.Base58Encode(block)
	if asset.Kind != "native" {
		if asset.Contract == "" {
			return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "当前网络不能转这个代币", asset.Note)
		}
		value, err := txbuild.MustUint64(units)
		if err != nil || value == 0 {
			return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "金额超出范围", "")
		}
		source, err := txbuild.AssociatedTokenAddress(from, asset.Contract)
		if err != nil {
			return domain.PrepareResult{}, err
		}
		dest, err := txbuild.AssociatedTokenAddress(to, asset.Contract)
		if err != nil {
			return domain.PrepareResult{}, err
		}
		msg, err := txbuild.BuildSOLTokenTransfer(from, source, dest, to, asset.Contract, value, asset.Decimals, true, blockhash)
		if err != nil {
			return domain.PrepareResult{}, err
		}
		return domain.PrepareResult{
			Chain:   domain.Solana,
			Network: a.network,
			Mode:    "sandbox",
			Asset:   asset.Symbol,
			From:    from,
			To:      to,
			Amount:  amount,
			Fee:     domain.Fee{Symbol: "SOL", Amount: "0.000005", Detail: map[string]string{"lamports": "5000"}},
			Payload: domain.SOLPayload{Message: base64.StdEncoding.EncodeToString(msg), Blockhash: blockhash, Lamports: "0", From: from, To: to},
			Warnings: []string{
				"沙箱 blockhash 是固定值，签出来的交易不会被 Solana 接受。",
				"教学消息会带上创建关联账户的指令。真实网络上这一步大约再花 0.00204 SOL。",
			},
		}, nil
	}
	lamports, err := txbuild.MustUint64(units)
	if err != nil || lamports == 0 {
		return domain.PrepareResult{}, domain.E("INVALID_ARGUMENT", "金额超出范围", "")
	}
	msg, err := txbuild.BuildSOLTransfer(from, to, lamports, blockhash)
	if err != nil {
		return domain.PrepareResult{}, err
	}
	return domain.PrepareResult{
		Chain:   domain.Solana,
		Network: a.network,
		Mode:    "sandbox",
		Asset:   "SOL",
		From:    from,
		To:      to,
		Amount:  amount,
		Fee:     domain.Fee{Symbol: "SOL", Amount: "0.000005", Detail: map[string]string{"lamports": "5000"}},
		Payload: domain.SOLPayload{
			Message:   base64.StdEncoding.EncodeToString(msg),
			Blockhash: blockhash,
			Lamports:  units.String(),
			From:      from,
			To:        to,
		},
		Warnings: []string{"沙箱 blockhash 是固定值，签出来的交易不会被 Solana 接受。"},
	}, nil
}

// 构造假 txID，签名步骤和主网相同。
func (a sandboxAdapter) prepareTRON(from, to, amount string, units *big.Int, asset domain.Asset) (domain.PrepareResult, error) {
	fee := "0"
	kind := "TransferContract"
	warnings := []string{"沙箱里的 txID 不是 raw_data 的哈希，广播会被真实节点拒绝。签名步骤和主网相同：对 txID 做 secp256k1。"}
	if asset.Kind != "native" {
		fee = "15"
		kind = "TriggerSmartContract"
		warnings = append(warnings, "TRC20 的能量费用这里用 15 TRX 作为教学估算。")
	}
	txID := txbuild.TronTxID("sandbox", from, to, amount, asset.Symbol, units.String())
	return domain.PrepareResult{
		Chain:    domain.Tron,
		Network:  a.network,
		Mode:     "sandbox",
		Asset:    asset.Symbol,
		From:     from,
		To:       to,
		Amount:   amount,
		Fee:      domain.Fee{Symbol: "TRX", Amount: fee},
		Payload:  domain.TRONPayload{TxID: txID, Transaction: txbuild.TronTransaction(txID, kind)},
		Warnings: warnings,
	}, nil
}

// 不把交易发到网络，只生成一个沙箱哈希。
func (a sandboxAdapter) Broadcast(_ context.Context, signed string) (string, error) {
	if strings.TrimSpace(signed) == "" {
		return "", domain.E("INVALID_ARGUMENT", "缺少已签名交易", "")
	}
	sum := sha256.Sum256([]byte(string(a.chain) + "|" + signed))
	hash := hex.EncodeToString(sum[:])
	if a.chain == domain.Ethereum || a.chain == domain.BNB {
		hash = "0x" + hash
	}
	return hash, nil
}

// 沙箱里已广播的交易直接视为已确认。
func (a sandboxAdapter) Status(_ context.Context, hash string) (domain.TxState, error) {
	return domain.TxState{State: "confirmed", Hash: hash, Block: "sandbox", Detail: "沙箱没有广播到真实网络。"}, nil
}

// 返回沙箱探针列表，用来确认控制台本身是通的。
func (a sandboxAdapter) Probes() []domain.Probe {
	return []domain.Probe{{
		Name:        "echo",
		Title:       "沙箱探针",
		Description: "不访问节点，用来确认控制台本身是通的",
	}}
}

// 执行白名单探针。沙箱的 echo 不发出网络请求。
func (a sandboxAdapter) RunProbe(_ context.Context, name string, args map[string]string) (any, error) {
	return map[string]any{
		"mode":    "sandbox",
		"chain":   a.chain,
		"probe":   name,
		"address": args["address"],
		"note":    "当前 MODE=sandbox，没有发出网络请求。改成 live 后，同一条探针会打到真实节点。",
		"at":      time.Now().UTC().Format(time.RFC3339),
	}, nil
}
