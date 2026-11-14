// 卡号加密。AES-GCM 的 nonce 放在密文前面。密钥来自 CARD_KEY，否则写到 data/card.key，生产环境应换成 KMS。
package platform

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// 用 AES-GCM 加密，nonce 放在密文前面，再做 base64。
func Encrypt(key, plain []byte) (string, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	out := gcm.Seal(nonce, nonce, plain, nil)
	return base64.StdEncoding.EncodeToString(out), nil
}

// 解密 Encrypt 产生的字符串。
func Decrypt(key []byte, encoded string) ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(raw) < gcm.NonceSize() {
		return nil, errors.New("ciphertext too short")
	}
	nonce, ct := raw[:gcm.NonceSize()], raw[gcm.NonceSize():]
	return gcm.Open(nil, nonce, ct, nil)
}

// 读取 32 字节 AES 密钥。生产环境应来自 KMS；沙箱放在 CARD_KEY 或本地文件里，重启后仍能解开演示卡号。
func LoadMasterKey(path, hexKey string) ([]byte, error) {
	hexKey = strings.TrimSpace(hexKey)
	if hexKey != "" {
		b, err := hex.DecodeString(hexKey)
		if err != nil || len(b) != 32 {
			return nil, fmt.Errorf("CARD_KEY 必须是 64 位十六进制（32 字节）")
		}
		return b, nil
	}
	if b, err := os.ReadFile(path); err == nil {
		b = bytes.TrimSpace(b)
		if len(b) == 32 {
			return b, nil
		}
		if decoded, err := hex.DecodeString(string(b)); err == nil && len(decoded) == 32 {
			return decoded, nil
		}
	}
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, []byte(hex.EncodeToString(key)), 0o600); err != nil {
		return nil, err
	}
	return key, nil
}
