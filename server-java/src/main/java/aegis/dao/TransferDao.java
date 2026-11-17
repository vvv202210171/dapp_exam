package aegis.dao;

import aegis.domain.Models.AppError;
import aegis.domain.Models.Transfer;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 转账记录表的读写。
 */
@Repository
public class TransferDao {
    private static final String TRANSFER = "SELECT id, account_id, chain, asset, from_addr, to_addr, amount, fee, tx_hash, signed_payload, status, detail, created_at, updated_at FROM transfers";
    private final Database db;

    /**
     * @param db 共享数据库
     */
    public TransferDao(Database db) { this.db = db; }

    /**
     * 插入一条转账记录。
     *
     * @param t 转账记录
     */
    public void insert(Transfer t) {
        db.update("""
                INSERT INTO transfers(id, account_id, chain, asset, from_addr, to_addr, amount, fee, tx_hash, signed_payload, status, detail, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, t.id, t.accountId, t.chain, t.asset, t.from, t.to, t.amount, nz(t.fee), nz(t.txHash), nz(t.signedPayload), t.status, nz(t.detail), millis(t.createdAt), millis(t.updatedAt));
    }

    /**
     * 更新哈希、状态和说明。
     *
     * @param t 已修改的转账记录
     */
    public void update(Transfer t) {
        db.update("UPDATE transfers SET tx_hash = ?, status = ?, detail = ?, updated_at = ? WHERE id = ?", nz(t.txHash), t.status, nz(t.detail), millis(t.updatedAt), t.id);
    }

    /**
     * 按记录编号读取。
     *
     * @param id 转账记录编号
     * @return 转账记录
     */
    public Transfer get(String id) {
        List<Transfer> rows = db.query(TRANSFER + " WHERE id = ?", TransferDao::scan, id);
        if (rows.isEmpty()) throw AppError.of("NOT_FOUND", "交易不存在", "");
        return rows.get(0);
    }

    /**
     * 列出账户最近 100 笔转账，新的在前。
     *
     * @param accountId 账户编号
     * @return 转账列表
     */
    public List<Transfer> list(String accountId) {
        return db.query(TRANSFER + " WHERE account_id = ? ORDER BY created_at DESC LIMIT 100", TransferDao::scan, accountId);
    }

    /**
     * 把转账查询的一行映射成对象。
     *
     * @param rs 当前行
     * @return 转账记录
     */
    private static Transfer scan(java.sql.ResultSet rs) throws java.sql.SQLException {
        Transfer t = new Transfer();
        t.id = rs.getString(1);
        t.accountId = rs.getString(2);
        t.chain = rs.getString(3);
        t.asset = rs.getString(4);
        t.from = rs.getString(5);
        t.to = rs.getString(6);
        t.amount = rs.getString(7);
        t.fee = rs.getString(8);
        t.txHash = rs.getString(9);
        t.signedPayload = rs.getString(10);
        t.status = rs.getString(11);
        t.detail = rs.getString(12);
        t.createdAt = Instant.ofEpochMilli(rs.getLong(13));
        t.updatedAt = Instant.ofEpochMilli(rs.getLong(14));
        return t;
    }

    /**
     * 空字符串代替 null，避免写入数据库时空值。
     *
     * @param s 原文本
     * @return 非 null 文本
     */
    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * 时间转毫秒。为空时用当前时间。
     *
     * @param t 时间
     * @return 毫秒时间戳
     */
    private static long millis(Instant t) { return t == null ? System.currentTimeMillis() : t.toEpochMilli(); }
}
