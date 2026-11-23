package aegis.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConfigDataDirTest {
    /**
     * 从 server-java 启动时，默认库要落到仓库的 server/data。
     */
    @Test
    void sharesServerDataDir() {
        String dir = Config.sharedDataDir();
        String expected = Path.of("server", "data").toString();
        assertTrue(dir.endsWith(expected), dir);
    }
}
