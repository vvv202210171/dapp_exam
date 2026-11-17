package eth

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"strings"
	"time"
)

const (
	rpcURL = "http://127.0.0.1:8545"

	settleContractAddr = "0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512"

	usscContractAddr = "0x5FbDB2315678afecb367f032d93F642f64180aa3"
)

type RPCRequest struct {
	JSONRPC string        `json:"jsonrpc"`
	Method  string        `json:"method"`
	Params  []interface{} `json:"params"`
	ID      int           `json:"id"`
}

type RPCResponse struct {
	JSONRPC string    `json:"jsonrpc"`
	ID      int       `json:"id"`
	Result  string    `json:"result"`
	Error   *RPCError `json:"error"`
}

type RPCError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// 将 address 编码成 ABI 的 32 字节格式
func encodeAddress(address string) (string, error) {
	address = strings.TrimPrefix(address, "0x")

	if len(address) != 40 {
		return "", fmt.Errorf("address 长度不正确")
	}

	if _, err := hexDecode(address); err != nil {
		return "", err
	}

	return strings.Repeat("0", 24) + strings.ToLower(address), nil
}

// 原生十六进制校验
func hexDecode(s string) ([]byte, error) {
	return hex.DecodeString(s)
}

// 查询用户未结算余额
func ContractCall(user string, token string) (*big.Int, error) {

	// 1. 编码 user 地址
	userData, err := encodeAddress(user)
	if err != nil {
		return nil, err
	}

	// 2. 编码 token 地址
	tokenData, err := encodeAddress(token)
	if err != nil {
		return nil, err
	}

	// 3. available(address,address) 函数选择器
	//methodID := "available(address,address)"

	// 注意：这里需要填入正确的 Keccak-256 函数选择器
	selector := "bbe430de"

	// 4. 拼接 ABI 参数
	data := "0x" + selector + userData + tokenData

	// 5. 构造 JSON-RPC 请求
	rpcReq := RPCRequest{
		JSONRPC: "2.0",
		Method:  "eth_call",
		Params: []interface{}{
			map[string]interface{}{
				"to":   settleContractAddr,
				"data": data,
			},
			"latest",
		},
		ID: 1,
	}

	reqBody, err := json.Marshal(rpcReq)
	if err != nil {
		return nil, err
	}

	// 6. HTTP POST
	req, err := http.NewRequest(
		http.MethodPost,
		rpcURL,
		bytes.NewBuffer(reqBody),
	)
	if err != nil {
		return nil, err
	}

	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{
		Timeout: 10 * time.Second,
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP错误: %s, %s", resp.Status, body)
	}

	// 7. 解析 RPC 响应
	var rpcResp RPCResponse

	if err := json.Unmarshal(body, &rpcResp); err != nil {
		return nil, err
	}

	if rpcResp.Error != nil {
		return nil, fmt.Errorf(
			"RPC错误: %s",
			rpcResp.Error.Message,
		)
	}

	// 8. 解码 uint256
	result := strings.TrimPrefix(rpcResp.Result, "0x")

	amount := new(big.Int)

	if _, ok := amount.SetString(result, 16); !ok {
		return nil, fmt.Errorf("uint256 解码失败: %s", result)
	}

	return amount, nil
}

// 给测试地址增发代币

func MintToken(to string, amount *big.Int) error {
	address, err := encodeAddress(to)
	if err != nil {
		return err
	}

	amountHex := fmt.Sprintf("%064x", amount)

	//mint(address to, uint256 amount)
	req := RPCRequest{
		JSONRPC: "2.0",
		Method:  "eth_call",
		Params: []interface{}{
			map[string]interface{}{
				"to":   usscContractAddr,
				"data": "0x" + "40c10f19" + address + amountHex, // mint(address,uint256) 函数选择器
			},
			"latest",
		},
		ID: 1,
	}

	reqBody, err := json.Marshal(req)
	if err != nil {
		return err
	}

	httpReq, err := http.NewRequest(
		http.MethodPost,
		rpcURL,
		bytes.NewBuffer(reqBody),
	)
	if err != nil {
		return err
	}

	httpReq.Header.Set("Content-Type", "application/json")

	client := &http.Client{
		Timeout: 10 * time.Second,
	}

	resp, err := client.Do(httpReq)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP错误: %s, %s", resp.Status, body)
	}

	var rpcResp RPCResponse

	if err := json.Unmarshal(body, &rpcResp); err != nil {
		return err
	}

	if rpcResp.Error != nil {
		return fmt.Errorf(
			"RPC错误: %s",
			rpcResp.Error.Message,
		)
	}

	return nil

}

// form 是已解锁的发送账户，to 是接收测试币的地址。交易发给 MockUSDC，调用 mint。
func sendTrans(form string, to string, amount *big.Int) error {
	recipient, err := encodeAddress(to)
	if err != nil {
		return err
	}
	amountHex := fmt.Sprintf("%064x", amount)

	req := RPCRequest{
		JSONRPC: "2.0",
		Method:  "eth_sendTransaction",
		Params: []interface{}{
			map[string]interface{}{
				"from": form,
				"to":   usscContractAddr,
				"data": "0x" + "40c10f19" + recipient + amountHex,
			},
		},
		ID: 1,
	}

	reqBody, err := json.Marshal(req)
	if err != nil {
		return err
	}
	httpReq, err := http.NewRequest(http.MethodPost, rpcURL, bytes.NewBuffer(reqBody))
	if err != nil {
		return err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(httpReq)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP错误: %s, %s", resp.Status, body)
	}

	var rpcResp RPCResponse
	if err := json.Unmarshal(body, &rpcResp); err != nil {
		return err
	}
	if rpcResp.Error != nil {
		return fmt.Errorf("RPC错误: %s", rpcResp.Error.Message)
	}
	if rpcResp.Result == "" {
		return fmt.Errorf("节点没有返回交易哈希")
	}
	return nil
}
