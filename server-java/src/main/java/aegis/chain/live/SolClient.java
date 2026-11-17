package aegis.chain.live;

import aegis.chain.Chains;
import aegis.domain.Models.AppError;
import aegis.domain.Models.Asset;
import aegis.domain.Models.Health;
import aegis.domain.Models.PrepareResult;
import aegis.domain.Models.Probe;
import aegis.domain.Models.SolPayload;
import aegis.domain.Models.TxState;
import aegis.domain.Money;
import aegis.platform.Support;
import aegis.txbuild.TxBuild;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Solana 真实节点适配器。转账路径目前只覆盖原生 SOL。
 */
public final class SolClient implements Chains.Adapter {
    private final String network;
    private final String rpc;
    private final Support.HttpJson http;

    /**
     * @param network 网络
     * @param rpc JSON-RPC 地址
     * @param http HTTP 客户端
     */
    public SolClient(String network, String rpc, Support.HttpJson http) {
        this.network = network;
        this.rpc = rpc;
        this.http = http;
    }

    /**
     * @return 链编号
     */
    public String id() { return "solana"; }

    /**
     * 询问节点是否可达。
     *
     * @return 健康结果
     */
    public Health health() {
        long start = System.nanoTime();
        Health h = new Health();
        h.chain = "solana";
        h.name = "Solana";
        h.endpoint = Support.hostOf(rpc);
        h.mode = "live";
        try {
            h.block = call("getSlot", List.of()).asText().replace("\"", "");
            h.ok = true;
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
        if ("native".equals(asset.kind)) {
            JsonNode raw = call("getBalance", List.of(address));
            return Money.formatUnits(BigInteger.valueOf(raw.path("value").asLong()), 9);
        }
        if (asset.contract == null || asset.contract.isEmpty()) return "0";
        JsonNode raw = call("getTokenAccountsByOwner", List.of(address, Map.of("mint", asset.contract), Map.of("encoding", "jsonParsed")));
        BigInteger total = BigInteger.ZERO;
        for (JsonNode row : raw.path("value")) {
            String amt = row.path("account").path("data").path("parsed").path("info").path("tokenAmount").path("amount").asText("0");
            try { total = total.add(new BigInteger(amt)); } catch (Exception ignored) {}
        }
        return Money.formatUnits(total, asset.decimals);
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
        if (!"native".equals(asset.kind)) throw AppError.of("INVALID_ARGUMENT", "Solana 代币转账还没接到签名器", "USDT/USDC 可以查余额。转账教学路径先覆盖 SOL 原生转账，SPL Token 需要关联账户。");
        long lamports = TxBuild.mustLong(units);
        if (lamports == 0) throw AppError.of("INVALID_ARGUMENT", "金额超出范围", "");
        JsonNode raw = call("getLatestBlockhash", List.of(Map.of("commitment", "confirmed")));
        String blockhash = raw.path("value").path("blockhash").asText("");
        if (blockhash.isEmpty()) throw new RuntimeException("节点没有返回 blockhash");
        byte[] msg = TxBuild.buildSolTransfer(from, to, lamports, blockhash);
        SolPayload payload = new SolPayload();
        payload.message = Base64.getEncoder().encodeToString(msg);
        payload.blockhash = blockhash;
        payload.lamports = units.toString();
        payload.from = from;
        payload.to = to;
        return Chains.result("solana", network, "live", "SOL", from, to, amount,
                Chains.fee("SOL", "0.000005", Map.of("lamports", "5000")), payload,
                List.of("blockhash 大约一分钟失效，签完尽快广播。", "向新地址转账时，金额通常还要覆盖租金豁免（约 0.00089 SOL），否则节点会拒绝。"));
    }

    /**
     * 广播已签名交易。
     *
     * @param signed 已签名内容
     * @return 交易哈希
     */
    public String broadcast(String signed) {
        JsonNode raw = call("sendTransaction", List.of(signed, Map.of("encoding", "base64")));
        String sig = raw.asText("");
        if (sig.isEmpty()) throw new RuntimeException("节点没有返回签名");
        return sig;
    }

    /**
     * 查询交易状态。
     *
     * @param hash 交易哈希
     * @return 状态
     */
    public TxState status(String hash) {
        JsonNode raw = call("getSignatureStatuses", List.of(List.of(hash), Map.of("searchTransactionHistory", true)));
        JsonNode value = raw.path("value");
        TxState s = new TxState();
        s.hash = hash;
        if (!value.isArray() || value.size() == 0 || value.get(0) == null || value.get(0).isNull()) {
            s.state = "not_found";
            s.detail = "节点还没有这个签名。blockhash 过期时也会这样。";
            return s;
        }
        JsonNode row = value.get(0);
        if (row.has("err") && !row.get("err").isNull()) {
            s.state = "failed";
            s.detail = row.get("err").toString();
            return s;
        }
        s.state = "confirmed";
        s.block = Long.toString(row.path("slot").asLong());
        s.detail = row.path("confirmationStatus").asText("");
        return s;
    }

    /**
     * @return 可手动执行的探针
     */
    public List<Probe> probes() {
        return List.of(EvmClient.probe("slot", "当前 slot", "getSlot"), EvmClient.probe("blockhash", "最新 blockhash", "getLatestBlockhash"), EvmClient.probe("balance", "地址余额", "getBalance，需要 address"));
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
            case "slot" -> call("getSlot", List.of());
            case "blockhash" -> call("getLatestBlockhash", List.of(Map.of("commitment", "confirmed")));
            case "balance" -> {
                String address = args.getOrDefault("address", "").trim();
                if (address.isEmpty()) throw AppError.of("INVALID_ARGUMENT", "缺少 address", "填一个 Solana 地址。");
                yield call("getBalance", List.of(address));
            }
            default -> throw AppError.of("NOT_FOUND", "没有这个探针", "");
        };
    }

    /**
     * 发送一次 JSON-RPC。
     *
     * @param method 方法名
     * @param params 参数
     * @return result 节点
     */
    private JsonNode call(String method, Object params) {
        JsonNode envelope = http.postJson(rpc, Support.rpcBody(method, params));
        if (envelope.has("error") && !envelope.get("error").isNull()) {
            throw new RuntimeException(method + ": " + envelope.path("error").path("message").asText(""));
        }
        return envelope.get("result");
    }
}
