package eth

import (
	"fmt"
	"math/big"
	"testing"
)

func TestContractCall(t *testing.T) {
	data, err := ContractCall("0x8a8bc31678b81e094ceed04e11a4faf8c89af626", "0x5FbDB2315678afecb367f032d93F642f64180aa3")
	if err != nil {
		t.Errorf("ContractCall failed: %v", err)
	}
	fmt.Println(data)

}

func TestContractCall2(t *testing.T) {
	err := MintToken("0x8a8bc31678b81e094ceed04e11a4faf8c89af626", big.NewInt(11000_000))
	if err != nil {
		t.Errorf("MintToken failed: %v", err)
	}
}

func TestSendTrans(t *testing.T) {
	err := sendTrans("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", "0x8a8bc31678b81e094ceed04e11a4faf8c89af626", big.NewInt(12_000_000))
	if err != nil {
		t.Errorf("sendTrans failed: %v", err)
	}
}
