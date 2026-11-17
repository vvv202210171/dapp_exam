package aegis.controller;

import aegis.platform.Support;
import aegis.service.CardService;
import aegis.service.SettlementService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开卡、入账、消费、提现、状态流转和月账单。
 */
@RestController
public class CardController {
    private final CardService cards;
    private final SettlementService settlement;

    /**
     * @param cards 卡账本业务
     * @param settlement 链上结算业务，充值入账前要先核对存入交易
     */
    public CardController(CardService cards, SettlementService settlement) {
        this.cards = cards;
        this.settlement = settlement;
    }

    /**
     * 开一张虚拟卡或实体卡，并返回一次明文卡号。
     *
     * @param id 账户编号
     * @param body 含 kind 和 holder
     * @return 卡、卡号和 CVV，HTTP 201
     */
    @PostMapping("/api/v1/accounts/{id}/cards")
    public ResponseEntity<Map<String, Object>> create(@PathVariable String id, @RequestBody JsonNode body) {
        CardService.Issued issued = cards.create(id, Jsons.text(body, "kind"), Jsons.text(body, "holder"));
        return ResponseEntity.status(201).body(Map.of("card", issued.card(), "pan", issued.pan(), "cvv", issued.cvv()));
    }

    /**
     * 列出账户下的卡。不返回完整卡号。
     *
     * @param id 账户编号
     * @return 卡列表
     */
    @GetMapping("/api/v1/accounts/{id}/cards")
    public Map<String, Object> list(@PathVariable String id) {
        return Map.of("cards", cards.list(id));
    }

    /**
     * 查询一张卡的状态和余额。
     *
     * @param id 卡编号
     * @return 卡
     */
    @GetMapping("/api/v1/cards/{id}")
    public Object get(@PathVariable String id) {
        return cards.get(id);
    }

    /**
     * 解密并返回完整卡号和 CVV。
     *
     * @param id 卡编号
     * @return 卡号、CVV 和有效期
     */
    @PostMapping("/api/v1/cards/{id}/reveal")
    public Map<String, String> reveal(@PathVariable String id) {
        CardService.Secret secret = cards.reveal(id);
        return Map.of("pan", secret.pan(), "cvv", secret.cvv(), "expiry", secret.expiry());
    }

    /**
     * 核对已确认的 USDC 存入后，才给卡加上同样金额。
     *
     * @param id 卡编号
     * @param body 含 amount 和 txHash
     * @return 入账结果
     */
    @PostMapping("/api/v1/cards/{id}/topup")
    public Object topup(@PathVariable String id, @RequestBody JsonNode body) {
        var card = cards.get(id);
        settlement.confirmDeposit(card.accountId, Jsons.text(body, "amount"), Jsons.text(body, "txHash"));
        CardService.Move in = new CardService.Move();
        in.amount = Jsons.text(body, "amount");
        in.note = "USDC 已存入结算合约 " + Jsons.text(body, "txHash").trim();
        in.idempotencyKey = Jsons.text(body, "txHash");
        return cards.creditDeposit(id, in);
    }

    /**
     * 按卡账本记一笔消费。只扣卡余额，不直接动合约。
     *
     * @param id 卡编号
     * @param body 含 amount、merchant、mcc、note
     * @param key 请求头 Idempotency-Key，正文没带幂等键时使用
     * @return 动账结果
     */
    @PostMapping("/api/v1/cards/{id}/spend")
    public Object spend(@PathVariable String id, @RequestBody JsonNode body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return cards.spend(id, move(body, key));
    }

    /**
     * 从卡账本提现，另记 1% 手续费。这一步不从合约转出 USDC。
     *
     * @param id 卡编号
     * @param body 含 amount
     * @param key 请求头 Idempotency-Key
     * @return 动账结果
     */
    @PostMapping("/api/v1/cards/{id}/withdraw")
    public Object withdraw(@PathVariable String id, @RequestBody JsonNode body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return cards.withdraw(id, move(body, key));
    }

    /**
     * 改变卡状态，例如激活、冻结、注销。
     *
     * @param id 卡编号
     * @param body 含 action
     * @return 更新后的卡
     */
    @PostMapping("/api/v1/cards/{id}/transition")
    public Object transition(@PathVariable String id, @RequestBody JsonNode body) {
        return cards.transition(id, Jsons.text(body, "action"));
    }

    /**
     * 按月汇总这张卡的流水。
     *
     * @param id 卡编号
     * @param month 月份，格式 YYYY-MM，空则用当前 UTC 月
     * @return 月账单
     */
    @GetMapping("/api/v1/cards/{id}/bills")
    public Object bill(@PathVariable String id, @RequestParam(required = false) String month) {
        return cards.bill(id, month);
    }

    /**
     * 把请求体转成动账参数，并补上幂等键。
     *
     * @param body 请求体
     * @param header 请求头里的幂等键，可以为空
     * @return 动账参数
     */
    private static CardService.Move move(JsonNode body, String header) {
        CardService.Move in = Support.JSON.convertValue(body, CardService.Move.class);
        if (in.idempotencyKey == null || in.idempotencyKey.isBlank()) in.idempotencyKey = header;
        return in;
    }
}
