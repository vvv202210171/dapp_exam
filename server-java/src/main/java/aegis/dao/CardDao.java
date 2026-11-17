package aegis.dao;

import aegis.domain.Models.AppError;
import aegis.domain.Models.Card;
import aegis.domain.Models.Cents;
import aegis.domain.Models.LedgerEntry;
import aegis.domain.Models.MoveResult;
import aegis.platform.Support;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 卡、卡密文和账本流水的读写。动账在一个事务里完成。
 */
@Repository
public class CardDao {
    static final String CARD = "SELECT id, account_id, kind, status, last4, expiry, holder, currency, available, created_at FROM cards";
    static final String ENTRY = "SELECT id, card_id, kind, amount, balance_after, merchant, mcc, ref, note, created_at FROM ledger_entries";
    private final Database db;

    /**
     * @param db 共享数据库
     */
    public CardDao(Database db) { this.db = db; }

    /**
     * 新建卡时要写入的字段。卡号和 CVV 已经加密。
     *
     * @param accountId 账户编号
     * @param kind virtual 或 physical
     * @param status 初始状态
     * @param last4 卡号后四位
     * @param panEnc 加密后的卡号
     * @param cvvEnc 加密后的 CVV
     * @param expiry 有效期，MM/yy
     * @param holder 持卡人
     */
    public record NewCard(String accountId, String kind, String status, String last4, String panEnc, String cvvEnc, String expiry, String holder) {}

    /**
     * 一笔记账草稿。金额单位是分。
     *
     * @param kind topup、spend、withdraw 或 fee
     * @param amount 金额，单位分
     * @param merchant 商户，消费以外可空
     * @param mcc 商户类别码，可空
     * @param note 备注
     * @param ref 幂等键
     */
    public record Draft(String kind, long amount, String merchant, String mcc, String note, String ref) {}

    /**
     * 动账前检查卡状态。不通过时抛出业务异常。
     */
    public interface Policy {
        /**
         * @param kind 卡类型
         * @param status 当前状态
         */
        void check(String kind, String status);
    }

    /**
     * 插入一张余额为 0 的卡。
     *
     * @param in 建卡字段
     * @return 新卡
     */
    public Card insert(NewCard in) {
        Card card = new Card();
        card.id = Support.newId();
        card.accountId = in.accountId;
        card.kind = in.kind;
        card.status = in.status;
        card.last4 = in.last4;
        card.expiry = in.expiry;
        card.holder = in.holder;
        card.currency = "USD";
        card.available = new Cents(0);
        card.createdAt = Instant.now();
        db.update("""
                INSERT INTO cards(id, account_id, kind, status, last4, pan_enc, cvv_enc, expiry, holder, currency, available, created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, card.id, card.accountId, card.kind, card.status, card.last4, in.panEnc, in.cvvEnc, card.expiry, card.holder, card.currency, 0, card.createdAt.toEpochMilli());
        return card;
    }

    /**
     * 按卡编号读取，不含卡号明文。
     *
     * @param id 卡编号
     * @return 卡
     */
    public Card get(String id) {
        List<Card> rows = db.query(CARD + " WHERE id = ?", CardDao::scanCard, id);
        if (rows.isEmpty()) throw AppError.of("NOT_FOUND", "卡不存在", "");
        return rows.get(0);
    }

    /**
     * 读取加密后的卡号和 CVV。
     *
     * @param id 卡编号
     * @return 下标 0 是卡号密文，下标 1 是 CVV 密文
     */
    public String[] secrets(String id) {
        get(id);
        List<String[]> rows = db.query("SELECT pan_enc, cvv_enc FROM cards WHERE id = ?", rs -> new String[] {rs.getString(1), rs.getString(2)}, id);
        if (rows.isEmpty()) throw AppError.of("NOT_FOUND", "卡不存在", "");
        return rows.get(0);
    }

    /**
     * 列出账户的卡，新的在前。
     *
     * @param accountId 账户编号
     * @return 卡列表
     */
    public List<Card> list(String accountId) {
        return db.query(CARD + " WHERE account_id = ? ORDER BY created_at DESC", CardDao::scanCard, accountId);
    }

    /**
     * 按时间顺序列出这张卡的流水。
     *
     * @param cardId 卡编号
     * @return 流水
     */
    public List<LedgerEntry> entries(String cardId) {
        get(cardId);
        return db.query(ENTRY + " WHERE card_id = ? ORDER BY created_at ASC, rowid ASC", CardDao::scanEntry, cardId);
    }

    /**
     * 查找哪张卡已经用这个幂等键入过账。
     *
     * @param ref 去掉 0x 的交易哈希
     * @return 卡编号，没有则空字符串
     */
    public String topupCardByRef(String ref) {
        List<String> rows = db.query("SELECT card_id FROM ledger_entries WHERE ref = ? AND kind = 'topup' LIMIT 1", rs -> rs.getString(1), ref);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    /**
     * 更新卡状态。
     *
     * @param cardId 卡编号
     * @param status 新状态
     */
    public void updateStatus(String cardId, String status) {
        db.update("UPDATE cards SET status = ? WHERE id = ?", status, cardId);
    }

    /**
     * 在一个事务里记账。同一幂等键再次提交时返回原来的流水，不再改余额。
     *
     * @param cardId 卡编号
     * @param drafts 要写入的流水，第一笔的 ref 是幂等键
     * @param policy 写入前的状态检查
     * @return 卡和本次流水。重放时 replayed 为 true
     */
    public MoveResult move(String cardId, List<Draft> drafts, Policy policy) {
        if (drafts.isEmpty() || drafts.get(0).ref == null || drafts.get(0).ref.isEmpty()) {
            throw AppError.of("INVALID_ARGUMENT", "缺少幂等键", "每次充值、消费、提现都带一个 Idempotency-Key，超时重试才不会记两笔。");
        }
        return db.tx(() -> {
            Card card = get(cardId);
            List<LedgerEntry> existing = db.query(ENTRY + " WHERE card_id = ? AND ref IN (?, ?) ORDER BY created_at ASC", CardDao::scanEntry, cardId, drafts.get(0).ref, drafts.get(0).ref + ":fee");
            if (!existing.isEmpty()) {
                MoveResult result = new MoveResult();
                result.card = card;
                result.entries = existing;
                result.replayed = true;
                return result;
            }
            policy.check(card.kind, card.status);
            long balance = card.available.value;
            long now = System.currentTimeMillis();
            List<LedgerEntry> written = new ArrayList<>();
            for (int i = 0; i < drafts.size(); i++) {
                Draft d = drafts.get(i);
                if (d.amount <= 0) throw AppError.of("INVALID_ARGUMENT", "金额必须大于 0", "");
                balance = nextBalance(balance, d);
                LedgerEntry entry = new LedgerEntry();
                entry.id = Support.newId();
                entry.cardId = cardId;
                entry.kind = d.kind;
                entry.amount = new Cents(d.amount);
                entry.balanceAfter = new Cents(balance);
                entry.merchant = nz(d.merchant);
                entry.mcc = nz(d.mcc);
                entry.ref = d.ref;
                entry.note = nz(d.note);
                entry.createdAt = Instant.ofEpochMilli(now + i);
                db.update("""
                        INSERT INTO ledger_entries(id, card_id, kind, amount, balance_after, merchant, mcc, ref, note, created_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?)
                        """, entry.id, entry.cardId, entry.kind, d.amount, balance, entry.merchant, entry.mcc, entry.ref, entry.note, entry.createdAt.toEpochMilli());
                written.add(entry);
            }
            db.update("UPDATE cards SET available = ? WHERE id = ?", balance, cardId);
            card.available = new Cents(balance);
            MoveResult result = new MoveResult();
            result.card = card;
            result.entries = written;
            return result;
        });
    }

    /**
     * 按流水类型计算新余额。余额不够时抛出业务异常。
     *
     * @param balance 当前余额，单位分
     * @param d 一笔草稿
     * @return 新余额，单位分
     */
    private static long nextBalance(long balance, Draft d) {
        return switch (d.kind) {
            case "topup" -> {
                if (balance > Long.MAX_VALUE - d.amount) throw AppError.of("INVALID_ARGUMENT", "余额超出上限", "");
                yield balance + d.amount;
            }
            case "spend" -> {
                if (balance < d.amount) throw AppError.of("INSUFFICIENT_FUNDS", "卡余额不足", "消费只扣这一笔金额。");
                yield balance - d.amount;
            }
            case "withdraw", "fee" -> {
                if (balance < d.amount) throw AppError.of("INSUFFICIENT_FUNDS", "卡余额不足", "提现是外扣手续费，可用余额要盖住金额加手续费。");
                yield balance - d.amount;
            }
            default -> throw AppError.of("INVALID_ARGUMENT", "未知的账务类型", "");
        };
    }

    /**
     * 把卡查询的一行映射成卡。
     *
     * @param rs 当前行
     * @return 卡
     */
    static Card scanCard(java.sql.ResultSet rs) throws java.sql.SQLException {
        Card c = new Card();
        c.id = rs.getString(1);
        c.accountId = rs.getString(2);
        c.kind = rs.getString(3);
        c.status = rs.getString(4);
        c.last4 = rs.getString(5);
        c.expiry = rs.getString(6);
        c.holder = rs.getString(7);
        c.currency = rs.getString(8);
        c.available = new Cents(rs.getLong(9));
        c.createdAt = Instant.ofEpochMilli(rs.getLong(10));
        return c;
    }

    /**
     * 把流水查询的一行映射成流水。
     *
     * @param rs 当前行
     * @return 流水
     */
    static LedgerEntry scanEntry(java.sql.ResultSet rs) throws java.sql.SQLException {
        LedgerEntry e = new LedgerEntry();
        e.id = rs.getString(1);
        e.cardId = rs.getString(2);
        e.kind = rs.getString(3);
        e.amount = new Cents(rs.getLong(4));
        e.balanceAfter = new Cents(rs.getLong(5));
        e.merchant = rs.getString(6);
        e.mcc = rs.getString(7);
        e.ref = rs.getString(8);
        e.note = rs.getString(9);
        e.createdAt = Instant.ofEpochMilli(rs.getLong(10));
        return e;
    }

    /**
     * 空字符串代替 null。
     *
     * @param s 原文本
     * @return 非 null 文本
     */
    private static String nz(String s) { return s == null ? "" : s; }
}
