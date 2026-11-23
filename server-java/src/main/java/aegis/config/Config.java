package aegis.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * 进程配置。值写在 application.properties，同名环境变量仍可覆盖。
 */
public final class Config {
    public final String addr;
    public final String mode;
    public final String network;
    public final Path dbPath;
    public final Path keyPath;
    public final String cardKey;
    public final String chainClient;
    public final String settlementFile;
    public final String settlementRpc;
    public final Map<String, String> rpcs;

    /**
     * @param addr 监听地址，例如 :8080
     * @param mode sandbox 或 live
     * @param network testnet 或 mainnet
     * @param dbPath SQLite 文件
     * @param keyPath 卡密钥文件
     * @param cardKey 配置里的密钥十六进制，可空
     * @param chainClient http 或 web3j
     * @param settlementFile 部署文件，可空
     * @param settlementRpc 结算节点，可空
     * @param rpcs 链到 RPC 地址
     */
    private Config(String addr, String mode, String network, Path dbPath, Path keyPath, String cardKey, String chainClient, String settlementFile, String settlementRpc, Map<String, String> rpcs) {
        this.addr = addr;
        this.mode = mode;
        this.network = network;
        this.dbPath = dbPath;
        this.keyPath = keyPath;
        this.cardKey = cardKey;
        this.chainClient = chainClient;
        this.settlementFile = settlementFile;
        this.settlementRpc = settlementRpc;
        this.rpcs = rpcs;
    }

    /**
     * 从 Spring 环境和旧的环境变量加载配置。
     *
     * @param env Spring 环境，能读到 application.properties
     * @return 配置
     */
    public static Config load(Environment env) {
        String mode = pick(env, "aegis.mode", "MODE", "live");
        if (!mode.equals("sandbox") && !mode.equals("live")) throw new IllegalArgumentException("MODE 只能是 sandbox 或 live");
        String network = pick(env, "aegis.network", "NETWORK", "testnet");
        if (!network.equals("testnet") && !network.equals("mainnet")) throw new IllegalArgumentException("NETWORK 只能是 testnet 或 mainnet");
        String dataDir = pick(env, "aegis.data-dir", "DATA_DIR", "");
        if (dataDir.isBlank()) dataDir = sharedDataDir();
        String chainClient = first(env, "CHAIN_CLIENT", "aegis.adapter.settlement", "aegis.chain-client", "http");
        if (!chainClient.equals("http") && !chainClient.equals("web3j")) throw new IllegalArgumentException("结算适配器只能是 http 或 web3j");
        String port = pick(env, "server.port", "", "8080");
        String host = pick(env, "server.address", "", "0.0.0.0");
        String addr = host + ":" + port;
        if (System.getenv("ADDR") != null && !System.getenv("ADDR").isBlank()) addr = System.getenv("ADDR").trim();
        return new Config(addr, mode, network, Path.of(dataDir, "aegis.db"), Path.of(dataDir, "card.key"),
                pick(env, "aegis.card-key", "CARD_KEY", ""), chainClient,
                first(env, "SETTLEMENT_FILE", "aegis.adapter.settlement.file", "aegis.settlement.file", ""),
                first(env, "SETTLEMENT_RPC", "aegis.adapter.settlement.rpc", "aegis.settlement.rpc", "http://127.0.0.1:8545"),
                rpcs(env, network));
    }

    /**
     * 没设数据目录时，和 Go 版共用仓库里的 server/data。
     * 从 server 或 server-java 启动都能找到。
     *
     * @return 数据库和卡密钥所在目录
     */
    static String sharedDataDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("server")) && Files.isDirectory(dir.resolve("server-java"))) {
                return dir.resolve("server").resolve("data").toString();
            }
            dir = dir.getParent();
        }
        return "data";
    }

    /**
     * 按网络读取五条链的节点。单条环境变量仍可覆盖。
     *
     * @param env Spring 环境
     * @param network testnet 或 mainnet
     * @return 链到 URL
     */
    private static Map<String, String> rpcs(Environment env, String network) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ethereum", first(env, "ETH_RPC", "aegis.adapter." + network + ".ethereum", "aegis.rpc." + network + ".ethereum", ""));
        m.put("bnb", first(env, "BNB_RPC", "aegis.adapter." + network + ".bnb", "aegis.rpc." + network + ".bnb", ""));
        m.put("solana", first(env, "SOL_RPC", "aegis.adapter." + network + ".solana", "aegis.rpc." + network + ".solana", ""));
        m.put("tron", first(env, "TRON_RPC", "aegis.adapter." + network + ".tron", "aegis.rpc." + network + ".tron", ""));
        m.put("bitcoin", first(env, "BTC_API", "aegis.adapter." + network + ".bitcoin", "aegis.rpc." + network + ".bitcoin", ""));
        return m;
    }

    /**
     * 环境变量优先，其次是配置文件，都没有就用默认值。
     *
     * @param env Spring 环境
     * @param property application.properties 里的键
     * @param legacy 旧环境变量名，可空
     * @param fallback 默认值
     * @return 配置值
     */
    private static String pick(Environment env, String property, String legacy, String fallback) {
        if (legacy != null && !legacy.isBlank()) {
            String fromEnv = System.getenv(legacy);
            if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.trim();
        }
        String value = env == null ? null : env.getProperty(property);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * 按顺序取第一个非空值。环境变量名全大写，其余是配置键，最后一项是默认值。
     *
     * @param env Spring 环境
     * @param keys 环境变量、配置键和默认值
     * @return 配置值
     */
    private static String first(Environment env, String... keys) {
        String fallback = keys.length == 0 ? "" : keys[keys.length - 1];
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            String value = key.equals(key.toUpperCase()) ? System.getenv(key) : (env == null ? null : env.getProperty(key));
            if (value != null && !value.isBlank()) return value.trim();
        }
        return fallback;
    }
}
