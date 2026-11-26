package txbuild

import (
	"encoding/base64"
	"encoding/binary"
	"math/big"
	"testing"

	"aegis/internal/domain"
	"aegis/internal/platform"
)

// 检查选币、找零和余额不足。
func TestSelectCoins(t *testing.T) {
	utxos := []domain.BTCInput{
		{TxID: "aa", Vout: 0, Value: 10_000},
		{TxID: "bb", Vout: 1, Value: 30_000},
		{TxID: "cc", Vout: 0, Value: 20_000},
	}
	selected, fee, change, err := SelectCoins(utxos, 25_000, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(selected) != 1 || selected[0].TxID != "bb" {
		t.Fatalf("selected %#v", selected)
	}
	if fee <= 0 || change <= 0 {
		t.Fatalf("fee %d change %d", fee, change)
	}
	if selected[0].Value != 25_000+fee+change {
		t.Fatalf("conservation failed")
	}
}

// 检查 transfer 和 balanceOf 的函数选择器。
func TestERC20Selector(t *testing.T) {
	data, err := ERC20TransferData("0x0000000000000000000000000000000000000001", big.NewInt(1))
	if err != nil {
		t.Fatal(err)
	}
	if data[:10] != "0xa9059cbb" {
		t.Fatalf("selector %s", data[:10])
	}
	bal, err := ERC20BalanceOfData("0x0000000000000000000000000000000000000002")
	if err != nil {
		t.Fatal(err)
	}
	if bal[:10] != "0x70a08231" {
		t.Fatalf("balanceOf %s", bal[:10])
	}
}

// 检查 Solana 消息的头部、账户数和系统程序。
func TestSOLMessageShape(t *testing.T) {
	from := platform.Base58Encode(bytesRepeat(1))
	to := platform.Base58Encode(bytesRepeat(2))
	bh := platform.Base58Encode(bytesRepeat(3))
	msg, err := BuildSOLTransfer(from, to, 42, bh)
	if err != nil {
		t.Fatal(err)
	}
	if msg[0] != 1 || msg[1] != 0 || msg[2] != 1 {
		t.Fatalf("header %v", msg[:3])
	}
	if msg[3] != 3 {
		t.Fatalf("account count %d", msg[3])
	}
	// system program sits after two 32-byte keys
	sys := msg[4+64 : 4+96]
	for _, b := range sys {
		if b != 0 {
			t.Fatal("system program is not zero")
		}
	}
	// last 12 bytes are the transfer instruction data
	data := msg[len(msg)-12:]
	if binary.LittleEndian.Uint32(data[:4]) != 2 {
		t.Fatal("instruction index")
	}
	if binary.LittleEndian.Uint64(data[4:]) != 42 {
		t.Fatal("lamports")
	}
	_ = base64.StdEncoding.EncodeToString(msg)
}

// 关联账户要和独立算出的地址一致，转账消息只签一次。
func TestSPLAssociatedAccount(t *testing.T) {
	owner := "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
	got, err := AssociatedTokenAddress(owner, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
	if err != nil {
		t.Fatal(err)
	}
	if got != "5N3f1tj9v1vc5TUZ8S7mCAnVmjVKrfnzXWhxLaxyZAgt" {
		t.Fatalf("ata %s", got)
	}
	dev, err := AssociatedTokenAddress(owner, "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")
	if err != nil {
		t.Fatal(err)
	}
	if dev != "Bjr2Shw9sy31UNdm8kYs5y72iTbnxB4JemK98vJqyQWm" {
		t.Fatalf("dev ata %s", dev)
	}
	msg, err := BuildSOLTokenTransfer(owner, got, dev, "4fYNw3dojWmQ4dXtSGE9epjRGy9pFSx62YypT7avPYvA", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", 1_000_000, 6, false, platform.Base58Encode(bytesRepeat(9)))
	if err != nil {
		t.Fatal(err)
	}
	if msg[0] != 1 || msg[1] != 0 || msg[2] != 2 || msg[3] != 5 || msg[len(msg)-1] != 6 {
		t.Fatalf("message header %v tail %d", msg[:4], msg[len(msg)-1])
	}
}

// 测试里生成一段重复字节。
func bytesRepeat(v byte) []byte {
	b := make([]byte, 32)
	for i := range b {
		b[i] = v
	}
	return b
}
