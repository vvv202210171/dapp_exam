package aegis.chain.evm;

import aegis.chain.Chains;
import aegis.domain.Models.EvmPayload;
import aegis.domain.Money;
import aegis.platform.Support;
import java.math.BigInteger;
import java.util.Map;

/**
 * 按配置选择结算链客户端，并组装两边共用的报价。
 */
public final class SettlementRpcs {
    private SettlementRpcs() {}

    /**
     * 打开一个结算客户端。
     *
     * @param kind http 用手写 JSON-RPC，web3j 用 web3j
     * @param url 节点地址
     * @param http 手写客户端用的 HTTP；web3j 不使用
     * @return 结算客户端
     */
    public static SettlementRpc open(String kind, String url, Support.HttpJson http) {
        if (kind == null || kind.isBlank() || "http".equalsIgnoreCase(kind)) return new HttpSettlementRpc(url, http);
        if ("web3j".equalsIgnoreCase(kind)) return new Web3jSettlementRpc(url);
        throw new IllegalArgumentException("CHAIN_CLIENT 只能是 http 或 web3j");
    }

    /**
     * 把已经算好的 gas、nonce 和费用收成未签名交易。两种客户端共用，避免报价形状分叉。
     *
     * @param chainId 链编号
     * @param nonce pending nonce
     * @param gas 已上浮的 gas
     * @param gasPrice 节点 gas 价格，单位 wei
     * @param tip 优先费，单位 wei
     * @param to 接收地址
     * @param data calldata
     * @return 报价
     */
    static SettlementRpc.Quote quote(BigInteger chainId, BigInteger nonce, BigInteger gas, BigInteger gasPrice, BigInteger tip, String to, String data) {
        BigInteger maxFee = gasPrice.multiply(BigInteger.TWO).add(tip);
        if (tip.compareTo(maxFee) > 0) tip = maxFee;
        EvmPayload payload = new EvmPayload();
        payload.type = 2;
        payload.chainId = chainId.toString();
        payload.nonce = nonce.toString();
        payload.to = to;
        payload.value = "0";
        payload.data = data;
        payload.gasLimit = gas.toString();
        payload.maxFeePerGas = maxFee.toString();
        payload.maxPriorityFeePerGas = tip.toString();
        return new SettlementRpc.Quote(payload, Chains.fee("ETH", Money.formatUnits(gas.multiply(maxFee), 18), Map.of(
                "gasLimit", gas.toString(), "maxFeePerGas", maxFee.toString(), "maxPriorityFeePerGas", tip.toString())));
    }
}
