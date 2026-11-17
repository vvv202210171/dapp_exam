package aegis.dao;

import aegis.domain.Models.AppError;
import aegis.domain.Models.LedgerEntry;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 结算记录和消费流水的读写。
 */
@Repository
public class SettlementDao {
    private final Database db;

    /**
     * @param db 共享数据库
     */
    public SettlementDao(Database db) { this.db = db; }

    /**
     * 一笔卡消费，以及它有没有链上结算哈希。
     */
    public static final class SpendRecord {
        public LedgerEntry entry;
        public String accountId;
        public String last4;
        public String txHash;
    }

    /**
     * 列出账户全部消费，新的在前。
     *
     * @param accountId 账户编号
     * @return 消费和结算状态
     */
    public List<SpendRecord> spends(String accountId) {
        return query("""
                SELECT e.id, e.card_id, e.kind, e.amount, e.balance_after, e.merchant, e.mcc, e.ref, e.note, e.created_at,
                       c.account_id, c.last4, IFNULL(st.tx_hash, '')
                FROM ledger_entries e JOIN cards c ON c.id = e.card_id
                LEFT JOIN settlements st ON st.entry_id = e.id
                WHERE c.account_id = ? AND e.kind = 'spend' ORDER BY e.created_at DESC, e.rowid DESC
                """, accountId);
    }

    /**
     * 读取属于该账户的一笔消费。
     *
     * @param accountId 账户编号
     * @param entryId 流水编号
     * @return 消费记录
     */
    public SpendRecord spendForAccount(String accountId, String entryId) {
        List<SpendRecord> rows = query("""
                SELECT e.id, e.card_id, e.kind, e.amount, e.balance_after, e.merchant, e.mcc, e.ref, e.note, e.created_at,
                       c.account_id, c.last4, IFNULL(st.tx_hash, '')
                FROM ledger_entries e JOIN cards c ON c.id = e.card_id
                LEFT JOIN settlements st ON st.entry_id = e.id
                WHERE e.id = ? AND c.account_id = ? AND e.kind = 'spend'
                """, entryId, accountId);
        if (rows.isEmpty()) throw AppError.of("NOT_FOUND", "没有这笔消费", "只能结算当前账户卡片上的消费流水。");
        return rows.get(0);
    }

    /**
     * 记下这笔消费已经结算。同一流水重复写入会被忽略。
     *
     * @param entryId 流水编号
     * @param accountId 账户编号
     * @param txHash 结算交易哈希
     * @param units 划出的 USDC 最小单位
     */
    public void put(String entryId, String accountId, String txHash, String units) {
        db.update("""
                INSERT INTO settlements(entry_id, account_id, tx_hash, units, created_at) VALUES(?,?,?,?,?)
                ON CONFLICT(entry_id) DO NOTHING
                """, entryId, accountId, txHash, units, System.currentTimeMillis());
    }

    /**
     * 执行消费查询。
     *
     * @param sql 查询语句
     * @param args 绑定参数
     * @return 消费记录
     */
    private List<SpendRecord> query(String sql, String... args) {
        return db.query(sql, rs -> {
            SpendRecord rec = new SpendRecord();
            rec.entry = CardDao.scanEntry(rs);
            rec.accountId = rs.getString(11);
            rec.last4 = rs.getString(12);
            rec.txHash = rs.getString(13);
            return rec;
        }, (Object[]) args);
    }
}
