package aegis.service;

import aegis.config.CardKey;
import aegis.dao.AccountDao;
import aegis.dao.CardDao;
import aegis.dao.Database;
import aegis.domain.Models.AppError;
import aegis.domain.Models.Bill;
import aegis.domain.Models.Card;
import aegis.domain.Models.Cents;
import aegis.domain.Models.LedgerEntry;
import aegis.domain.Models.MoveResult;
import aegis.domain.Money;
import aegis.platform.Support;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 卡的开立、入账、消费、提现、状态和账单。余额单位是分。
 */
@Service
public class CardService {
    private static final Map<String, Map<String, Map<String, String>>> TRANSITIONS = Map.of(
            "virtual", Map.of(
                    "active", Map.of("freeze", "frozen", "close", "closed"),
                    "frozen", Map.of("unfreeze", "active", "close", "closed")),
            "physical", Map.of(
                    "requested", Map.of("issue", "issued", "close", "closed"),
                    "issued", Map.of("ship", "shipped", "close", "closed"),
                    "shipped", Map.of("activate", "active", "close", "closed"),
                    "active", Map.of("freeze", "frozen", "close", "closed"),
                    "frozen", Map.of("unfreeze", "active", "close", "closed")));

    private final AccountDao accounts;
    private final CardDao cards;
    private final Database db;
    private final byte[] key;

    /**
     * @param accounts 用来确认账户存在
     * @param cards 卡数据访问
     * @param db 状态变更使用的事务
     * @param key 卡号加密密钥
     */
    public CardService(AccountDao accounts, CardDao cards, Database db, CardKey key) {
        this.accounts = accounts;
        this.cards = cards;
        this.db = db;
        this.key = key.bytes();
    }

    /**
     * 开一张卡。虚拟卡直接可用，实体卡从 requested 开始。
     *
     * @param accountId 账户编号
     * @param kind virtual 或 physical
     * @param holder 持卡人
     * @return 卡以及只返回一次的卡号和 CVV
     */
    public Issued create(String accountId, String kind, String holder) {
        kind = kind == null ? "" : kind.trim();
        if (!kind.equals("virtual") && !kind.equals("physical")) throw AppError.of("INVALID_ARGUMENT", "卡类型不正确", "virtual 是虚拟卡，physical 是实体卡。");
        holder = holder == null ? "" : holder.trim();
        if (holder.isEmpty() || holder.codePointCount(0, holder.length()) > 40) throw AppError.of("INVALID_ARGUMENT", "持卡人姓名不正确", "填写 1 到 40 个字符。");
        accounts.get(accountId);
        String pan = newPan();
        String cvv = "" + Support.cryptoInt(10) + Support.cryptoInt(10) + Support.cryptoInt(10);
        String status = "physical".equals(kind) ? "requested" : "active";
        String expiry = DateTimeFormatter.ofPattern("MM/yy").withZone(ZoneOffset.UTC).format(Instant.now().atZone(ZoneOffset.UTC).plusYears(4));
        Card card = cards.insert(new CardDao.NewCard(accountId, kind, status, pan.substring(pan.length() - 4), Support.encrypt(key, pan.getBytes()), Support.encrypt(key, cvv.getBytes()), expiry, holder));
        return new Issued(card, pan, cvv);
    }

    /**
     * 列出账户的卡。
     *
     * @param accountId 账户编号
     * @return 卡列表
     */
    public List<Card> list(String accountId) {
        accounts.get(accountId);
        return cards.list(accountId);
    }

    /**
     * 读取一张卡。
     *
     * @param id 卡编号
     * @return 卡
     */
    public Card get(String id) { return cards.get(id); }

    /**
     * 解密卡号和 CVV。
     *
     * @param id 卡编号
     * @return 明文卡号、CVV 和有效期
     */
    public Secret reveal(String id) {
        Card card = cards.get(id);
        String[] enc = cards.secrets(id);
        return new Secret(new String(Support.decrypt(key, enc[0])), new String(Support.decrypt(key, enc[1])), card.expiry);
    }

    /**
     * 用链上存入哈希给卡入账。同一哈希不能记到另一张卡。
     *
     * @param cardId 卡编号
     * @param in 金额和交易哈希
     * @return 入账结果
     */
    public MoveResult creditDeposit(String cardId, Move in) {
        String ref = trimHex(in.idempotencyKey);
        if (ref.isEmpty()) throw AppError.of("INVALID_ARGUMENT", "缺少存入交易", "先在小狐狸里把 USDC 划进结算合约。");
        String other = cards.topupCardByRef(ref);
        if (!other.isEmpty() && !other.equals(cardId)) throw AppError.of("CONFLICT", "这笔存入已经入过账", "同一笔链上存入只能给一张卡加余额。");
        in.idempotencyKey = ref;
        if (in.note == null || in.note.isBlank()) in.note = "USDC 已存入结算合约";
        return topup(cardId, in);
    }

    /**
     * 给卡加余额。注销的卡不能充值。
     *
     * @param cardId 卡编号
     * @param in 金额、备注和幂等键
     * @return 动账结果
     */
    public MoveResult topup(String cardId, Move in) {
        Ref money = moneyRef(in);
        String note = in.note == null || in.note.isBlank() ? "充值" : in.note.trim();
        return cards.move(cardId, List.of(new CardDao.Draft("topup", money.amount, "", "", note, money.ref)), CardService::allowTopup);
    }

    /**
     * 记一笔消费。只检查卡余额，不读合约。
     *
     * @param cardId 卡编号
     * @param in 金额、商户和幂等键
     * @return 动账结果
     */
    public MoveResult spend(String cardId, Move in) {
        Ref money = moneyRef(in);
        String merchant = in.merchant == null ? "" : in.merchant.trim();
        if (merchant.isEmpty() || merchant.codePointCount(0, merchant.length()) > 80) throw AppError.of("INVALID_ARGUMENT", "需要商户名称", "消费记一笔商户，方便账单对账。");
        String mcc = in.mcc == null ? "" : in.mcc.trim();
        if (!mcc.isEmpty() && !mcc.matches("\\d{4}")) throw AppError.of("INVALID_ARGUMENT", "MCC 应为 4 位数字", "例如餐饮 5814。可以留空。");
        return cards.move(cardId, List.of(new CardDao.Draft("spend", money.amount, merchant, mcc, in.note == null ? "" : in.note.trim(), money.ref)), CardService::allowSpend);
    }

    /**
     * 从卡账本扣提现金额，并另记至少 1 分的 1% 手续费。
     *
     * @param cardId 卡编号
     * @param in 金额和幂等键
     * @return 动账结果，包含提现和手续费两条流水
     */
    public MoveResult withdraw(String cardId, Move in) {
        Ref money = moneyRef(in);
        long fee = Math.max(1, money.amount / 100);
        String note = in.note == null || in.note.isBlank() ? "提现到钱包余额" : in.note.trim();
        return cards.move(cardId, List.of(
                new CardDao.Draft("withdraw", money.amount, "", "", note, money.ref),
                new CardDao.Draft("fee", fee, "", "", "提现手续费 1%，最低 0.01 美元", money.ref + ":fee")), CardService::allowSpend);
    }

    /**
     * 按卡类型和当前状态执行动作。
     *
     * @param cardId 卡编号
     * @param action issue、ship、activate、freeze、unfreeze 或 close
     * @return 更新后的卡
     */
    public Card transition(String cardId, String action) {
        String step = action == null ? "" : action.trim();
        if (step.isEmpty()) throw AppError.of("INVALID_ARGUMENT", "缺少动作", "实体卡可用 issue、ship、activate。所有卡都可以 freeze、unfreeze、close。");
        return db.tx(() -> {
            Card card = cards.get(cardId);
            String next = TRANSITIONS.getOrDefault(card.kind, Map.of()).getOrDefault(card.status, Map.of()).get(step);
            if (next == null) throw AppError.of("INVALID_ARGUMENT", "当前状态不能做这个操作", "实体卡要按 申请 → 制卡 → 寄出 → 激活 走。冻结的卡要先解冻。");
            cards.updateStatus(cardId, next);
            card.status = next;
            return card;
        });
    }

    /**
     * 汇总某个月的充值、消费、提现和手续费。
     *
     * @param cardId 卡编号
     * @param month 月份 YYYY-MM，空则当前 UTC 月
     * @return 月账单
     */
    public Bill bill(String cardId, String month) {
        Card card = cards.get(cardId);
        month = month == null ? "" : month.trim();
        if (month.isEmpty()) month = YearMonth.now(ZoneOffset.UTC).toString();
        YearMonth start;
        try { start = YearMonth.parse(month); } catch (Exception e) { throw AppError.of("INVALID_ARGUMENT", "月份格式应为 YYYY-MM", ""); }
        Instant from = start.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = start.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long opening = 0;
        List<LedgerEntry> inMonth = new ArrayList<>();
        for (LedgerEntry e : cards.entries(cardId)) {
            if (e.createdAt.isBefore(from)) opening = e.balanceAfter.value;
            else if (e.createdAt.isBefore(to)) inMonth.add(e);
        }
        Bill bill = new Bill();
        bill.cardId = cardId;
        bill.month = month;
        bill.currency = card.currency;
        bill.opening = new Cents(opening);
        bill.entries = inMonth;
        long topup = 0, spend = 0, withdraw = 0, fees = 0, closing = opening;
        for (LedgerEntry e : inMonth) {
            switch (e.kind) {
                case "topup" -> topup += e.amount.value;
                case "spend" -> spend += e.amount.value;
                case "withdraw" -> withdraw += e.amount.value;
                case "fee" -> fees += e.amount.value;
            }
            closing = e.balanceAfter.value;
        }
        bill.topup = new Cents(topup);
        bill.spend = new Cents(spend);
        bill.withdraw = new Cents(withdraw);
        bill.fees = new Cents(fees);
        bill.closing = new Cents(closing);
        return bill;
    }

    /**
     * 充值只拒绝已注销的卡。
     *
     * @param kind 卡类型
     * @param status 当前状态
     */
    private static void allowTopup(String kind, String status) {
        if ("closed".equals(status)) throw AppError.of("CARD_INACTIVE", "卡已注销", "注销后不能再充值。");
    }

    /**
     * 消费和提现要求卡处于 active。
     *
     * @param kind 卡类型
     * @param status 当前状态
     */
    private static void allowSpend(String kind, String status) {
        if (!"active".equals(status)) {
            String hint = "先把卡解冻。";
            if ("physical".equals(kind) && ("requested".equals(status) || "issued".equals(status) || "shipped".equals(status))) hint = "实体卡要激活后才能消费或提现。可以先充值。";
            if ("closed".equals(status)) hint = "注销后的卡不能再动账。";
            throw AppError.of("CARD_INACTIVE", "卡当前状态不能消费或提现", hint);
        }
    }

    /**
     * 把金额解析成分，并整理幂等键。
     *
     * @param in 动账参数
     * @return 金额和幂等键
     */
    private static Ref moneyRef(Move in) {
        long amount;
        try { amount = Money.parseCents(in.amount); }
        catch (IllegalArgumentException e) { throw AppError.of("INVALID_ARGUMENT", e.getMessage(), "金额用美元，最多两位小数，例如 25.50。"); }
        String ref = in.idempotencyKey == null ? "" : in.idempotencyKey.trim();
        if (ref.isEmpty()) ref = Support.newId();
        if (ref.length() > 64 || ref.contains(":")) throw AppError.of("INVALID_ARGUMENT", "幂等键不合法", "使用 UUID，不要包含冒号。");
        return new Ref(amount, ref);
    }

    /**
     * 生成 16 位教学卡号，前缀 990016，最后一位是 Luhn 校验。
     *
     * @return 卡号
     */
    private static String newPan() {
        StringBuilder b = new StringBuilder("990016");
        for (int i = 0; i < 9; i++) b.append(Support.cryptoInt(10));
        String base = b.toString();
        return base + luhn(base);
    }

    /**
     * 计算 Luhn 校验位。
     *
     * @param base 不含校验位的数字串
     * @return 0 到 9
     */
    private static int luhn(String base) {
        int sum = 0;
        boolean alt = true;
        for (int i = base.length() - 1; i >= 0; i--) {
            int d = base.charAt(i) - '0';
            if (alt) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            alt = !alt;
        }
        return (10 - (sum % 10)) % 10;
    }

    /**
     * 去掉哈希前缀并转成小写。
     *
     * @param s 交易哈希
     * @return 不带 0x 的哈希
     */
    private static String trimHex(String s) {
        s = s == null ? "" : s.trim().toLowerCase();
        return s.startsWith("0x") ? s.substring(2) : s;
    }

    /**
     * 开卡时一次性返回的明文。
     *
     * @param card 卡
     * @param pan 完整卡号
     * @param cvv 三位 CVV
     */
    public record Issued(Card card, String pan, String cvv) {}

    /**
     * 查看卡密时返回的明文。
     *
     * @param pan 完整卡号
     * @param cvv 三位 CVV
     * @param expiry 有效期
     */
    public record Secret(String pan, String cvv, String expiry) {}

    /**
     * 一次充值、消费或提现的入参。
     */
    public static final class Move {
        public String amount;
        public String merchant;
        public String mcc;
        public String note;
        public String idempotencyKey;
    }
    /**
     * 已解析的金额和幂等键。
     *
     * @param amount 金额，单位分
     * @param ref 幂等键
     */
    private record Ref(long amount, String ref) {}
}
