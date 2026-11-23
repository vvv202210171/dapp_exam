package aegis.chain.evm;

import aegis.domain.Models.AppError;
import aegis.platform.Support;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;

/**
 * 用 web3j 实现的结算客户端。OkHttp 固定 HTTP/1.1，Hardhat 才接受。
 */
public final class Web3jSettlementRpc implements SettlementRpc {
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .protocols(List.of(Protocol.HTTP_1_1))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();
    private static final Map<String, Web3j> CLIENTS = new ConcurrentHashMap<>();

    private final Web3j web3;

    /**
     * @param url 节点地址
     */
    public Web3jSettlementRpc(String url) {
        this.web3 = CLIENTS.computeIfAbsent(url, endpoint -> Web3j.build(new HttpService(endpoint, HTTP, false)));
    }

    /**
     * 读取 chainId。
     *
     * @return 链编号
     */
    @Override
    public BigInteger chainId() { return send(web3.ethChainId(), "eth_chainId").getChainId(); }

    /**
     * 读取地址上的合约代码。
     *
     * @param address 合约地址
     * @return 代码十六进制，空合约通常是 0x
     */
    @Override
    public String code(String address) {
        return send(web3.ethGetCode(address, org.web3j.protocol.core.DefaultBlockParameterName.LATEST), "eth_getCode").getCode();
    }

    /**
     * 读取原生 ETH 余额。
     *
     * @param address 地址
     * @return wei
     */
    @Override
    public BigInteger nativeBalance(String address) {
        return send(web3.ethGetBalance(address, org.web3j.protocol.core.DefaultBlockParameterName.LATEST), "eth_getBalance").getBalance();
    }

    /**
     * 执行只读调用，并把结果当成 uint256。
     *
     * @param to 合约地址
     * @param data calldata
     * @return 返回值
     */
    @Override
    public BigInteger callUint(String to, String data) {
        String value = send(web3.ethCall(Transaction.createEthCallTransaction(null, to, data), org.web3j.protocol.core.DefaultBlockParameterName.LATEST), "eth_call").getValue();
        return Support.hexToInt(value);
    }

    /**
     * 估算 gas 和 nonce，组装 EIP-1559 未签名交易。
     *
     * @param from 发送地址
     * @param to 接收地址
     * @param data calldata
     * @return 未签名载荷和手续费
     */
    @Override
    public Quote quote(String from, String to, String data) {
        Transaction call = Transaction.createEthCallTransaction(from, to, data);
        BigInteger gas = amount(send(web3.ethEstimateGas(call), "eth_estimateGas")).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        BigInteger gasPrice = send(web3.ethGasPrice(), "eth_gasPrice").getGasPrice();
        BigInteger tip = gasPrice.divide(BigInteger.TEN);
        try {
            BigInteger parsed = send(web3.ethMaxPriorityFeePerGas(), "eth_maxPriorityFeePerGas").getMaxPriorityFeePerGas();
            if (parsed.signum() > 0) tip = parsed;
        } catch (Exception ignored) {}
        BigInteger nonce = send(web3.ethGetTransactionCount(from, org.web3j.protocol.core.DefaultBlockParameterName.PENDING), "eth_getTransactionCount").getTransactionCount();
        return SettlementRpcs.quote(chainId(), nonce, gas, gasPrice, tip, to, data);
    }

    /**
     * 按哈希读取交易。
     *
     * @param hash 交易哈希
     * @return 发送方、接收方和 input
     */
    @Override
    public Tx transaction(String hash) {
        var found = send(web3.ethGetTransactionByHash(hash), "eth_getTransactionByHash").getTransaction();
        if (found.isEmpty()) throw AppError.of("NOT_FOUND", "节点上看不到这笔交易", "等小狐狸广播完成后再入账。");
        org.web3j.protocol.core.methods.response.Transaction tx = found.get();
        return new Tx(tx.getFrom(), tx.getTo() == null ? "" : tx.getTo(), tx.getInput());
    }

    /**
     * 广播已签名交易。
     *
     * @param signed 原始交易十六进制
     * @return 交易哈希
     */
    @Override
    public String sendRaw(String signed) {
        if (!signed.startsWith("0x")) signed = "0x" + signed;
        String hash = send(web3.ethSendRawTransaction(signed), "eth_sendRawTransaction").getTransactionHash();
        if (hash == null || hash.isEmpty()) throw new RuntimeException("节点没有返回交易哈希");
        return hash;
    }

    /**
     * 用节点里解锁的账户发送交易。
     *
     * @param from 运营方地址
     * @param to 接收地址
     * @param value 转出的 wei，空表示 0
     * @param data calldata，空表示没有
     * @return 交易哈希
     */
    @Override
    public String sendFrom(String from, String to, String value, String data) {
        boolean contract = data != null && !data.isEmpty() && !"0x".equals(data);
        BigInteger wei = value == null || value.isEmpty() || "0x0".equals(value) ? BigInteger.ZERO : Support.hexToInt(value);
        Transaction draft = contract
                ? Transaction.createFunctionCallTransaction(from, null, null, null, to, wei, data)
                : Transaction.createEtherTransaction(from, null, null, null, to, wei);
        BigInteger gas = amount(send(web3.ethEstimateGas(draft), "eth_estimateGas")).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        Transaction tx = contract
                ? Transaction.createFunctionCallTransaction(from, null, null, gas, to, wei, data)
                : Transaction.createEtherTransaction(from, null, null, gas, to, wei);
        EthSendTransaction sent = send(web3.ethSendTransaction(tx), "eth_sendTransaction");
        if (sent.getTransactionHash() == null || sent.getTransactionHash().isEmpty()) throw new RuntimeException("节点没有返回交易哈希");
        return sent.getTransactionHash();
    }

    /**
     * 等回执最多 8 秒。成功返回 confirmed，超时返回 pending，失败抛出回滚错误。
     *
     * @param hash 交易哈希
     * @return confirmed 或 pending
     */
    @Override
    public String wait(String hash) {
        long deadline = System.currentTimeMillis() + 8_000;
        while (true) {
            EthGetTransactionReceipt receipt = send(web3.ethGetTransactionReceipt(hash), "eth_getTransactionReceipt");
            if (receipt.getTransactionReceipt().isPresent()) {
                TransactionReceipt body = receipt.getTransactionReceipt().get();
                if ("0x1".equals(body.getStatus())) return "confirmed";
                throw AppError.of("REVERTED", "交易已上链但执行失败", "常见原因：没 approve、USDC 不够、链上可用余额不够，或这张账单已经结算过。");
            }
            if (System.currentTimeMillis() > deadline) return "pending";
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
    }

    /**
     * 发送 web3j 请求。节点错误和网络错误都转成运行时异常，外层沿用原来的提示。
     *
     * @param request web3j 请求
     * @param method 方法名，写进错误信息
     * @param <T> 响应类型
     * @return 响应
     */
    private static <T extends Response<?>> T send(Request<?, T> request, String method) {
        try {
            T response = request.send();
            if (response.hasError()) throw new RuntimeException(method + ": " + response.getError().getMessage());
            return response;
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage() == null ? "i/o timeout" : e.getMessage(), e);
        }
    }

    /**
     * 取出估算 gas。没有数量时当成失败。
     *
     * @param estimate 估算响应
     * @return gas
     */
    private static BigInteger amount(EthEstimateGas estimate) {
        if (estimate.getAmountUsed() == null) throw new RuntimeException("eth_estimateGas: 节点没有返回 gas");
        return estimate.getAmountUsed();
    }
}
