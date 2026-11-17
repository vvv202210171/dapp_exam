package aegis.chain.live;

import aegis.chain.Chains;
import aegis.domain.Models.AppError;
import aegis.domain.Models.Asset;
import aegis.domain.Models.EvmPayload;
import aegis.domain.Models.Fee;
import aegis.domain.Models.Health;
import aegis.domain.Models.PrepareResult;
import aegis.domain.Models.Probe;
import aegis.domain.Models.TxState;
import aegis.domain.Money;
import aegis.platform.Support;
import aegis.txbuild.TxBuild;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ethereum 和 BNB 的真实节点适配器。报价使用 EIP-1559。
 */
public final class EvmClient implements Chains.Adapter {
    private final String chain;
    private final String network;
    private final String rpc;
    private final Support.HttpJson http;

    /**
     * @param chain ethereum 或 bnb
     * @param network 网络
     * @param rpc 节点地址
     * @param http HTTP 客户端
     */
    public EvmClient(String chain, String network, String rpc, Support.HttpJson http) {
        this.chain = chain;
        this.network = network;
        this.rpc = rpc;
        this.http = http;
    }

    /**
     * @return 链编号
     */
    public String id() {
        return chain;
    }

    /**
     * 询问节点是否可达。
     *
     * @return 健康结果
     */
    public Health health() {
        long start = System.nanoTime();
        Health h = base();
        try {
            JsonNode raw = call("eth_blockNumber", List.of());
            h.ok = true;
            h.block = hexQuantity(raw);
        } catch (Exception e) {
            h.error = Support.explainBody(e);
        }
        h.latencyMs = (System.nanoTime() - start) / 1_000_000;
        return h;
    }

    /**
     * 查询余额。
     *
     * @param address 地址
     * @param asset 资产
     * @return 人类可读余额
     */
    public String balance(String address, Asset asset) {
        if ("token".equals(asset.kind)) {
            if (asset.contract == null || asset.contract.isEmpty()) return "0";
            JsonNode raw = call("eth_call", List.of(Map.of("to", asset.contract, "data", TxBuild.erc20BalanceOf(address)), "latest"));
            return Money.formatUnits(parseHex(raw), asset.decimals);
        }
        return Money.formatUnits(parseHex(call("eth_getBalance", List.of(address, "latest"))), asset.decimals);
    }

    /**
     * 向节点报价一笔未签名转账。
     *
     * @param from 付款地址
     * @param to 收款地址
     * @param amount 人类可读金额
     * @param units 最小单位
     * @param asset 资产
     * @return 报价
     */
    public PrepareResult prepare(String from, String to, String amount, BigInteger units, Asset asset) {
        List<String> head = new ArrayList<>();
        BigInteger value = BigInteger.ZERO;
        String data = "0x";
        String target = to;
        if ("native".equals(asset.kind)) value = units;
        else {
            if (asset.contract == null || asset.contract.isEmpty())
                throw AppError.of("INVALID_ARGUMENT", "当前网络不能转这个代币", asset.note);
            data = TxBuild.erc20Transfer(to, units);
            target = asset.contract;
            head.add("代币转账的 gas 用原生币支付，目标地址要有对应代币余额。");
        }
        Quoted q = quote(from, target, value, data);
        head.addAll(q.warnings);
        return Chains.result(chain, network, "live", asset.symbol, from, to, amount, q.fee, q.payload, head);
    }

    /**
     * 估算 gas 和 nonce，组装 EIP-1559 载荷。
     *
     * @param from 发送地址
     * @param to 接收地址
     * @param value 转出的 wei，代币转账为 0
     * @param data calldata，原生转账可空
     * @return 载荷、手续费和提示
     */
    public Quoted quote(String from, String to, BigInteger value, String data) {
        if (value == null) value = BigInteger.ZERO;
        Map<String, String> call = new LinkedHashMap<>();
        call.put("from", from);
        call.put("to", to);
        if (value.signum() > 0) call.put("value", Support.quantityHex(value));
        if (data != null && !data.isEmpty() && !"0x".equals(data)) call.put("data", data);
        BigInteger gas = parseHex(call("eth_estimateGas", List.of(call))).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        List<String> warnings = new ArrayList<>();
        if (gas.compareTo(BigInteger.valueOf(2_000_000)) > 0) warnings.add("估算的 gas 很高，先确认没有转到错误的合约。");
        BigInteger gasPrice = parseHex(call("eth_gasPrice", List.of()));
        BigInteger tip = gasPrice.divide(BigInteger.TEN);
        try {
            BigInteger parsed = parseHex(call("eth_maxPriorityFeePerGas", List.of()));
            if (parsed.signum() > 0) tip = parsed;
        } catch (Exception ignored) {
        }
        BigInteger maxFee = gasPrice.multiply(BigInteger.TWO).add(tip);
        if (tip.compareTo(maxFee) > 0) tip = maxFee;
        BigInteger nonce = parseHex(call("eth_getTransactionCount", List.of(from, "pending")));
        BigInteger chainId = parseHex(call("eth_chainId", List.of()));
        warnings.add("报价来自节点。用户交易在浏览器签名，服务器看不到那把私钥。");
        EvmPayload payload = new EvmPayload();
        payload.type = 2;
        payload.chainId = chainId.toString();
        payload.nonce = nonce.toString();
        payload.to = to;
        payload.value = value.toString();
        payload.data = data == null || data.isEmpty() ? "0x" : data;
        payload.gasLimit = gas.toString();
        payload.maxFeePerGas = maxFee.toString();
        payload.maxPriorityFeePerGas = tip.toString();
        Fee fee = Chains.fee(Chains.nativeSymbol(chain), Money.formatUnits(gas.multiply(maxFee), 18), Map.of(
                "gasLimit", gas.toString(), "maxFeePerGas", maxFee.toString(), "maxPriorityFeePerGas", tip.toString()));
        return new Quoted(payload, fee, warnings);
    }

    /**
     * 广播已签名交易。
     *
     * @param signed 已签名内容
     * @return 交易哈希
     */
    public String broadcast(String signed) {
        if (!signed.startsWith("0x")) signed = "0x" + signed;
        String hash = text(call("eth_sendRawTransaction", List.of(signed)));
        if (hash.isEmpty()) throw new RuntimeException("节点没有返回交易哈希");
        return hash;
    }

    /**
     * 查询交易状态。
     *
     * @param hash 交易哈希
     * @return 状态
     */
    public TxState status(String hash) {
        JsonNode raw = call("eth_getTransactionReceipt", List.of(hash));
        if (raw == null || raw.isNull()) {
            JsonNode tx = call("eth_getTransactionByHash", List.of(hash));
            TxState s = new TxState();
            s.hash = hash;
            if (tx != null && !tx.isNull()) {
                s.state = "pending";
                s.detail = "交易已进内存池，还没有回执。";
            } else {
                s.state = "not_found";
                s.detail = "节点还看不到这笔交易。";
            }
            return s;
        }
        String st = raw.path("status").asText("");
        TxState s = new TxState();
        s.hash = hash;
        s.block = Support.hexToInt(raw.path("blockNumber").asText("0")).toString();
        if ("0x0".equals(st)) {
            s.state = "failed";
            s.detail = "回执状态为 0，执行失败。";
        } else {
            s.state = "confirmed";
            s.detail = "回执状态为 1。";
        }
        return s;
    }

    /**
     * @return 可手动执行的探针
     */
    public List<Probe> probes() {
        return List.of(probe("blockNumber", "最新区块", "eth_blockNumber"), probe("chainId", "链 ID", "eth_chainId，用来确认没有连错网"), probe("gasPrice", "Gas 价格", "eth_gasPrice"), probe("balance", "地址余额", "eth_getBalance，需要 address"));
    }

    /**
     * 执行探针。
     *
     * @param name 探针名称
     * @param args 参数，通常含 address
     * @return 探针结果
     */
    public Object runProbe(String name, Map<String, String> args) {
        return switch (name) {
            case "blockNumber" -> call("eth_blockNumber", List.of());
            case "chainId" -> call("eth_chainId", List.of());
            case "gasPrice" -> call("eth_gasPrice", List.of());
            case "balance" -> {
                String address = args.getOrDefault("address", "").trim();
                if (address.isEmpty()) throw AppError.of("INVALID_ARGUMENT", "缺少 address", "填一个 0x 地址再查余额。");
                yield call("eth_getBalance", List.of(address, "latest"));
            }
            default -> throw AppError.of("NOT_FOUND", "没有这个探针", "只开放白名单方法，避免把后端变成任意 RPC 代理。");
        };
    }

    /**
     * 发送一次 JSON-RPC。
     *
     * @param method 方法名
     * @param params 参数
     * @return result 节点
     */
    public JsonNode call(String method, Object params) {
        JsonNode envelope = http.postJson(rpc, Support.rpcBody(method, params));
        if (envelope.has("error") && !envelope.get("error").isNull()) {
            throw new RuntimeException(method + ": " + envelope.path("error").path("message").asText(""));
        }
        return envelope.get("result");
    }

    /**
     * 读取最新块高，作为健康检查。
     *
     * @return 健康结果
     */
    private Health base() {
        Health h = new Health();
        h.chain = chain;
        h.name = Chains.name(chain);
        h.endpoint = Support.hostOf(rpc);
        h.mode = "live";
        return h;
    }

    /**
     * 把十六进制数量解析成整数。
     *
     * @param raw JSON 节点
     * @return 整数，空值时为 0
     */
    public static BigInteger parseHex(JsonNode raw) {
        if (raw == null || raw.isNull()) return BigInteger.ZERO;
        if (raw.isTextual()) return Support.hexToInt(raw.asText());
        return Support.hexToInt(raw.toString().replace("\"", ""));
    }

    /**
     * 读取十六进制数量文本。
     *
     * @param raw JSON 节点
     * @return 文本
     */
    private static String hexQuantity(JsonNode raw) {
        return parseHex(raw).toString();
    }

    /**
     * 读取 JSON 文本。
     *
     * @param raw JSON 节点
     * @return 文本，空节点时为空字符串
     */
    private static String text(JsonNode raw) {
        return raw == null || raw.isNull() ? "" : raw.asText("");
    }

    /**
     * 组装一条探针说明。
     *
     * @param name 探针名称
     * @param title 展示标题
     * @param description 说明
     * @return 探针
     */
    static Probe probe(String name, String title, String description) {
        Probe p = new Probe();
        p.name = name;
        p.title = title;
        p.description = description;
        return p;
    }

    /**
     * EVM 报价结果。
     *
     * @param payload 未签名交易
     * @param fee 手续费
     * @param warnings 提示
     */
    public record Quoted(EvmPayload payload, Fee fee, List<String> warnings) {
    }
}
