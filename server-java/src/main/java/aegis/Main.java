package aegis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 入口。ADDR 仍沿用 Go 的 :8080 写法。
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
     * 把 ADDR 转成 Spring 的 server.port 和 server.address。
     */
    static void applyAddr() {
        String addr = System.getenv("ADDR");
        if (addr == null || addr.isBlank()) addr = ":8080";
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
