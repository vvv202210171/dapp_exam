package aegis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 入口。端口写在 application.properties 的 server.port。设了 ADDR 时仍按 Go 的 :8080 写法覆盖。
 */
@SpringBootApplication
public class Main {
    /**
     * 启动 Web 服务。
     *
     * @param args 命令行参数，当前不使用
     */
    public static void main(String[] args) {
        applyAddr();
        SpringApplication.run(Main.class, args);
    }

    /**
     * ADDR 有值时转成 Spring 的 server.port 和 server.address。没设则用配置文件里的端口。
     */
    static void applyAddr() {
        String addr = System.getenv("ADDR");
        if (addr == null || addr.isBlank()) return;
        String host = "";
        String port;
        if (addr.startsWith(":")) {
            port = addr.substring(1);
        } else {
            int i = addr.lastIndexOf(':');
            if (i < 0) port = addr;
            else {
                host = addr.substring(0, i);
                port = addr.substring(i + 1);
            }
        }
        System.setProperty("server.port", port);
        if (!host.isEmpty()) System.setProperty("server.address", host);
    }
}
