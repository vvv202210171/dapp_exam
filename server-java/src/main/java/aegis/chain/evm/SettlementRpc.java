package aegis.chain.evm;

import aegis.domain.Models.EvmPayload;
import aegis.domain.Models.Fee;
import java.math.BigInteger;

/**
 * 本地结算链的读和发交易。手写 JSON-RPC 和 web3j 都实现这一套。
 */
public interface SettlementRpc {
    /**
     * 读取 chainId。
     *
     * @return 链编号
     */
    BigInteger chainId();

    /**
     * 读取地址上的合约代码。
     *
     * @param address 合约地址
     * @return 代码十六进制，空合约通常是 0x
     */
    String code(String address);

    /**
     * 读取原生 ETH 余额。
     *
     * @param address 地址
     * @return wei
     */
    BigInteger nativeBalance(String address);

    /**
     * 执行只读调用，并把结果当成 uint256。
     *
     * @param to 合约地址
     * @param data calldata
     * @return 返回值
     */
    BigInteger callUint(String to, String data);

    /**
     * 估算 gas 和 nonce，组装 EIP-1559 未签名交易。
     *
     * @param from 发送地址
     * @param to 接收地址
     * @param data calldata
     * @return 未签名载荷和手续费
     */
    Quote quote(String from, String to, String data);

    /**
     * 按哈希读取交易。
     *
     * @param hash 交易哈希
     * @return 发送方、接收方和 input
     */
    Tx transaction(String hash);

    /**
     * 广播已签名交易。
     *
     * @param signed 原始交易十六进制
     * @return 交易哈希
     */
    String sendRaw(String signed);

    /**
     * 用节点里解锁的账户发送交易。
     *
     * @param from 运营方地址
     * @param to 接收地址
     * @param value 转出的 wei，空表示 0
     * @param data calldata，空表示没有
     * @return 交易哈希
     */
    String sendFrom(String from, String to, String value, String data);

    /**
     * 等回执最多 8 秒。成功返回 confirmed，超时返回 pending，失败抛出回滚错误。
     *
     * @param hash 交易哈希
     * @return confirmed 或 pending
     */
    String wait(String hash);

    /**
     * 报价结果。
     *
     * @param payload 未签名交易
     * @param fee 手续费
     */
    record Quote(EvmPayload payload, Fee fee) {}

    /**
     * 链上交易的发送方、接收方和输入数据。
     *
     * @param from 发送地址
     * @param to 接收地址
     * @param input calldata
     */
    record Tx(String from, String to, String input) {}
}
