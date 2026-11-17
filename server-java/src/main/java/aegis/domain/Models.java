package aegis.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口和账本共用的数据结构。金额用字符串，时间用 ISO-8601。
 */
public final class Models {
    /** 工具类，不允许实例化。 */
    private Models() {}

    /**
     * 卡账本金额，单位是分。JSON 输出成两位美元，例如 25.50。
     */
    public static final class Cents {
        public final long value;

        /**
         * @param value 金额，单位分
         */
        public Cents(long value) { this.value = value; }

        /**
         * 序列化成两位小数字符串。
         *
         * @return 美元文本
         */
        @JsonValue
        public String json() { return Money.formatCents(value); }
    }

    /**
     * 返回给前端的错误体。空 hint 不输出。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class ErrBody {
        public String code;
        public String message;
        public String hint;
        public boolean retryable;
    }

    /**
     * 业务异常。控制器把它转成 error JSON。
     */
    public static final class AppError extends RuntimeException {
        public final String code;
        public final String hint;
        public final boolean retryable;

        /**
         * @param code 错误码
         * @param message 给人看的说明
         * @param hint 下一步该怎么做
         * @param retryable 是否值得重试
         */
        public AppError(String code, String message, String hint, boolean retryable) {
            super(message == null ? "" : message);
            this.code = code;
            this.hint = hint == null ? "" : hint;
            this.retryable = retryable;
        }

        /**
         * 创建一个不可重试的业务异常。
         *
         * @param code 错误码
         * @param message 说明
         * @param hint 下一步
         * @return 异常
         */
        public static AppError of(String code, String message, String hint) {
            return new AppError(code, message, hint, false);
        }

        /**
         * 转成响应体。
         *
         * @return 错误 JSON 对应的对象
         */
        public ErrBody body() {
            ErrBody b = new ErrBody();
            b.code = code;
            b.message = getMessage();
            b.hint = hint;
            b.retryable = retryable;
            return b;
        }
    }

    /** 一条链上的地址和派生路径。 */
    public static final class Address {
        public String chain;
        public String address;
        public String path;
    }

    /** 一个助记词对应的账户，下面挂五条链地址。 */
    public static final class Account {
        public String id;
        public String label;
        public Instant createdAt;
        public List<Address> addresses = new ArrayList<>();
    }

    /** 一条链上可查询或可转账的资产。 */
    public static final class Asset {
        public String symbol;
        public String name;
        public int decimals;
        public String kind;
        @JsonInclude(JsonInclude.Include.NON_EMPTY) public String contract;
        @JsonInclude(JsonInclude.Include.NON_EMPTY) public String note;
    }

    /** 手续费。amount 用该币的人类可读小数。 */
    public static final class Fee {
        public String symbol;
        public String amount;
        @JsonInclude(JsonInclude.Include.NON_EMPTY) public String usd;
        @JsonInclude(JsonInclude.Include.NON_EMPTY) public Map<String, String> detail;
    }

    /** 转账报价。payload 的具体形状由链决定。 */
    public static final class PrepareResult {
        public String chain;
        public String network;
        public String mode;
        public String asset;
        public String from;
        public String to;
        public String amount;
        public Fee fee;
        public Object payload;
        public List<String> warnings = new ArrayList<>();
    }

    /** 浏览器要签的 EIP-1559 交易。数值用十进制字符串。 */
    public static final class EvmPayload {
        public int type;
        public String chainId;
        public String nonce;
        public String to;
        public String value;
        public String data;
        public String gasLimit;
        public String maxFeePerGas;
        public String maxPriorityFeePerGas;
    }

    /** 比特币未花费输出。 */
    public static final class BtcInput {
        public String txid;
        public long vout;
        public long value;
    }

    /** 比特币输出。value 单位是聪。 */
    public static final class BtcOutput {
        public String address;
        public long value;
    }

    /** 尚未签名的比特币交易。 */
    public static final class BtcPayload {
        public String network;
        public List<BtcInput> inputs = new ArrayList<>();
        public List<BtcOutput> outputs = new ArrayList<>();
        public long feeSats;
    }

    /** 尚未签名的 Solana 转账。message 是要签的字节。 */
    public static final class SolPayload {
        public String message;
        public String blockhash;
        public String lamports;
        public String from;
        public String to;
    }

    /** 尚未签名的 TRON 交易。浏览器对 txID 签名。 */
    public static final class TronPayload {
        public String txID;
        public Object transaction;
    }

    /** 已保存的转账记录。 */
    public static final class Transfer {
        public String id;
        public String accountId;
        public String chain;
        public String asset;
        public String from;
        public String to;
        public String amount;
        public String fee;
        public String txHash;
        public String signedPayload;
        public String status;
        @JsonInclude(JsonInclude.Include.NON_EMPTY) public String detail;
        public Instant createdAt;
        public Instant updatedAt;
    }

    /** 链上交易当前状态。 */
    public static final class TxState {
        public String state;
        public String hash;
        @JsonInclude(JsonInclude.Include.NON_EMPTY) public String block;
        @JsonInclude(JsonInclude.Include.NON_EMPTY) public String detail;
    }

    /** 一条链的连通性检查结果。 */
    public static final class Health {
        public String chain;
        public String name;
        public boolean ok;
        public long latencyMs;
        @JsonInclude(JsonInclude.Include.NON_EMPTY) public String block;
        public String endpoint;
        public String mode;
        @JsonInclude(JsonInclude.Include.NON_NULL) public ErrBody error;
    }

    /** 前端可以手动触发的学习探针。 */
    public static final class Probe {
        public String name;
        public String title;
        public String description;
    }

    /** 支付卡。available 是卡账本余额，单位分。 */
    public static final class Card {
        public String id;
        public String accountId;
        public String kind;
        public String status;
        public String last4;
        public String expiry;
        public String holder;
        public String currency;
        public Cents available;
        public Instant createdAt;
    }

    /** 卡账本的一笔流水。 */
    public static final class LedgerEntry {
        public String id;
        public String cardId;
        public String kind;
        public Cents amount;
        public Cents balanceAfter;
        @JsonInclude(JsonInclude.Include.NON_EMPTY) public String merchant;
        @JsonInclude(JsonInclude.Include.NON_EMPTY) public String mcc;
        public String ref;
        @JsonInclude(JsonInclude.Include.NON_EMPTY) public String note;
        public Instant createdAt;
    }

    /** 一张卡某个月的汇总。 */
    public static final class Bill {
        public String cardId;
        public String month;
        public String currency;
        public Cents opening;
        public Cents topup;
        public Cents spend;
        public Cents withdraw;
        public Cents fees;
        public Cents closing;
        public List<LedgerEntry> entries = new ArrayList<>();
    }

    /** 一次动账的结果。replayed 为真表示幂等键已经入过账。 */
    public static final class MoveResult {
        public Card card;
        public List<LedgerEntry> entries = new ArrayList<>();
        public boolean replayed;
    }

    /** 元信息里的一条链。JSON 字段名 native 不能直接用 Java 关键字。 */
    public static final class ChainInfo {
        public String id;
        public String name;
        public String nativeSymbol;
        public String explorerTx;
        public List<Asset> assets = new ArrayList<>();

        /**
         * 转成前端要的 JSON 对象，把 nativeSymbol 输出成 native。
         *
         * @return 链信息
         */
        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("native", nativeSymbol);
            m.put("explorerTx", explorerTx);
            m.put("assets", assets);
            return m;
        }
    }
}
