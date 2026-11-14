package service

import (
	"context"
	"path/filepath"
	"testing"

	"aegis/internal/domain"
	"aegis/internal/store"
)

// 检查充值、消费、提现手续费、幂等和实体卡未激活不能消费。
func TestCardLedger(t *testing.T) {
	st := openTestDB(t)
	acc, err := st.SyncAccount(context.Background(), "面试账户", addrs())
	if err != nil {
		t.Fatal(err)
	}
	cards := NewCard(st, []byte("0123456789abcdef0123456789abcdef"))
	issued, err := cards.Create(context.Background(), acc.ID, "virtual", "Ada")
	if err != nil {
		t.Fatal(err)
	}
	if len(issued.PAN) != 16 || issued.PAN[:6] != "990016" {
		t.Fatalf("pan %s", issued.PAN)
	}
	if !luhnOK(issued.PAN) {
		t.Fatalf("luhn %s", issued.PAN)
	}
	secret, err := cards.Reveal(context.Background(), issued.Card.ID)
	if err != nil || secret.PAN != issued.PAN || secret.CVV != issued.CVV {
		t.Fatalf("reveal %+v %v", secret, err)
	}

	top, err := cards.Topup(context.Background(), issued.Card.ID, MoveInput{Amount: "100.00", IdempotencyKey: "top-1"})
	if err != nil {
		t.Fatal(err)
	}
	again, err := cards.Topup(context.Background(), issued.Card.ID, MoveInput{Amount: "100.00", IdempotencyKey: "top-1"})
	if err != nil || !again.Replayed || again.Card.Available != top.Card.Available {
		t.Fatalf("replay %+v %v", again, err)
	}
	if got := int64(top.Card.Available); got != 10000 {
		t.Fatalf("available %d", got)
	}
	if _, err := cards.Spend(context.Background(), issued.Card.ID, MoveInput{Amount: "40.00", Merchant: "星巴克", MCC: "5814", IdempotencyKey: "spend-1"}); err != nil {
		t.Fatal(err)
	}
	withdrawn, err := cards.Withdraw(context.Background(), issued.Card.ID, MoveInput{Amount: "10.00", IdempotencyKey: "wd-1"})
	if err != nil {
		t.Fatal(err)
	}
	// 100 - 40 - 10 - 0.10
	if int64(withdrawn.Card.Available) != 4990 {
		t.Fatalf("after withdraw %d", withdrawn.Card.Available)
	}
	if _, err := cards.Spend(context.Background(), issued.Card.ID, MoveInput{Amount: "80", Merchant: "超市", IdempotencyKey: "spend-2"}); err == nil {
		t.Fatal("expected insufficient")
	}

	physical, err := cards.Create(context.Background(), acc.ID, "physical", "Ada")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := cards.Topup(context.Background(), physical.Card.ID, MoveInput{Amount: "20", IdempotencyKey: "p-top"}); err != nil {
		t.Fatal(err)
	}
	if _, err := cards.Spend(context.Background(), physical.Card.ID, MoveInput{Amount: "1", Merchant: "地铁", IdempotencyKey: "p-spend"}); err == nil {
		t.Fatal("physical card should not spend before activation")
	}
	for _, action := range []string{"issue", "ship", "activate"} {
		if _, err := cards.Transition(context.Background(), physical.Card.ID, action); err != nil {
			t.Fatal(action, err)
		}
	}
	if _, err := cards.Spend(context.Background(), physical.Card.ID, MoveInput{Amount: "1.50", Merchant: "地铁", IdempotencyKey: "p-spend-2"}); err != nil {
		t.Fatal(err)
	}
	bill, err := cards.Bill(context.Background(), physical.Card.ID, "")
	if err != nil {
		t.Fatal(err)
	}
	if int64(bill.Spend) != 150 || int64(bill.Topup) != 2000 {
		t.Fatalf("bill %+v", bill)
	}
}

// 验证卡号是否通过 Luhn。
func luhnOK(pan string) bool {
	base := pan[:len(pan)-1]
	want := pan[len(pan)-1] - '0'
	return luhnCheck(base) == int(want)
}

// 开一个临时数据库。
func openTestDB(t *testing.T) *store.Store {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "aegis.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

// 给测试准备五条链的假地址。
func addrs() []domain.Address {
	return []domain.Address{
		{Chain: domain.Bitcoin, Address: "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", Path: "m/84'/0'/0'/0/0"},
		{Chain: domain.Ethereum, Address: "0x9858effd232b4033e47d90003d41ec34ecaeda94", Path: "m/44'/60'/0'/0/0"},
		{Chain: domain.BNB, Address: "0x9858effd232b4033e47d90003d41ec34ecaeda94", Path: "m/44'/60'/0'/0/0"},
		{Chain: domain.Solana, Address: "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", Path: "m/44'/501'/0'/0'"},
		{Chain: domain.Tron, Address: "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb", Path: "m/44'/195'/0'/0/0"},
	}
}
