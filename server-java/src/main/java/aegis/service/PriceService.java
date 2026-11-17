package aegis.service;

import aegis.config.Config;
import aegis.domain.Money;
import aegis.platform.Support;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 美元价格。沙箱用固定价，线上每分钟向 CoinGecko 拉一次，失败时退回固定价。
 */
@Service
public class PriceService {
    private final String mode;
    private final Support.HttpJson http;
    private Map<String, String> prices = staticPrices();
    private String source = "static";
    private Instant at = Instant.EPOCH;

    /**
     * @param config 用来判断是不是沙箱
     * @param http HTTP 客户端
     */
    public PriceService(Config config, Support.HttpJson http) {
        this.mode = config.mode;
        this.http = http;
    }

    /**
     * 返回当前价格。一分钟内复用上次结果。
     *
     * @return 符号到美元价格的映射，以及价格来源
     */
    public synchronized Snapshot snapshot() {
        if ("sandbox".equals(mode)) return new Snapshot(staticPrices(), "static");
        if (Duration.between(at, Instant.now()).compareTo(Duration.ofMinutes(1)) < 0 && prices != null) return new Snapshot(new LinkedHashMap<>(prices), source);
        Snapshot next = fetch();
        prices = next.prices;
        source = next.source;
        at = Instant.now();
        return new Snapshot(new LinkedHashMap<>(prices), source);
    }

    /**
     * 向 CoinGecko 拉价。失败时用固定价。
     *
     * @return 价格快照
     */
    private Snapshot fetch() {
        try {
            JsonNode raw = http.getJson("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,binancecoin,solana,tron&vs_currencies=usd");
            Map<String, String> out = staticPrices();
            set(out, raw, "bitcoin", "BTC");
            set(out, raw, "ethereum", "ETH");
            set(out, raw, "binancecoin", "BNB");
            set(out, raw, "solana", "SOL");
            set(out, raw, "tron", "TRX");
            return new Snapshot(out, "coingecko");
        } catch (Exception e) {
            return new Snapshot(staticPrices(), "static-fallback");
        }
    }

    /**
     * 把接口里的美元价格写成两位小数字符串。价格不大于 0 时保留原值。
     *
     * @param out 要更新的价格表
     * @param raw CoinGecko 响应
     * @param id CoinGecko 资产编号
     * @param symbol 本系统里的符号
     */
    private static void set(Map<String, String> out, JsonNode raw, String id, String symbol) {
        double v = raw.path(id).path("usd").asDouble(0);
        if (v > 0) out.put(symbol, Money.formatCents(Math.round(v * 100)));
    }

    /**
     * 一次价格快照。
     *
     * @param prices 符号到美元价格
     * @param source coingecko、static 或 static-fallback
     */
    public record Snapshot(Map<String, String> prices, String source) {}

    /**
     * 教学用固定价格。
     *
     * @return 符号到美元价格
     */
    private static Map<String, String> staticPrices() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("BTC", "64000");
        m.put("ETH", "3200");
        m.put("BNB", "580");
        m.put("SOL", "145");
        m.put("TRX", "0.16");
        m.put("USDT", "1");
        m.put("USDC", "1");
        return m;
    }
}
