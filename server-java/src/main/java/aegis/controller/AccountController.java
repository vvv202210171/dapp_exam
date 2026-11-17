package aegis.controller;

import aegis.domain.Models.Address;
import aegis.platform.Support;
import aegis.service.AccountService;
import aegis.service.AssetService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账户同步、账户查询和资产组合。
 */
@RestController
public class AccountController {
    private final AccountService accounts;
    private final AssetService assets;

    /**
     * @param accounts 账户业务
     * @param assets 资产查询业务
     */
    public AccountController(AccountService accounts, AssetService assets) {
        this.accounts = accounts;
        this.assets = assets;
    }

    /**
     * 用五条链地址登记或更新账户。
     *
     * @param body 含 label 和 addresses
     * @return 账户
     */
    @PostMapping("/api/v1/accounts/sync")
    public Object sync(@RequestBody JsonNode body) {
        List<Address> addresses = Support.JSON.convertValue(body.path("addresses"), Support.JSON.getTypeFactory().constructCollectionType(List.class, Address.class));
        return accounts.sync(Jsons.text(body, "label"), addresses);
    }

    /**
     * 按账户编号查询。
     *
     * @param id 账户编号
     * @return 账户
     */
    @GetMapping("/api/v1/accounts/{id}")
    public Object account(@PathVariable String id) {
        return accounts.get(id);
    }

    /**
     * 查询该账户五条链的余额和美元估值。
     *
     * @param id 账户编号
     * @return 资产组合
     */
    @GetMapping("/api/v1/accounts/{id}/assets")
    public Object assets(@PathVariable String id) {
        return assets.portfolio(id);
    }
}
