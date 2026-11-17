package eth

import (
	"math/big"
	"testing"
)

func TestMintToken(t *testing.T) {
	sendTransThree("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", "0x8a8bc31678b81e094ceed04e11a4faf8c89af626", big.NewInt(6_000_000))
}
