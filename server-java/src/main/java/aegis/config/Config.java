package aegis.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 进程配置。值来自环境变量，缺省和 Go 版一致。
 */
public final class Config {
    public final String addr;
    public final String mode;
    public final String network;
    public final Path dbPath;
    public final Path keyPath;
    public final String cardKey;
    public final Map<String, String> rpcs;

    /**
     * @param addr 监听地址，例如 :8080
     * @param mode sandbox 或 live
     * @param network testnet 或 mainnet
     * @param dbPath SQLite 文件
     * @param keyPath 卡密钥文件
     * @param cardKey 环境变量里的密钥十六进制，可空
     * @param rpcs 链到 RPC 地址
     */
    private Config(String addr, String mode, String network, Path dbPath, Path keyPath, String cardKey, Map<String, String> rpcs) {
        this.addr = addr;
        this.mode = mode;
        this.network = network;
        this.dbPath = dbPath;
        this.keyPath = keyPath;
        this.cardKey = cardKey;
        this.rpcs = rpcs;
    }

    /**
     * 从环境变量加载配置。
     *
     * @return 配置
     */
    public static Config load() {
        String mode = env("MODE", "live");
        if (!mode.equals("sandbox") && !mode.equals("live")) throw new IllegalArgumentException("MODE 只能是 sandbox 或 live");
        String network = env("NETWORK", "testnet");
        if (!network.equals("testnet") && !network.equals("mainnet")) throw new IllegalArgumentException("NETWORK 只能是 testnet 或 mainnet");
        String dataDir = env("DATA_DIR", "data");
        Map<String, String> rpcs = defaultRpcs(network);
        override(rpcs, "ETH_RPC", "ethereum");
        override(rpcs, "BNB_RPC", "bnb");
        override(rpcs, "SOL_RPC", "solana");
        override(rpcs, "TRON_RPC", "tron");
        override(rpcs, "BTC_API", "bitcoin");
        return new Config(env("ADDR", ":8080"), mode, network, Path.of(dataDir, "aegis.db"), Path.of(dataDir, "card.key"), System.getenv("CARD_KEY"), rpcs);
    }

    /**
     * 用环境变量覆盖某条链的节点地址。
     *
     * @param rpcs 节点表
     * @param env 环境变量名
     * @param chain 链编号
     */
    private static void override(Map<String, String> rpcs, String env, String chain) {
        String v = System.getenv(env);
        if (v != null && !v.isBlank()) rpcs.put(chain, v.trim());
    }

    /**
     * 读取环境变量，空白时用默认值。
     *
     * @param key 变量名
     * @param fallback 默认值
     * @return 配置值
     */
    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /**
     * 各链的默认公共节点。
     *
     * @param network testnet 或 mainnet
     * @return 链到 URL
     */
    private static Map<String, String> defaultRpcs(String network) {
        Map<String, String> m = new LinkedHashMap<>();
        if ("mainnet".equals(network)) {
            m.put("ethereum", "https://ethereum.publicnode.com");
            m.put("bnb", "https://bsc-dataseed.binance.org");
            m.put("solana", "https://api.mainnet-beta.solana.com");
            m.put("tron", "https://api.trongrid.io");
            m.put("bitcoin", "https://mempool.space/api");
        } else {
            m.put("ethereum", "https://ethereum-sepolia.publicnode.com");
            m.put("bnb", "https://bsc-testnet.publicnode.com");
            m.put("solana", "https://api.devnet.solana.com");
            m.put("tron", "https://api.shasta.trongrid.io");
            m.put("bitcoin", "https://mempool.space/testnet/api");
        }
        return m;
    }
}
