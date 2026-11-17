package aegis.controller;

import aegis.service.SettlementService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地结算合约的查询、用户签名报价、水龙头和运营方结算。
 */
@RestController
public class SettlementController {
    private final SettlementService settlement;

    /**
     * @param settlement 结算业务
     */
    public SettlementController(SettlementService settlement) { this.settlement = settlement; }

    /**
     * 查询合约地址、钱包余额、合约里可用余额，以及未结算消费。
     *
     * @param id 账户编号
     * @return 结算页视图。节点没开时 online 为 false，流水仍返回
     */
    @GetMapping("/api/v1/accounts/{id}/settlement")
    public Object status(@PathVariable String id) {
        return settlement.status(id);
    }

    /**
     * 给用户要签的 approve、deposit 或 withdraw 报价。
     *
     * @param id 账户编号
     * @param body 含 action 和 amount
     * @return 未签名交易和手续费
     */
    @PostMapping("/api/v1/accounts/{id}/settlement/prepare")
    public Object prepare(@PathVariable String id, @RequestBody JsonNode body) {
        return settlement.prepare(id, Jsons.text(body, "action"), Jsons.text(body, "amount"));
    }

    /**
     * 广播用户已签名的结算交易。
     *
     * @param id 账户编号
     * @param body 含 signedPayload
     * @return 交易哈希和状态
     */
    @PostMapping("/api/v1/accounts/{id}/settlement/broadcast")
    public Object broadcast(@PathVariable String id, @RequestBody JsonNode body) {
        return settlement.broadcast(id, Jsons.text(body, "signedPayload"));
    }

    /**
     * 本地练习用：给用户地址打 1 ETH，并铸造 100 MockUSDC。
     *
     * @param id 账户编号
     * @return 两笔交易哈希
     */
    @PostMapping("/api/v1/accounts/{id}/settlement/faucet")
    public Object faucet(@PathVariable String id) {
        return settlement.faucet(id);
    }

    /**
     * 运营方按一笔卡消费，从合约可用余额划出对应 USDC。
     *
     * @param id 账户编号
     * @param body 含 entryId
     * @return 结算交易哈希和状态
     */
    @PostMapping("/api/v1/accounts/{id}/settlement/settle")
    public Object settle(@PathVariable String id, @RequestBody JsonNode body) {
        return settlement.settle(id, Jsons.text(body, "entryId"));
    }
}
