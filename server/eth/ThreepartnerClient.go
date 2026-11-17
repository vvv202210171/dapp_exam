package eth

import (
	"context"
	"errors"
	"fmt"
	"math/big"
	"strings"
	"time"

	"github.com/ethereum/go-ethereum"
	"github.com/ethereum/go-ethereum/accounts/abi"
	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/ethclient"
	"github.com/ethereum/go-ethereum/rpc"
)

const mintABI = `[{"inputs":[{"name":"to","type":"address"},{"name":"amount","type":"uint256"}],"name":"mint","outputs":[],"stateMutability":"nonpayable","type":"function"}]`

// form 是 Hardhat 已解锁的发送账户，to 是接收测试币的地址。
func sendTransThree(form string, to string, amount *big.Int) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	rpcClient, err := rpc.DialContext(ctx, rpcURL)
	if err != nil {
		return err
	}
	defer rpcClient.Close()

	parsed, err := abi.JSON(strings.NewReader(mintABI))
	if err != nil {
		return err
	}
	data, err := parsed.Pack("mint", common.HexToAddress(to), amount)
	if err != nil {
		return err
	}

	var hash common.Hash
	err = rpcClient.CallContext(ctx, &hash, "eth_sendTransaction", map[string]string{
		"from": form,
		"to":   usscContractAddr,
		"data": "0x" + common.Bytes2Hex(data),
	})
	if err != nil {
		return err
	}
	if hash == (common.Hash{}) {
		return fmt.Errorf("节点没有返回交易哈希")
	}

	client := ethclient.NewClient(rpcClient)
	deadline := time.Now().Add(8 * time.Second)
	for {
		receipt, err := client.TransactionReceipt(ctx, hash)
		if err == nil {
			if receipt.Status != 1 {
				return fmt.Errorf("交易已上链但执行失败: %s", hash.Hex())
			}
			return nil
		}
		if !errors.Is(err, ethereum.NotFound) {
			return err
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("交易已发送 %s，回执还没到", hash.Hex())
		}
		time.Sleep(200 * time.Millisecond)
	}
}
