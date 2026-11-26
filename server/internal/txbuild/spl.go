package txbuild

import (
	"crypto/sha256"
	"encoding/binary"
	"math/big"

	"aegis/internal/domain"
	"aegis/internal/platform"
)

const (
	// TokenProgram 是经典 SPL Token 程序。USDT、USDC 用这一张，不用 Token-2022。
	TokenProgram = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
	ataProgram   = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
)

var (
	edP = new(big.Int).Sub(new(big.Int).Lsh(big.NewInt(1), 255), big.NewInt(19))
	edD = edwardsD()
)

func edwardsD() *big.Int {
	inv := new(big.Int).ModInverse(big.NewInt(121666), edP)
	d := new(big.Int).Mul(big.NewInt(-121665), inv)
	return d.Mod(d, edP)
}

// AssociatedTokenAddress 推导钱包持有某种代币的关联账户。
func AssociatedTokenAddress(owner, mint string) (string, error) {
	ownerKey, err := pubkey(owner, "Solana 付款地址不正确")
	if err != nil {
		return "", err
	}
	mintKey, err := pubkey(mint, "代币合约不正确")
	if err != nil {
		return "", err
	}
	tokenProgram, err := pubkey(TokenProgram, "Token 程序地址不正确")
	if err != nil {
		return "", err
	}
	program, err := pubkey(ataProgram, "关联账户程序地址不正确")
	if err != nil {
		return "", err
	}
	found, err := findAddress([][]byte{ownerKey, tokenProgram, mintKey}, program)
	if err != nil {
		return "", err
	}
	return platform.Base58Encode(found), nil
}

// BuildSOLTokenTransfer 组装 TransferChecked。createAccount 为真时先幂等创建收款关联账户。
func BuildSOLTokenTransfer(owner, source, destination, destWallet, mint string, amount uint64, decimals int, createAccount bool, blockhash string) ([]byte, error) {
	ownerKey, err := pubkey(owner, "Solana 付款地址不正确")
	if err != nil {
		return nil, err
	}
	sourceKey, err := pubkey(source, "付款代币账户不正确")
	if err != nil {
		return nil, err
	}
	destKey, err := pubkey(destination, "收款代币账户不正确")
	if err != nil {
		return nil, err
	}
	mintKey, err := pubkey(mint, "代币合约不正确")
	if err != nil {
		return nil, err
	}
	var walletKey []byte
	if createAccount {
		walletKey, err = pubkey(destWallet, "Solana 收款地址不正确")
		if err != nil {
			return nil, err
		}
	}
	bh, err := platform.Base58Decode(blockhash)
	if err != nil || len(bh) != 32 {
		return nil, domain.E("CHAIN_ERROR", "blockhash 无法解码", "重新向节点获取最新 blockhash。")
	}
	if bytesEqual(ownerKey, walletKey) || bytesEqual(sourceKey, destKey) {
		return nil, domain.E("INVALID_ARGUMENT", "不能把代币转给自己", "付款钱包和收款钱包要是两个地址。")
	}
	if amount == 0 {
		return nil, domain.E("INVALID_ARGUMENT", "金额超出范围", "")
	}
	if decimals < 0 || decimals > 255 {
		return nil, domain.E("INVALID_ARGUMENT", "代币小数位不正确", "")
	}
	tokenProgram, err := pubkey(TokenProgram, "Token 程序地址不正确")
	if err != nil {
		return nil, err
	}
	accounts := [][]byte{ownerKey, sourceKey, destKey}
	var buf []byte
	var transferProgram byte
	var transferAccounts []byte
	if createAccount {
		ata, err := pubkey(ataProgram, "关联账户程序地址不正确")
		if err != nil {
			return nil, err
		}
		accounts = append(accounts, walletKey, mintKey, make([]byte, 32), tokenProgram, ata)
		buf = messageHead(accounts, 5, bh, 2)
		buf = appendInstruction(buf, 7, []byte{0, 2, 3, 4, 5, 6}, []byte{1})
		transferProgram = 6
		transferAccounts = []byte{1, 4, 2, 0}
	} else {
		accounts = append(accounts, mintKey, tokenProgram)
		buf = messageHead(accounts, 2, bh, 1)
		transferProgram = 4
		transferAccounts = []byte{1, 3, 2, 0}
	}
	data := make([]byte, 10)
	data[0] = 12
	binary.LittleEndian.PutUint64(data[1:9], amount)
	data[9] = byte(decimals)
	return appendInstruction(buf, transferProgram, transferAccounts, data), nil
}

func messageHead(accounts [][]byte, readonlyUnsigned int, blockhash []byte, instructions int) []byte {
	buf := []byte{1, 0, byte(readonlyUnsigned), byte(len(accounts))}
	for _, account := range accounts {
		buf = append(buf, account...)
	}
	buf = append(buf, blockhash...)
	return append(buf, byte(instructions))
}

func appendInstruction(buf []byte, program byte, indexes, data []byte) []byte {
	buf = append(buf, program, byte(len(indexes)))
	buf = append(buf, indexes...)
	buf = append(buf, byte(len(data)))
	return append(buf, data...)
}

func findAddress(seeds [][]byte, program []byte) ([]byte, error) {
	marker := []byte("ProgramDerivedAddress")
	for bump := 255; bump >= 0; bump-- {
		h := sha256.New()
		for _, seed := range seeds {
			_, _ = h.Write(seed)
		}
		_, _ = h.Write([]byte{byte(bump)})
		_, _ = h.Write(program)
		_, _ = h.Write(marker)
		sum := h.Sum(nil)
		if !onCurve(sum) {
			return sum, nil
		}
	}
	return nil, domain.E("CHAIN_ERROR", "推导不了关联账户", "检查钱包地址和代币合约。")
}

func onCurve(compressed []byte) bool {
	if len(compressed) != 32 {
		return false
	}
	be := make([]byte, 32)
	for i := 0; i < 32; i++ {
		be[i] = compressed[31-i]
	}
	y := new(big.Int).SetBytes(be)
	y.And(y, new(big.Int).Sub(new(big.Int).Lsh(big.NewInt(1), 255), big.NewInt(1)))
	if y.Cmp(edP) >= 0 {
		return false
	}
	y2 := new(big.Int).Mul(y, y)
	y2.Mod(y2, edP)
	u := new(big.Int).Sub(y2, big.NewInt(1))
	u.Mod(u, edP)
	v := new(big.Int).Mul(edD, y2)
	v.Add(v, big.NewInt(1))
	v.Mod(v, edP)
	if v.Sign() == 0 {
		return false
	}
	inv := new(big.Int).ModInverse(v, edP)
	x2 := new(big.Int).Mul(u, inv)
	x2.Mod(x2, edP)
	return sqrtMod(x2) != nil
}

func sqrtMod(n *big.Int) *big.Int {
	exp := new(big.Int).Add(edP, big.NewInt(3))
	exp.Rsh(exp, 3)
	x := new(big.Int).Exp(n, exp, edP)
	sq := new(big.Int).Mul(x, x)
	sq.Mod(sq, edP)
	if sq.Cmp(n) == 0 {
		return x
	}
	iexp := new(big.Int).Sub(edP, big.NewInt(1))
	iexp.Rsh(iexp, 2)
	i := new(big.Int).Exp(big.NewInt(2), iexp, edP)
	x = new(big.Int).Mul(x, i)
	x.Mod(x, edP)
	sq = new(big.Int).Mul(x, x)
	sq.Mod(sq, edP)
	if sq.Cmp(n) == 0 {
		return x
	}
	return nil
}

func pubkey(text, message string) ([]byte, error) {
	raw, err := platform.Base58Decode(text)
	if err != nil || len(raw) != 32 {
		return nil, domain.E("INVALID_ARGUMENT", message, "地址应是 32 字节公钥的 base58。")
	}
	return raw, nil
}
