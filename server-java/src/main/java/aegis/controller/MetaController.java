package aegis.controller;

import aegis.chain.Chains;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查和运行模式说明。
 */
@RestController
public class MetaController {
    private final Chains.Registry chains;

    /**
     * @param chains 当前链注册表，用来读取模式和网络
     */
    public MetaController(Chains.Registry chains) { this.chains = chains; }

    /**
     * 进程是否在听请求。
     *
     * @return status 为 ok
     */
    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /**
     * 返回模式、网络、链清单和给前端的警告。
     *
     * @return 元信息
     */
    @GetMapping("/api/v1/meta")
    public Map<String, Object> meta() {
        List<String> warnings = new ArrayList<>();
        if ("sandbox".equals(chains.mode)) warnings.add("当前是沙箱模式：余额是按地址稳定生成的模拟值，广播不会发到真实网络。签名用的 EVM chainId 是 84532026，不能在主网或测试网重放。");
        else if ("mainnet".equals(chains.network)) warnings.add("当前指向主网。学习时不要使用存有真实资金的助记词。");
        else warnings.add("当前是 live + testnet。节点是公共 RPC，失败时看错误里的 hint。");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", chains.mode);
        out.put("network", chains.network);
        out.put("chains", chains.chains());
        out.put("warnings", warnings);
        out.put("settlement", Map.of("name", "CardSettlement", "note", "本地链上，用户签名存入和提现，运营方账户结算卡消费。卡账本仍按“分”记账。"));
        return out;
    }
}
