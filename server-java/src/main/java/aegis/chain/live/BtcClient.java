package aegis.chain.live;

import aegis.chain.Chains;
import aegis.domain.Models.AppError;
import aegis.domain.Models.Asset;
import aegis.domain.Models.BtcInput;
import aegis.domain.Models.BtcOutput;
import aegis.domain.Models.BtcPayload;
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
import java.util.List;
import java.util.Map;

/**
 * 比特币真实节点适配器，走 mempool.space 风格的 HTTP API。
 */
public final class BtcClient implements Chains.Adapter {
    private final String network;
    private final String base;
    private final Support.HttpJson http;

    /**
     * @param network 网络
     * @param base API 根地址
     * @param http HTTP 客户端
     */
    public BtcClient(String network, String base, Support.HttpJson http) {
        this.network = network;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.http = http;
    }

    /**
     * @return 链编号
     */
    public String id() { return "bitcoin"; }

    /**
     * 询问节点是否可达。
     *
     * @return 健康结果
     */
    public Health health() {
        long start = System.nanoTime();
        Health h = new Health();
        h.chain = "bitcoin";
        h.name = "Bitcoin";
        h.endpoint = Support.hostOf(base);
        h.mode = "live";
        try {
            h.block = http.getText(base + "/blocks/tip/height");
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
        if (!"native".equals(asset.kind)) throw AppError.of("INVALID_ARGUMENT", "Bitcoin 适配器只查询原生 BTC", "USDT 不在比特币主链的这个接口里。");
        long total = 0;
        for (BtcInput u : utxos(address, false)) total += u.value;
        return Money.formatUnits(BigInteger.valueOf(total), 8);
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
        if (!"native".equals(asset.kind)) throw AppError.of("INVALID_ARGUMENT", "不能在比特币主链上直接转 USDT", "稳定币请换 Ethereum、BNB、TRON 或 Solana。");
        // 2024-06-10: 这里不再用 BigInteger，直接用 long，避免精度问题。
        long sats = TxBuild.mustLong(units);
        if (sats <= 0) throw AppError.of("INVALID_ARGUMENT", "金额超出范围", "");
        // 2024-06-10: 这里只花费已确认的 UTXO，避免刚收到的币被花掉。
        List<BtcInput> confirmed = utxos(from, true);
        if (confirmed.isEmpty()) throw AppError.of("INSUFFICIENT_FUNDS", "没有已确认的 UTXO", "刚收到的比特币要等确认后才能转出。未确认的找零也不能花。");
        long feeRate = 8;
        JsonNode fees = http.getJson(base + "/v1/fees/recommended");
        if (fees.path("halfHourFee").asLong(0) > 0) feeRate = fees.path("halfHourFee").asLong();

        TxBuild.Coins coins = TxBuild.selectCoins(confirmed, sats, feeRate);
        List<BtcOutput> outputs = new ArrayList<>();
        BtcOutput pay = new BtcOutput();
        pay.address = to;
        pay.value = sats;
        outputs.add(pay);
        if (coins.change() > 0) {
            BtcOutput change = new BtcOutput();
            change.address = from;
            change.value = coins.change();
            outputs.add(change);
        }
        BtcPayload payload = new BtcPayload();
        payload.network = network;
        payload.inputs = coins.selected();
        payload.outputs = outputs;
        payload.feeSats = coins.fee();
        return Chains.result("bitcoin", network, "live", "BTC", from, to, amount,
                Chains.fee("BTC", Money.formatUnits(BigInteger.valueOf(coins.fee()), 8), Map.of("feeRateSatVb", Long.toString(feeRate), "inputs", Integer.toString(coins.selected().size()))),
                payload, List.of("手续费按 P2WPKH 体积估算并留了 10% 余量。只花费已确认 UTXO。", "签名在浏览器里完成。已签名交易任何人都可以广播。"));
    }

    /**
     * 广播已签名交易。
     *
     * @param signed 已签名内容
     * @return 交易哈希
     */
    public String broadcast(String signed) {
        signed = signed.trim();
        if (signed.startsWith("0x")) signed = signed.substring(2);
        String txid = http.postText(base + "/tx", signed);
        if (txid.length() != 64) throw new RuntimeException("节点返回的不是交易哈希: " + txid);
        return txid;
    }

    /**
     * 查询交易状态。
     *
     * @param hash 交易哈希
     * @return 状态
     */
    public TxState status(String hash) {
        try {
            JsonNode body = http.getJson(base + "/tx/" + hash + "/status");
            TxState s = new TxState();
            s.hash = hash;
            if (body.path("confirmed").asBoolean(false)) {
                s.state = "confirmed";
                s.block = Long.toString(body.path("block_height").asLong());
            } else {
                s.state = "pending";
                s.detail = "交易已被看到，尚未确认。";
            }
            return s;
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                TxState s = new TxState();
                s.state = "not_found";
                s.hash = hash;
                s.detail = "区块浏览器还没有这笔交易。";
                return s;
            }
            throw e;
        }
    }

    /**
     * @return 可手动执行的探针
     */
    public List<Probe> probes() {
        return List.of(EvmClient.probe("tip", "链尖高度", "GET /blocks/tip/height"), EvmClient.probe("fees", "推荐费率", "GET /v1/fees/recommended"), EvmClient.probe("utxo", "地址 UTXO", "GET /address/{address}/utxo"));
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
            case "tip" -> http.getText(base + "/blocks/tip/height");
            case "fees" -> http.getJson(base + "/v1/fees/recommended");
            case "utxo" -> {
                String address = args.getOrDefault("address", "").trim();
                if (address.isEmpty()) throw AppError.of("INVALID_ARGUMENT", "缺少 address", "填一个 bc1q 或 tb1q 地址。");
                yield utxos(address, false);
            }
            default -> throw AppError.of("NOT_FOUND", "没有这个探针", "比特币不是 JSON-RPC 这条路径，走的是区块浏览器 HTTP API。");
        };
    }

    /**
     * 读取地址的未花费输出。
     *
     * @param address 比特币地址
     * @param confirmedOnly 为真时只要已确认的输出
     * @return 未花费输出
     */
    private List<BtcInput> utxos(String address, boolean confirmedOnly) {
        JsonNode rows = http.getJson(base + "/address/" + address + "/utxo");
        List<BtcInput> out = new ArrayList<>();
        if (rows == null || !rows.isArray()) return out;
        for (JsonNode row : rows) {
            if (confirmedOnly && !row.path("status").path("confirmed").asBoolean(false)) continue;
            BtcInput u = new BtcInput();
            u.txid = row.path("txid").asText();
            u.vout = row.path("vout").asLong();
            u.value = row.path("value").asLong();
            out.add(u);
        }
        return out;
    }
}
