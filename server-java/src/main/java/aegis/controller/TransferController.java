package aegis.controller;

import aegis.platform.Support;
import aegis.service.TransferService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 转账报价、提交已签名交易和状态刷新。
 */
@RestController
public class TransferController {
    private final TransferService transfers;

    /**
     * @param transfers 转账业务
     */
    public TransferController(TransferService transfers) { this.transfers = transfers; }

    /**
     * 列出账户最近的转账记录。
     *
     * @param id 账户编号
     * @return transfers 数组
     */
    @GetMapping("/api/v1/accounts/{id}/transfers")
    public Map<String, Object> list(@PathVariable String id) {
        return Map.of("transfers", transfers.list(id));
    }

    /**
     * 报价一笔未签名转账。服务器不接触私钥。
     *
     * @param body 含 accountId、chain、asset、from、to、amount
     * @return 未签名载荷和手续费
     */
    @PostMapping("/api/v1/transfers/prepare")
    public Object prepare(@RequestBody JsonNode body) {
        return transfers.prepare(Jsons.text(body, "accountId"), Jsons.text(body, "chain"), Jsons.text(body, "asset"), Jsons.text(body, "from"), Jsons.text(body, "to"), Jsons.text(body, "amount"));
    }

    /**
     * 保存浏览器签好的交易。broadcast 为真时立刻广播。
     *
     * @param body 已签名交易
     * @return 转账记录，HTTP 201
     */
    @PostMapping("/api/v1/transfers")
    public ResponseEntity<Object> submit(@RequestBody JsonNode body) {
        return ResponseEntity.status(201).body(transfers.submit(Support.JSON.convertValue(body, TransferService.Submit.class)));
    }

    /**
     * 查询一笔转账记录。
     *
     * @param id 转账记录编号
     * @return 转账记录
     */
    @GetMapping("/api/v1/transfers/{id}")
    public Object get(@PathVariable String id) {
        return transfers.get(id);
    }

    /**
     * 广播一笔还停在已签名状态的交易。
     *
     * @param id 转账记录编号
     * @return 更新后的记录
     */
    @PostMapping("/api/v1/transfers/{id}/broadcast")
    public Object broadcast(@PathVariable String id) {
        return transfers.broadcast(id);
    }

    /**
     * 向节点查询这笔交易的最新状态。
     *
     * @param id 转账记录编号
     * @return 更新后的记录
     */
    @PostMapping("/api/v1/transfers/{id}/refresh")
    public Object refresh(@PathVariable String id) {
        return transfers.refresh(id);
    }
}
