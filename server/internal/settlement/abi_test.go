package settlement

import (
	"math/big"
	"strings"
	"testing"
)

func TestLoopback(t *testing.T) {
	if !loopback("http://127.0.0.1:8545") || !loopback("http://localhost:8545") {
		t.Fatal("本机地址应放行")
	}
	if loopback("https://ethereum.publicnode.com") || loopback("http://8.8.8.8:8545") {
		t.Fatal("公网地址应拒绝")
	}
}

func TestCentsToUnits(t *testing.T) {
	if got := CentsToUnits(2500); got.Cmp(big.NewInt(25_000_000)) != 0 {
		t.Fatalf("2500 分应是 25000000 个最小单位，得到 %s", got)
	}
}

func TestBillRefStable(t *testing.T) {
	a := BillRef("entry-1")
	b := BillRef("entry-1")
	if a != b || len(a) != 66 || !strings.HasPrefix(a, "0x") {
		t.Fatalf("账单号应是稳定的 32 字节，得到 %s", a)
	}
	if BillRef("entry-2") == a {
		t.Fatal("不同流水应得到不同账单号")
	}
}

func TestNormalizeTxHash(t *testing.T) {
	got, err := NormalizeTxHash("0xAbCDef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")
	if err != nil || got != "0xabcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789" {
		t.Fatalf("哈希应规范成小写: %s %v", got, err)
	}
	if _, err := NormalizeTxHash(""); err == nil {
		t.Fatal("没有交易哈希不能入账")
	}
}

func TestParseDepositRoundTrip(t *testing.T) {
	token := "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
	data, err := DepositData(token, big.NewInt(22_300_000))
	if err != nil {
		t.Fatal(err)
	}
	gotToken, gotAmount, err := ParseDeposit(data)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.EqualFold(gotToken, token) || gotAmount.Cmp(big.NewInt(22_300_000)) != 0 {
		t.Fatalf("解析结果不对: %s %s", gotToken, gotAmount)
	}
	if _, _, err := ParseDeposit("0x095ea7b3"); err == nil {
		t.Fatal("授权交易不能当成存入")
	}
}

func TestDepositCalldata(t *testing.T) {
	data, err := DepositData("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", big.NewInt(25_000_000))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(data, "0x47e7ef24") {
		t.Fatalf("deposit 选择器不对: %s", data)
	}
	if len(data) != 2+8+64+64 {
		t.Fatalf("deposit calldata 长度不对: %d", len(data))
	}
}
