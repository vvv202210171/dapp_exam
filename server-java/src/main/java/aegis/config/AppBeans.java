package aegis.config;

import aegis.chain.Chains;
import aegis.dao.Database;
import aegis.platform.Support;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 启动类。业务对象由注解扫描，这里只组装配置、数据库和链客户端。
 */
@Configuration
public class AppBeans {
    /**
     * @return 从环境变量读出的配置
     */
    @Bean
    Config config() {
        return Config.load();
    }

    /**
     * @param cfg 配置，提供数据库路径
     * @return SQLite 连接
     */
    @Bean
    Database database(Config cfg) {
        return new Database(cfg.dbPath);
    }

    /**
     * @return 访问节点和行情的 HTTP 客户端
     */
    @Bean
    Support.HttpJson httpJson() {
        return new Support.HttpJson();
    }

    /**
     * @param cfg 模式、网络和节点地址
     * @param http HTTP 客户端
     * @return 五条链的适配器
     */
    @Bean
    Chains.Registry chains(Config cfg, Support.HttpJson http) {
        return new Chains.Registry(cfg, http);
    }

    /**
     * 读取或生成卡密钥。
     *
     * @param cfg 密钥路径和可选的 CARD_KEY
     * @return 32 字节密钥
     */
    @Bean
    CardKey cardKey(Config cfg) {
        byte[] key = loadKey(cfg);
        if (System.getenv("CARD_KEY") == null || System.getenv("CARD_KEY").isBlank()) {
            System.out.println("card encryption key is a local file; production would use a KMS path=" + cfg.keyPath);
        }
        return new CardKey(key);
    }

    /**
     * 启动后打印监听地址。
     *
     * @param cfg 配置
     * @return 启动回调
     */
    @Bean
    ApplicationRunner started(Config cfg) {
        return args -> System.out.printf("aegis-java listening addr=%s mode=%s network=%s%n", cfg.addr, cfg.mode, cfg.network);
    }

    /**
     * 从环境变量或文件加载密钥，都没有就生成并写入文件。
     *
     * @param cfg 配置
     * @return 32 字节密钥
     */
    private static byte[] loadKey(Config cfg) {
        try {
            String hex = cfg.cardKey == null ? "" : cfg.cardKey.trim();
            if (!hex.isEmpty()) {
                byte[] b = Support.unhex(hex);
                if (b.length != 32) throw new IllegalArgumentException("CARD_KEY 必须是 64 位十六进制（32 字节）");
                return b;
            }
            if (Files.exists(cfg.keyPath)) {
                byte[] raw = Files.readAllBytes(cfg.keyPath);
                String text = new String(raw).trim();
                if (raw.length == 32 && !text.matches("[0-9a-fA-F]+")) return Arrays.copyOf(raw, 32);
                try {
                    byte[] decoded = Support.unhex(text);
                    if (decoded.length == 32) return decoded;
                } catch (Exception ignored) {}
            }
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            if (cfg.keyPath.getParent() != null) Files.createDirectories(cfg.keyPath.getParent());
            Files.writeString(cfg.keyPath, Support.hex(key));
            return key;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
