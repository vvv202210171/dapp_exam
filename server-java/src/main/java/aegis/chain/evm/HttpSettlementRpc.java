package aegis.chain.evm;

import aegis.domain.Models.AppError;
import aegis.platform.Support;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手写 JSON-RPC 的结算客户端。不依赖 web3j。
 */
public final class HttpSettlementRpc implements SettlementRpc {
    private final String url;
    private final Support.HttpJson http;

    /**
     * @param url 节点地址
     * @param http HTTP 客户端
     */
    public HttpSettlementRpc(String url, Support.HttpJson http) {
        this.url = url;
        this.http = http;
    }

    /**
     * 读取 chainId。
     *
     * @return 链编号
     */
    @Override
    public BigInteger chainId() { return parse(call("eth_chainId", List.of())); }

    /**
     * 读取地址上的合约代码。
     *
     * @param address 合约地址
     * @return 代码十六进制，空合约通常是 0x
     */
    @Override
    public String code(String address) { return text(call("eth_getCode", List.of(address, "latest"))); }

    /**
     * 读取原生 ETH 余额。
     *
     * @param address 地址
     * @return wei
     */
    @Override
    public BigInteger nativeBalance(String address) { return parse(call("eth_getBalance", List.of(address, "latest"))); }

    /**
     * 执行只读调用，并把结果当成 uint256。
     *
     * @param to 合约地址
     * @param data calldata
     * @return 返回值
     */
    @Override
    public BigInteger callUint(String to, String data) {
        return parse(call("eth_call", List.of(Map.of("to", to, "data", data), "latest")));
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
        Map<String, String> call = Map.of("from", from, "to", to, "data", data);
        BigInteger gas = parse(call("eth_estimateGas", List.of(call))).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        BigInteger gasPrice = parse(call("eth_gasPrice", List.of()));
        BigInteger tip = gasPrice.divide(BigInteger.TEN);
        try {
            BigInteger parsed = parse(call("eth_maxPriorityFeePerGas", List.of()));
            if (parsed.signum() > 0) tip = parsed;
        } catch (Exception ignored) {}
        BigInteger nonce = parse(call("eth_getTransactionCount", List.of(from, "pending")));
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
        JsonNode raw = call("eth_getTransactionByHash", List.of(hash));
        if (raw == null || raw.isNull()) throw AppError.of("NOT_FOUND", "节点上看不到这笔交易", "等小狐狸广播完成后再入账。");
        return new Tx(raw.path("from").asText(""), raw.path("to").asText(""), raw.path("input").asText(""));
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
        String hash = text(call("eth_sendRawTransaction", List.of(signed)));
        if (hash.isEmpty()) throw new RuntimeException("节点没有返回交易哈希");
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
        Map<String, String> tx = new LinkedHashMap<>();
        tx.put("from", from);
        tx.put("to", to);
        if (value != null && !value.isEmpty() && !"0x0".equals(value)) tx.put("value", value);
        if (data != null && !data.isEmpty() && !"0x".equals(data)) tx.put("data", data);
        BigInteger gas = parse(call("eth_estimateGas", List.of(tx))).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        tx.put("gas", Support.quantityHex(gas));
        return text(call("eth_sendTransaction", List.of(tx)));
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
            JsonNode raw = call("eth_getTransactionReceipt", List.of(hash));
            if (raw != null && !raw.isNull() && !raw.isMissingNode()) {
                String status = raw.path("status").asText("");
                if ("0x1".equals(status)) return "confirmed";
                throw AppError.of("REVERTED", "交易已上链但执行失败", "常见原因：没 approve、USDC 不够、链上可用余额不够，或这张账单已经结算过。");
            }
            if (System.currentTimeMillis() > deadline) return "pending";
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
    }

    /**
     * 发送一次 JSON-RPC。
     *
     * @param method 方法名
     * @param params 参数
     * @return result 节点
     */
    private JsonNode call(String method, Object params) {
        JsonNode envelope = http.postJson(url, Support.rpcBody(method, params));
        if (envelope.has("error") && !envelope.get("error").isNull()) {
            throw new RuntimeException(method + ": " + envelope.path("error").path("message").asText(""));
        }
        return envelope.get("result");
    }

    /**
     * 把 JSON 文本节点转成字符串。
     *
     * @param raw JSON 节点
     * @return 文本，空节点时为空字符串
     */
    private static String text(JsonNode raw) { return raw == null || raw.isNull() ? "" : raw.asText(""); }

    /**
     * 把十六进制数量解析成整数。
     *
     * @param raw JSON 节点
     * @return 整数，空值时为 0
     */
    private static BigInteger parse(JsonNode raw) {
        if (raw == null || raw.isNull()) return BigInteger.ZERO;
        return Support.hexToInt(raw.asText(raw.toString().replace("\"", "")));
    }
}
