package aegis.service;

import aegis.chain.Chains;
import aegis.config.Config;
import aegis.dao.AccountDao;
import aegis.dao.TransferDao;
import aegis.domain.Models.Account;
import aegis.domain.Models.AppError;
import aegis.domain.Models.Asset;
import aegis.domain.Models.PrepareResult;
import aegis.domain.Models.Transfer;
import aegis.domain.Money;
import aegis.platform.Support;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 转账报价、保存签名和广播。私钥只留在浏览器。
 */
@Service
public class TransferService {
    private final AccountDao accounts;
    private final TransferDao transfers;
    private final Chains.Registry chains;
    private final PriceService prices;
    private final String network;
    private final String mode;

    /**
     * @param accounts 账户数据访问
     * @param transfers 转账数据访问
     * @param chains 链适配器
     * @param prices 手续费折美元
     * @param config 网络和模式
     */
    public TransferService(AccountDao accounts, TransferDao transfers, Chains.Registry chains, PriceService prices, Config config) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.chains = chains;
        this.prices = prices;
        this.network = config.network;
        this.mode = config.mode;
    }

    /**
     * 生成未签名载荷，并估算手续费的美元价值。
     *
     * @param accountId 账户编号
     * @param chain 链编号
     * @param asset 资产符号
     * @param from 付款地址
     * @param to 收款地址
     * @param amount 金额，按该资产的人类可读小数
     * @return 报价
     */
    public PrepareResult prepare(String accountId, String chain, String asset, String from, String to, String amount) {
        Ready ready = ready(accountId, chain, asset, from, to, amount);
        PrepareResult result = ready.adapter.prepare(Chains.normalize(chain, from), Chains.normalize(chain, to), amount.trim(), ready.units, ready.asset);
        PriceService.Snapshot snap = prices.snapshot();
        String price = snap.prices().get(result.fee.symbol);
        if (price != null) {
            try { result.fee.usd = Money.mulUsd(result.fee.amount, price); } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * 保存已签名交易。需要广播时先广播再落库；广播失败也会留下失败记录。
     *
     * @param in 已签名交易
     * @return 转账记录
     */
    public Transfer submit(Submit in) {
        ready(in.accountId, in.chain, in.asset, in.from, in.to, in.amount);
        String signed = in.signedPayload == null ? "" : in.signedPayload.trim();
        if (signed.isEmpty()) throw AppError.of("INVALID_ARGUMENT", "缺少签名结果", "先在浏览器里签名。服务器不保存助记词，也不能代签。");
        if (signed.length() > 64 * 1024) throw AppError.of("INVALID_ARGUMENT", "签名结果太大", "");
        Instant now = Instant.now();
        Transfer rec = new Transfer();
        rec.id = Support.newId();
        rec.accountId = in.accountId;
        rec.chain = in.chain;
        rec.asset = in.asset == null ? "" : in.asset.trim().toUpperCase();
        rec.from = Chains.normalize(in.chain, in.from);
        rec.to = Chains.normalize(in.chain, in.to);
        rec.amount = in.amount.trim();
        rec.fee = in.fee == null ? "" : in.fee.trim();
        rec.signedPayload = signed;
        rec.status = "signed";
        rec.createdAt = now;
        rec.updatedAt = now;
        if (!in.broadcast) {
            rec.detail = "已在本地签名，还没有广播。已签名交易等同于持票，任何拿到它的人都能广播。";
            transfers.insert(rec);
            return rec;
        }
        try {
            broadcast(rec);
        } catch (RuntimeException e) {
            transfers.insert(rec);
            throw e;
        }
        transfers.insert(rec);
        return rec;
    }

    /**
     * 广播一笔已签名但还没广播的记录。
     *
     * @param id 转账记录编号
     * @return 更新后的记录
     */
    public Transfer broadcast(String id) {
        Transfer rec = transfers.get(id);
        if (!"signed".equals(rec.status)) throw AppError.of("INVALID_ARGUMENT", "这笔交易不能再次广播", "只有停在“已签名”的记录可以稍后广播。");
        try { broadcast(rec); } catch (RuntimeException e) { transfers.update(rec); throw e; }
        transfers.update(rec);
        return rec;
    }

    /**
     * 用链上状态刷新记录。没有哈希时原样返回。
     *
     * @param id 转账记录编号
     * @return 更新后的记录
     */
    public Transfer refresh(String id) {
        Transfer rec = transfers.get(id);
        if (rec.txHash == null || rec.txHash.isEmpty()) return rec;
        var state = chains.get(rec.chain).status(rec.txHash);
        switch (state.state) {
            case "confirmed" -> { rec.status = "confirmed"; rec.detail = state.detail; }
            case "failed" -> { rec.status = "failed"; rec.detail = state.detail; }
            case "pending" -> { rec.status = "broadcast"; rec.detail = state.detail; }
            case "not_found" -> rec.detail = state.detail;
        }
        rec.updatedAt = Instant.now();
        transfers.update(rec);
        return rec;
    }

    /**
     * 列出账户的转账。
     *
     * @param accountId 账户编号
     * @return 转账列表
     */
    public List<Transfer> list(String accountId) {
        accounts.get(accountId);
        return transfers.list(accountId);
    }

    /**
     * 读取一笔转账。
     *
     * @param id 转账记录编号
     * @return 转账记录
     */
    public Transfer get(String id) { return transfers.get(id); }

    /**
     * 调用链适配器广播，并尽量立刻读一次状态。
     *
     * @param rec 要广播的记录，方法会改它的哈希和状态
     */
    private void broadcast(Transfer rec) {
        Chains.Adapter adapter = chains.get(rec.chain);
        try {
            rec.txHash = adapter.broadcast(rec.signedPayload);
            rec.status = "broadcast";
            rec.detail = "已提交给节点。";
            rec.updatedAt = Instant.now();
            try {
                var state = adapter.status(rec.txHash);
                switch (state.state) {
                    case "confirmed" -> { rec.status = "confirmed"; rec.detail = state.detail; }
                    case "failed" -> { rec.status = "failed"; rec.detail = state.detail; }
                    case "pending" -> { rec.status = "broadcast"; rec.detail = state.detail; }
                }
            } catch (Exception ignored) {}
        } catch (RuntimeException e) {
            AppError explained = Support.explain(e);
            rec.status = "failed";
            rec.detail = explained.getMessage();
            rec.updatedAt = Instant.now();
            throw explained;
        }
    }

    /**
     * 校验链、付款地址归属、收款地址和金额精度。
     *
     * @param accountId 账户编号
     * @param chain 链编号
     * @param assetSymbol 资产符号
     * @param from 付款地址
     * @param to 收款地址
     * @param amount 人类可读金额
     * @return 适配器、资产和最小单位金额
     */
    private Ready ready(String accountId, String chain, String assetSymbol, String from, String to, String amount) {
        if (!Chains.valid(chain)) throw AppError.of("INVALID_ARGUMENT", "不支持的链", "");
        Account acc = accounts.get(accountId);
        if (!AccountService.owns(acc, chain, from)) throw AppError.of("INVALID_ARGUMENT", "付款地址不属于这个账户", "只能从当前助记词派生出的地址转出。");
        Chains.validate(chain, network, to);
        Asset asset = Chains.findAsset(chain, network, mode, assetSymbol);
        BigInteger units;
        try { units = Money.parseUnits(amount, asset.decimals); }
        catch (IllegalArgumentException e) { throw AppError.of("INVALID_ARGUMENT", e.getMessage(), "小数位不能超过该资产的精度。例如 BTC 最多 8 位，ETH 最多 18 位，BSC 上的 USDT 是 18 位而以太坊上的 USDT 是 6 位。"); }
        if (units.signum() == 0) throw AppError.of("INVALID_ARGUMENT", "金额必须大于 0", "");
        return new Ready(chains.get(chain), asset, units);
    }

    /**
     * 报价通过后的链适配器和金额。
     *
     * @param adapter 链适配器
     * @param asset 资产
     * @param units 最小单位金额
     */
    private record Ready(Chains.Adapter adapter, Asset asset, BigInteger units) {}

    /**
     * 提交已签名交易的入参。
     */
    public static final class Submit {
        public String accountId;
        public String chain;
        public String asset;
        public String from;
        public String to;
        public String amount;
        public String fee;
        public String signedPayload;
        public boolean broadcast;
    }
}
