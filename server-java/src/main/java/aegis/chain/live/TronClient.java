package aegis.chain.live;

import aegis.chain.Chains;
import aegis.domain.Models.AppError;
import aegis.domain.Models.Asset;
import aegis.domain.Models.Health;
import aegis.domain.Models.PrepareResult;
import aegis.domain.Models.Probe;
import aegis.domain.Models.TronPayload;
import aegis.domain.Models.TxState;
import aegis.domain.Money;
import aegis.platform.Support;
import aegis.txbuild.TxBuild;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TRON 真实节点适配器，走钱包 HTTP 接口。
 */
public final class TronClient implements Chains.Adapter {
    private final String network;
    private final String base;
    private final Support.HttpJson http;

    /**
     * @param network 网络
     * @param base 节点根地址
     * @param http HTTP 客户端
     */
    public TronClient(String network, String base, Support.HttpJson http) {
        this.network = network;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.http = http;
    }

    /**
     * @return 链编号
     */
    public String id() { return "tron"; }

    /**
     * 询问节点是否可达。
     *
     * @return 健康结果
     */
    public Health health() {
        long start = System.nanoTime();
        Health h = new Health();
        h.chain = "tron";
        h.name = "TRON";
        h.endpoint = Support.hostOf(base);
        h.mode = "live";
        try {
            JsonNode block = http.postJson(base + "/wallet/getnowblock", Map.of());
            long number = block.path("block_header").path("raw_data").path("number").asLong(0);
            if (number == 0) h.error = AppError.of("CHAIN_ERROR", "节点没有返回区块", "确认 TRON API 地址，主网和 Shasta 不能混。").body();
            else {
                h.ok = true;
                h.block = Long.toString(number);
            }
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
            JsonNode account = http.postJson(base + "/wallet/getaccount", Map.of("address", address, "visible", true));
            return Money.formatUnits(BigInteger.valueOf(account.path("balance").asLong(0)), 6);
        }
        if (asset.contract == null || asset.contract.isEmpty()) return "0";
        String param = TxBuild.trc20Parameter(address, BigInteger.ZERO);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("owner_address", address);
        body.put("contract_address", asset.contract);
        body.put("function_selector", "balanceOf(address)");
        body.put("parameter", param.substring(0, 64));
        body.put("visible", true);
        JsonNode out = http.postJson(base + "/wallet/triggerconstantcontract", body);
        if (!out.path("constant_result").isArray() || out.path("constant_result").isEmpty()) return "0";
        return Money.formatUnits(Support.hexToInt(out.path("constant_result").get(0).asText("0")), asset.decimals);
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
        if ("native".equals(asset.kind)) {
            long sun = TxBuild.mustLong(units);
            if (sun <= 0) throw AppError.of("INVALID_ARGUMENT", "金额超出范围", "");
            JsonNode tx = http.postJson(base + "/wallet/createtransaction", Map.of("owner_address", from, "to_address", to, "amount", sun, "visible", true));
            String failed = failed(tx);
            if (failed != null) throw new RuntimeException(failed);
            return finish("TRX", "0", from, to, amount, tx, Map.of("resource", "bandwidth"), List.of("TRX 转账优先消耗带宽。带宽不够时才会燃烧少量 TRX。", "付款地址如果从未收过 TRX，需要先激活。"));
        }
        if (asset.contract == null || asset.contract.isEmpty()) throw AppError.of("INVALID_ARGUMENT", "当前网络不能转这个代币", asset.note);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("owner_address", from);
        body.put("contract_address", asset.contract);
        body.put("function_selector", "transfer(address,uint256)");
        body.put("parameter", TxBuild.trc20Parameter(to, units));
        body.put("fee_limit", 100_000_000);
        body.put("call_value", 0);
        body.put("visible", true);
        JsonNode wrapper = http.postJson(base + "/wallet/triggersmartcontract", body);
        if (!wrapper.path("result").path("result").asBoolean(false)) {
            throw new RuntimeException(decodeMaybeHex(wrapper.path("result").path("message").asText("")));
        }
        return finish(asset.symbol, "15", from, to, amount, wrapper.path("transaction"), Map.of("feeLimitSun", "100000000"),
                List.of("TRC20 手续费取决于能量。15 TRX 是常见估算，不是链上报价。能量不足时会燃烧 TRX，上限是 fee_limit。"));
    }

    /**
     * 把节点返回的未签名交易收成报价。
     *
     * @param asset 资产符号
     * @param fee 手续费文本
     * @param from 付款地址
     * @param to 收款地址
     * @param amount 金额
     * @param tx 节点返回的交易
     * @param detail 手续费明细
     * @param warnings 提示
     * @return 报价
     */
    private PrepareResult finish(String asset, String fee, String from, String to, String amount, JsonNode tx, Map<String, String> detail, List<String> warnings) {
        String txId = tx.path("txID").asText("");
        if (txId.isEmpty()) throw new RuntimeException("节点没有返回 txID");
        List<String> all = new ArrayList<>(warnings);
        all.add("浏览器对 txID 做 secp256k1 签名，再把 signature 填回交易。节点负责验签。");
        TronPayload payload = new TronPayload();
        payload.txID = txId;
        payload.transaction = Support.JSON.convertValue(tx, Object.class);
        return Chains.result("tron", network, "live", asset, from, to, amount, Chains.fee("TRX", fee, detail), payload, all);
    }

    /**
     * 广播已签名交易。
     *
     * @param signed 已签名内容
     * @return 交易哈希
     */
    public String broadcast(String signed) {
        Map<String, Object> tx;
        try {
            tx = Support.JSON.readValue(signed, new TypeReference<>() {});
        } catch (Exception e) {
            throw AppError.of("INVALID_ARGUMENT", "TRON 签名结果必须是交易 JSON", "应包含 txID 和 signature。");
        }
        JsonNode out = http.postJson(base + "/wallet/broadcasttransaction", tx);
        if (!out.path("result").asBoolean(false)) {
            String msg = decodeMaybeHex(out.path("message").asText(""));
            if (msg.isEmpty()) msg = out.path("code").asText("");
            throw new RuntimeException(msg);
        }
        String id = out.path("txid").asText("");
        if (id.isEmpty() && tx.get("txID") instanceof String s) return s;
        return id;
    }

    /**
     * 查询交易状态。
     *
     * @param hash 交易哈希
     * @return 状态
     */
    public TxState status(String hash) {
        JsonNode info = http.postJson(base + "/wallet/gettransactioninfobyid", Map.of("value", hash));
        TxState s = new TxState();
        s.hash = hash;
        if (info.path("id").asText("").isEmpty() && info.path("blockNumber").asLong(0) == 0) {
            s.state = "not_found";
            s.detail = "节点还没有这笔交易的回执。";
            return s;
        }
        String result = (info.path("result").asText("") + info.path("receipt").path("result").asText("")).toUpperCase();
        if (result.contains("FAIL") || result.contains("REVERT")) {
            s.state = "failed";
            s.block = Long.toString(info.path("blockNumber").asLong());
            s.detail = "链上执行失败。";
            return s;
        }
        if (info.path("blockNumber").asLong(0) > 0) {
            s.state = "confirmed";
            s.block = Long.toString(info.path("blockNumber").asLong());
            return s;
        }
        s.state = "pending";
        return s;
    }

    /**
     * @return 可手动执行的探针
     */
    public List<Probe> probes() {
        return List.of(EvmClient.probe("block", "最新区块", "wallet/getnowblock"), EvmClient.probe("account", "账户", "wallet/getaccount，需要 address"));
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
            case "block" -> http.postJson(base + "/wallet/getnowblock", Map.of());
            case "account" -> {
                String address = args.getOrDefault("address", "").trim();
                if (address.isEmpty()) throw AppError.of("INVALID_ARGUMENT", "缺少 address", "填一个 T 开头的地址。");
                yield http.postJson(base + "/wallet/getaccount", Map.of("address", address, "visible", true));
            }
            default -> throw AppError.of("NOT_FOUND", "没有这个探针", "");
        };
    }

    /**
     * 从交易里取出失败原因。
     *
     * @param tx 交易 JSON
     * @return 失败说明，没有则为空
     */
    private static String failed(JsonNode tx) {
        if (tx == null || tx.isNull()) return "空响应";
        if (!tx.path("Error").asText("").isEmpty()) return tx.path("Error").asText();
        if (tx.path("txID").asText("").isEmpty()) return "节点没有返回可签名交易";
        return null;
    }

    /**
     * 节点有时把错误信息做成十六进制，能解就解成文本。
     *
     * @param s 原文或十六进制
     * @return 文本
     */
    private static String decodeMaybeHex(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.isEmpty()) return "";
        try {
            byte[] b = Support.unhex(s);
            String text = new String(b);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 32 || c > 126) return s;
            }
            return text.isEmpty() ? s : text;
        } catch (Exception e) {
            return s;
        }
    }
}
