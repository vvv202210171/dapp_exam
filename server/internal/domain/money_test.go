package domain

import (
	"math/big"
	"testing"
)

// 检查金额解析、小数位限制和格式化是否互逆。
func TestParseFormatUnits(t *testing.T) {
	n, err := ParseUnits("1.5", 8)
	if err != nil {
		t.Fatal(err)
	}
	if n.Cmp(big.NewInt(150_000_000)) != 0 {
		t.Fatalf("got %s", n)
	}
	if got := FormatUnits(n, 8); got != "1.5" {
		t.Fatalf("format got %s", got)
	}
	wei, err := ParseUnits("0.00042", 18)
	if err != nil {
		t.Fatal(err)
	}
	if got := FormatUnits(wei, 18); got != "0.00042" {
		t.Fatalf("wei format %s", got)
	}
	if _, err := ParseUnits("1.000", 2); err == nil {
		t.Fatal("expected too many decimals")
	}
	if _, err := ParseCents("0"); err == nil {
		t.Fatal("expected zero rejected")
	}
	cents, err := ParseCents("10.5")
	if err != nil || cents != 1050 {
		t.Fatalf("cents %d %v", cents, err)
	}
	if got := FormatCents(1990); got != "19.90" {
		t.Fatalf("format cents %s", got)
	}
}

// 检查美元乘法的四舍五入。
func TestMulUSD(t *testing.T) {
	got, err := MulUSD("1.5", "3200")
	if err != nil {
		t.Fatal(err)
	}
	if got != "4800.00" {
		t.Fatalf("got %s", got)
	}
	if SumUSD([]string{"1.20", "0.05"}) != "1.25" {
		t.Fatal(SumUSD([]string{"1.20", "0.05"}))
	}
}
