package platform

import "testing"

// 用全零载荷核对 TRON 地址的 base58check。
func TestTronZeroAddress(t *testing.T) {
	payload := make([]byte, 21)
	payload[0] = 0x41
	got := Base58CheckEncode(payload)
	const want = "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb"
	if got != want {
		t.Fatalf("got %s", got)
	}
	back, err := Base58CheckDecode(got)
	if err != nil {
		t.Fatal(err)
	}
	if len(back) != 21 || back[0] != 0x41 {
		t.Fatalf("decoded %#v", back)
	}
}

// 核对系统程序地址是 32 个 0 的 base58。
func TestSolanaSystemProgram(t *testing.T) {
	got := Base58Encode(make([]byte, 32))
	if got != "11111111111111111111111111111111" {
		t.Fatalf("got %s", got)
	}
	raw, err := Base58Decode(got)
	if err != nil {
		t.Fatal(err)
	}
	if len(raw) != 32 {
		t.Fatalf("len %d", len(raw))
	}
}
