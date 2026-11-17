package aegis.service;

import aegis.chain.Chains;
import aegis.config.Config;
import aegis.dao.AccountDao;
import aegis.domain.Models.Account;
import aegis.domain.Models.Address;
import aegis.domain.Models.AppError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 账户同步和查询。只校验地址，不保存助记词。
 */
@Service
public class AccountService {
    private final AccountDao accounts;
    private final String network;

    /**
     * @param accounts 账户数据访问
     * @param config 用来读取当前是主网还是测试网
     */
    public AccountService(AccountDao accounts, Config config) {
        this.accounts = accounts;
        this.network = config.network;
    }

    /**
     * 校验五条链地址后登记或更新账户。
     *
     * @param label 账户名称，空则用「主账户」
     * @param addrs 五条链的地址和派生路径
     * @return 账户
     */
    public Account sync(String label, List<Address> addrs) {
        label = label == null ? "" : label.trim();
        if (label.isEmpty()) label = "主账户";
        if (label.codePointCount(0, label.length()) > 40) throw AppError.of("INVALID_ARGUMENT", "账户名称太长", "最多 40 个字。");
        if (addrs == null || addrs.size() != Chains.ORDER.size()) throw AppError.of("INVALID_ARGUMENT", "需要同时提交五条链的地址", "一套助记词会派生 Bitcoin、Ethereum、BNB、Solana、TRON。");
        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<Address> clean = new ArrayList<>();
        for (Address a : addrs) {
            if (!Chains.valid(a.chain)) throw AppError.of("INVALID_ARGUMENT", "不支持的链", "");
            if (seen.put(a.chain, true) != null) throw AppError.of("INVALID_ARGUMENT", "同一条链提交了两次", "");
            Chains.validate(a.chain, network, a.address);
            String path = a.path == null ? "" : a.path.trim();
            if (!path.startsWith("m/") || path.length() > 80) throw AppError.of("INVALID_ARGUMENT", "派生路径不正确", "路径应以 m/ 开头，例如 m/44'/60'/0'/0/0。");
            Address c = new Address();
            c.chain = a.chain;
            c.address = Chains.normalize(a.chain, a.address);
            c.path = path;
            clean.add(c);
        }
        for (String id : Chains.ORDER) if (!seen.containsKey(id)) throw AppError.of("INVALID_ARGUMENT", "缺少 " + Chains.name(id) + " 地址", "");
        return accounts.sync(label, clean);
    }

    /**
     * 按编号读取账户。
     *
     * @param id 账户编号
     * @return 账户
     */
    public Account get(String id) {
        if (id == null || id.isBlank()) throw AppError.of("INVALID_ARGUMENT", "缺少账户", "");
        return accounts.get(id);
    }

    /**
     * 判断地址是否属于这个账户在该链上的地址。
     *
     * @param acc 账户
     * @param chain 链编号
     * @param address 待比较的地址
     * @return 属于该账户时为 true
     */
    public static boolean owns(Account acc, String chain, String address) {
        for (Address a : acc.addresses) if (a.chain.equals(chain) && Chains.same(chain, a.address, address)) return true;
        return false;
    }
}
