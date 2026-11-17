package aegis.dao;

import aegis.domain.Models.Account;
import aegis.domain.Models.Address;
import aegis.domain.Models.AppError;
import aegis.platform.Support;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 账户和地址表的读写。
 */
@Repository
public class AccountDao {
    private final Database db;

    /**
     * @param db 共享数据库
     */
    public AccountDao(Database db) { this.db = db; }

    /**
     * 按地址找到已有账户并更新名称，否则新建账户，然后写入五条链地址。
     *
     * @param label 账户名称
     * @param addrs 已规范化的地址
     * @return 同步后的账户
     */
    public Account sync(String label, List<Address> addrs) {
        String accountId = db.tx(() -> {
            Map<String, Boolean> found = new LinkedHashMap<>();
            for (Address a : addrs) {
                for (String id : db.query("SELECT account_id FROM addresses WHERE chain = ? AND address = ?", rs -> rs.getString(1), a.chain, a.address)) {
                    found.put(id, true);
                }
            }
            if (found.size() > 1) throw AppError.of("CONFLICT", "这些地址属于不同账户", "一套助记词对应一个账户。请不要把两套钱包的地址混在一起同步。");
            String id;
            long now = System.currentTimeMillis();
            if (found.size() == 1) {
                id = found.keySet().iterator().next();
                db.update("UPDATE accounts SET label = ? WHERE id = ?", label, id);
            } else {
                id = Support.newId();
                db.update("INSERT INTO accounts(id, label, created_at) VALUES(?,?,?)", id, label, now);
            }
            for (Address a : addrs) {
                try {
                    db.update("""
                            INSERT INTO addresses(account_id, chain, address, derivation_path) VALUES(?,?,?,?)
                            ON CONFLICT(account_id, chain) DO UPDATE SET address = excluded.address, derivation_path = excluded.derivation_path
                            """, id, a.chain, a.address, a.path);
                } catch (RuntimeException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                    if (msg.contains("unique")) throw AppError.of("CONFLICT", "地址已经登记在别的账户上", "换一套助记词会得到另一组地址。");
                    throw e;
                }
            }
            return id;
        });
        return get(accountId);
    }

    /**
     * 按编号读取账户和它的地址。
     *
     * @param id 账户编号
     * @return 账户
     */
    public Account get(String id) {
        List<Account> rows = db.query("SELECT label, created_at FROM accounts WHERE id = ?", rs -> {
            Account acc = new Account();
            acc.id = id;
            acc.label = rs.getString(1);
            acc.createdAt = Instant.ofEpochMilli(rs.getLong(2));
            return acc;
        }, id);
        if (rows.isEmpty()) throw AppError.of("NOT_FOUND", "账户不存在", "重新解锁钱包，让浏览器再同步一次地址。");
        Account acc = rows.get(0);
        acc.addresses.addAll(db.query("SELECT chain, address, derivation_path FROM addresses WHERE account_id = ?", rs -> {
            Address a = new Address();
            a.chain = rs.getString(1);
            a.address = rs.getString(2);
            a.path = rs.getString(3);
            return a;
        }, id));
        acc.addresses.sort(Comparator.comparingInt(a -> aegis.chain.Chains.rank(a.chain)));
        return acc;
    }
}
