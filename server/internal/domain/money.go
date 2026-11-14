// 金额全部用十进制字符串和 big.Int。浮点数会把 0.1 这类值算丢，链上单位不能走 float。
package domain

import (
	"fmt"
	"math/big"
	"strings"
)

// ParseUnits 把 "1.5" 按精度转成最小单位。ETH 是 18 位，ETH 上的 USDT 是 6 位，BSC 上的 USDT 是 18 位。
func ParseUnits(amount string, decimals int) (*big.Int, error) {
	amount = strings.TrimSpace(amount)
	if amount == "" || strings.HasPrefix(amount, "+") || strings.HasPrefix(amount, "-") {
		return nil, fmt.Errorf("金额格式不正确")
	}
	if decimals < 0 || decimals > 36 {
		return nil, fmt.Errorf("精度不支持")
	}
	parts := strings.Split(amount, ".")
	if len(parts) > 2 {
		return nil, fmt.Errorf("金额格式不正确")
	}
	whole := parts[0]
	frac := ""
	if len(parts) == 2 {
		frac = parts[1]
	}
	if whole == "" {
		whole = "0"
	}
	if !digits(whole) || !digits(frac) {
		return nil, fmt.Errorf("金额格式不正确")
	}
	if len(frac) > decimals {
		return nil, fmt.Errorf("最多支持 %d 位小数", decimals)
	}
	frac += strings.Repeat("0", decimals-len(frac))
	combined := strings.TrimLeft(whole+frac, "0")
	if combined == "" {
		return big.NewInt(0), nil
	}
	if len(combined) > 78 {
		return nil, fmt.Errorf("金额过大")
	}
	n, ok := new(big.Int).SetString(combined, 10)
	if !ok {
		return nil, fmt.Errorf("金额格式不正确")
	}
	return n, nil
}

// 把最小单位还原成十进制字符串，并去掉小数末尾的 0。
func FormatUnits(v *big.Int, decimals int) string {
	if v == nil || v.Sign() == 0 {
		return "0"
	}
	neg := v.Sign() < 0
	s := new(big.Int).Abs(v).String()
	if decimals == 0 {
		if neg {
			return "-" + s
		}
		return s
	}
	if len(s) <= decimals {
		s = strings.Repeat("0", decimals-len(s)+1) + s
	}
	whole := s[:len(s)-decimals]
	frac := strings.TrimRight(s[len(s)-decimals:], "0")
	out := whole
	if frac != "" {
		out += "." + frac
	}
	if neg {
		return "-" + out
	}
	return out
}

// ParseCents 只接受美元两位小数，例如 "25.50" 得到 2550。
func ParseCents(amount string) (int64, error) {
	n, err := ParseUnits(amount, 2)
	if err != nil {
		return 0, err
	}
	if !n.IsInt64() {
		return 0, fmt.Errorf("金额过大")
	}
	if n.Sign() <= 0 {
		return 0, fmt.Errorf("金额必须大于 0")
	}
	return n.Int64(), nil
}

// 用金额乘单价，得到保留两位小数的美元字符串。
func MulUSD(amount, price string) (string, error) {
	a := new(big.Rat)
	if _, ok := a.SetString(strings.TrimSpace(amount)); !ok {
		return "", fmt.Errorf("金额格式不正确")
	}
	p := new(big.Rat)
	if _, ok := p.SetString(strings.TrimSpace(price)); !ok {
		return "", fmt.Errorf("价格格式不正确")
	}
	a.Mul(a, p)
	return formatRat(a, 2), nil
}

// 把多笔美元字符串相加，跳过无法解析的项。
func SumUSD(values []string) string {
	total := new(big.Rat)
	for _, v := range values {
		r := new(big.Rat)
		if _, ok := r.SetString(v); ok {
			total.Add(total, r)
		}
	}
	return formatRat(total, 2)
}

// 把有理数四舍五入到指定小数位。
func formatRat(r *big.Rat, places int) string {
	scale := new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(places)), nil)
	scaled := new(big.Rat).Mul(r, new(big.Rat).SetInt(scale))
	if scaled.Sign() >= 0 {
		scaled.Add(scaled, big.NewRat(1, 2))
	} else {
		scaled.Sub(scaled, big.NewRat(1, 2))
	}
	i := new(big.Int).Quo(scaled.Num(), scaled.Denom())
	return formatFixed(i, places)
}

// 把已经放大的整数排成固定小数位的字符串。
func formatFixed(v *big.Int, places int) string {
	neg := v.Sign() < 0
	s := new(big.Int).Abs(v).String()
	if len(s) <= places {
		s = strings.Repeat("0", places-len(s)+1) + s
	}
	out := s[:len(s)-places] + "." + s[len(s)-places:]
	if neg {
		return "-" + out
	}
	return out
}

// 判断字符串是不是只含数字，空串也算通过。
func digits(s string) bool {
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// 返回 10 的 n 次方，用来换算代币精度。
func Pow10(n int) *big.Int {
	return new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(n)), nil)
}
