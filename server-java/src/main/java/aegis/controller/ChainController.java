package aegis.controller;

import aegis.chain.Chains;
import aegis.platform.Support;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链健康检查和学习用探针。
 */
@RestController
public class ChainController {
    private final Chains.Registry chains;

    /**
     * @param chains 链注册表
     */
    public ChainController(Chains.Registry chains) { this.chains = chains; }

    /**
     * 检查五条链当前是否可达。
     *
     * @return 每条链的健康结果
     */
    @GetMapping("/api/v1/chains/health")
    public Map<String, Object> health() {
        return Map.of("chains", chains.health());
    }

    /**
     * 列出这条链可以手动点的探针。
     *
     * @param chain 链编号，如 ethereum、bitcoin
     * @return 探针清单
     */
    @GetMapping("/api/v1/chains/{chain}/probes")
    public Map<String, Object> probes(@PathVariable String chain) {
        return Map.of("probes", chains.get(chain).probes());
    }

    /**
     * 执行一条探针。请求体解析失败时按空地址继续，方便无参数探针。
     *
     * @param chain 链编号
     * @param name 探针名称
     * @param request 原始请求，body 里可选 address
     * @return 探针结果
     */
    @PostMapping("/api/v1/chains/{chain}/probes/{name}")
    public Map<String, Object> run(@PathVariable String chain, @PathVariable String name, HttpServletRequest request) {
        String address = "";
        try {
            byte[] body = request.getInputStream().readNBytes(1 << 20);
            if (body.length > 0) address = Jsons.text(Support.JSON.readTree(body), "address");
        } catch (Exception ignored) {}
        return Map.of("result", chains.get(chain).runProbe(name, Map.of("address", address)));
    }
}
