// Base58 / Base58Check。TRON 用双 SHA-256 的 4 字节校验；Solana 公钥没有这层校验。
package platform

import (
	"bytes"
	"crypto/sha256"
	"errors"
	"math/big"
)

const b58Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

// 把字节编成 base58。
func Base58Encode(input []byte) string {
	zeros := 0
	for zeros < len(input) && input[zeros] == 0 {
		zeros++
	}
	n := new(big.Int).SetBytes(input)
	base := big.NewInt(58)
	mod := new(big.Int)
	var encoded []byte
	for n.Sign() > 0 {
		n.DivMod(n, base, mod)
		encoded = append(encoded, b58Alphabet[mod.Int64()])
	}
	for i := 0; i < zeros; i++ {
		encoded = append(encoded, '1')
	}
	for i, j := 0, len(encoded)-1; i < j; i, j = i+1, j-1 {
		encoded[i], encoded[j] = encoded[j], encoded[i]
	}
	return string(encoded)
}

// 把 base58 解回字节。
func Base58Decode(s string) ([]byte, error) {
	if s == "" {
		return nil, errors.New("empty base58")
	}
	zeros := 0
	for zeros < len(s) && s[zeros] == '1' {
		zeros++
	}
	n := big.NewInt(0)
	base := big.NewInt(58)
	for i := 0; i < len(s); i++ {
		idx := bytes.IndexByte([]byte(b58Alphabet), s[i])
		if idx < 0 {
			return nil, errors.New("invalid base58 character")
		}
		n.Mul(n, base)
		n.Add(n, big.NewInt(int64(idx)))
	}
	decoded := n.Bytes()
	out := make([]byte, zeros+len(decoded))
	copy(out[zeros:], decoded)
	return out, nil
}

// 在载荷后附上 4 字节双 SHA-256 校验再编码。
func Base58CheckEncode(payload []byte) string {
	sum := doubleSHA256(payload)
	return Base58Encode(append(append([]byte{}, payload...), sum[:4]...))
}

// 解码并核对校验和，失败说明地址被改过。
func Base58CheckDecode(s string) ([]byte, error) {
	raw, err := Base58Decode(s)
	if err != nil {
		return nil, err
	}
	if len(raw) < 5 {
		return nil, errors.New("base58check too short")
	}
	payload := raw[:len(raw)-4]
	want := doubleSHA256(payload)
	if !bytes.Equal(raw[len(raw)-4:], want[:4]) {
		return nil, errors.New("base58check checksum mismatch")
	}
	return payload, nil
}

// 做两次 SHA-256，取前 4 字节当校验和。
func doubleSHA256(b []byte) [32]byte {
	first := sha256.Sum256(b)
	return sha256.Sum256(first[:])
}
