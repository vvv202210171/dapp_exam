// 构造未签名交易。签名仍在浏览器完成，这里只产出节点和钱包都能核对的 payload。
package txbuild

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"strings"

	"aegis/internal/domain"
	"aegis/internal/platform"
)

// SelectCoins 从大到小挑已确认 UTXO，直到金额加手续费够用。找零低于 546 聪就并进手续费，避免粉尘输出。
func SelectCoins(utxos []domain.BTCInput, amount, feeRate int64) (selected []domain.BTCInput, fee, change int64, err error) {
	if amount <= 0 {
		return nil, 0, 0, domain.E("INVALID_ARGUMENT", "金额必须大于 0", "")
	}
	if feeRate <= 0 {
		feeRate = 1
	}
	ordered := append([]domain.BTCInput(nil), utxos...)
	for i := 0; i < len(ordered); i++ {
		for j := i + 1; j < len(ordered); j++ {
			if ordered[j].Value > ordered[i].Value {
				ordered[i], ordered[j] = ordered[j], ordered[i]
			}
		}
	}
	var total int64
	for _, u := range ordered {
		if u.Value <= 0 {
			continue
		}
		selected = append(selected, u)
		total += u.Value
		if f, c, ok := fitCoins(total, amount, feeRate, len(selected)); ok {
			return selected, f, c, nil
		}
	}
	return nil, 0, 0, domain.E("INSUFFICIENT_FUNDS", "比特币余额不够支付金额和手续费", "确认已有足够的已确认 UTXO。刚收到的币要等确认后才能转出。")
}

// 判断当前选中的 UTXO 够不够付金额和手续费，并决定要不要找零。
func fitCoins(total, amount, feeRate int64, nIn int) (fee, change int64, ok bool) {
	if total < amount {
		return 0, 0, false
	}
	feeWithChange := estimateVSize(nIn, 2) * feeRate
	if total >= amount+feeWithChange {
		change = total - amount - feeWithChange
		if change >= 546 {
			return feeWithChange, change, true
		}
	}
	feeNoChange := estimateVSize(nIn, 1) * feeRate
	if total >= amount+feeNoChange {
		return total - amount, 0, true
	}
	return 0, 0, false
}

// P2WPKH 体积近似：固定开销 + 每个输入 68 vB + 每个输出 31 vB，再加 10%，避免手续费少算几个字节被节点拒绝。
func estimateVSize(nIn, nOut int) int64 {
	v := int64(11 + nIn*68 + nOut*31)
	v += v / 10
	if v < 1 {
		return 1
	}
	return v
}

// BuildSOLTransfer 组装旧版 message。浏览器只对这段字节做 ed25519 签名，再拼成可广播的交易。
func BuildSOLTransfer(from, to string, lamports uint64, blockhash string) ([]byte, error) {
	fromKey, err := platform.Base58Decode(from)
	if err != nil || len(fromKey) != 32 {
		return nil, domain.E("INVALID_ARGUMENT", "Solana 付款地址不正确", "地址应是 32 字节公钥的 base58。")
	}
	toKey, err := platform.Base58Decode(to)
	if err != nil || len(toKey) != 32 {
		return nil, domain.E("INVALID_ARGUMENT", "Solana 收款地址不正确", "地址应是 32 字节公钥的 base58。")
	}
	bh, err := platform.Base58Decode(blockhash)
	if err != nil || len(bh) != 32 {
		return nil, domain.E("CHAIN_ERROR", "blockhash 无法解码", "重新向节点获取最新 blockhash。")
	}
	if bytesEqual(fromKey, toKey) {
		return nil, domain.E("INVALID_ARGUMENT", "不能把 SOL 转给自己", "Solana 这笔教学交易把付款人和收款人当成两个账户。")
	}
	data := make([]byte, 12)
	binary.LittleEndian.PutUint32(data[0:4], 2)
	binary.LittleEndian.PutUint64(data[4:12], lamports)

	buf := make([]byte, 0, 200)
	buf = append(buf, 1, 0, 1) // header：1 个签名者，0 个只读签名账户，1 个只读未签名账户（系统程序）
	buf = appendCompact(buf, 3)
	buf = append(buf, fromKey...)
	buf = append(buf, toKey...)
	buf = append(buf, make([]byte, 32)...) // 系统程序 ID 是 32 个 0
	buf = append(buf, bh...)
	buf = appendCompact(buf, 1)
	buf = append(buf, 2) // program id index
	buf = appendCompact(buf, 2)
	buf = append(buf, 0, 1)
	buf = appendCompact(buf, len(data))
	buf = append(buf, data...)
	return buf, nil
}

// 写入 Solana 的 compact-u16。教学范围只支持小于 128 的数。
func appendCompact(buf []byte, n int) []byte {
	if n < 0 || n > 127 {
		panic("compact-u16 out of teaching range")
	}
	return append(buf, byte(n))
}

// 编码 ERC-20 transfer 的 calldata。
func ERC20TransferData(to string, amount *big.Int) (string, error) {
	word, err := abiAddress(to)
	if err != nil {
		return "", err
	}
	out := make([]byte, 0, 4+64)
	out = append(out, 0xa9, 0x05, 0x9c, 0xbb) // transfer(address,uint256) 的选择器
	out = append(out, word...)
	out = append(out, abiUint(amount)...)
	return "0x" + hex.EncodeToString(out), nil
}

// 编码 ERC-20 balanceOf 的 calldata。
func ERC20BalanceOfData(holder string) (string, error) {
	word, err := abiAddress(holder)
	if err != nil {
		return "", err
	}
	out := append([]byte{0x70, 0xa0, 0x82, 0x31}, word...) // balanceOf(address)
	return "0x" + hex.EncodeToString(out), nil
}

// 把 TRON 地址去掉 0x41 后，编成合约参数。
func TRC20Parameter(to string, amount *big.Int) (string, error) {
	payload, err := platform.Base58CheckDecode(to)
	if err != nil || len(payload) != 21 || payload[0] != 0x41 {
		return "", domain.E("INVALID_ARGUMENT", "TRON 收款地址不正确", "TRC20 参数里用的是去掉 0x41 版本字节后的 20 字节地址。")
	}
	word := make([]byte, 32)
	copy(word[12:], payload[1:])
	out := append(word, abiUint(amount)...)
	return hex.EncodeToString(out), nil
}

// 给沙箱拼一个确定性的假 txID。
func TronTxID(parts ...string) string {
	sum := sha256.Sum256([]byte(strings.Join(parts, "|")))
	return hex.EncodeToString(sum[:])
}

// 组装一笔带 txID 的 TRON 交易 JSON。
func TronTransaction(txID, contractType string) json.RawMessage {
	raw, _ := json.Marshal(map[string]any{
		"visible":      true,
		"txID":         txID,
		"raw_data_hex": hex.EncodeToString([]byte("sandbox:" + contractType)),
		"raw_data": map[string]any{
			"contract": []any{map[string]any{"type": contractType}},
		},
	})
	return raw
}

// 把 0x 地址左填充成 ABI 的 32 字节字。
func abiAddress(addr string) ([]byte, error) {
	s := strings.TrimPrefix(strings.TrimSpace(addr), "0x")
	if len(s) != 40 {
		return nil, domain.E("INVALID_ARGUMENT", "EVM 地址格式不正确", "地址应为 0x 开头的 40 位十六进制。")
	}
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != 20 {
		return nil, domain.E("INVALID_ARGUMENT", "EVM 地址格式不正确", "地址应为 0x 开头的 40 位十六进制。")
	}
	out := make([]byte, 32)
	copy(out[12:], b)
	return out, nil
}

// 把整数左填充成 ABI 的 32 字节字。
func abiUint(n *big.Int) []byte {
	out := make([]byte, 32)
	if n == nil || n.Sign() == 0 {
		return out
	}
	if n.Sign() < 0 {
		panic("negative abi uint")
	}
	b := n.Bytes()
	if len(b) > 32 {
		panic("uint overflow")
	}
	copy(out[32-len(b):], b)
	return out
}

// 比较两段字节是否相同。
func bytesEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// 把金额收成 uint64，超出就返回错误。
func MustUint64(n *big.Int) (uint64, error) {
	if n.Sign() < 0 || !n.IsUint64() {
		return 0, fmt.Errorf("金额超出范围")
	}
	return n.Uint64(), nil
}

// 把金额收成 int64，超出就返回错误。
func MustInt64(n *big.Int) (int64, error) {
	if !n.IsInt64() {
		return 0, fmt.Errorf("金额超出范围")
	}
	return n.Int64(), nil
}
