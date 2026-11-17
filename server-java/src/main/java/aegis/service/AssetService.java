package aegis.service;

import aegis.chain.Chains;
import aegis.dao.AccountDao;
import aegis.domain.Models.Account;
import aegis.domain.Models.Address;
import aegis.domain.Models.AppError;
import aegis.domain.Models.Asset;
import aegis.domain.Money;
import aegis.platform.Support;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Service;

/**
 * 按账户地址查询各链余额，并折成美元。
 */
@Service
public class AssetService {
    private final AccountDao accounts;
    private final Chains.Registry chains;
    private final PriceService prices;

    /**
     * @param accounts 账户数据访问
     * @param chains 链适配器
     * @param prices 价格
     */
    public AssetService(AccountDao accounts, Chains.Registry chains, PriceService prices) {
        this.accounts = accounts;
        this.chains = chains;
        this.prices = prices;
    }

    /**
     * 并行查询该账户每条链的余额。
     *
     * @param accountId 账户编号
     * @return 各链资产和总美元价值
     */
    public Map<String, Object> portfolio(String accountId) {
        Account acc = accounts.get(accountId);
        PriceService.Snapshot snap = prices.snapshot();
        List<Future<Map<String, Object>>> jobs = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Address addr : acc.addresses) jobs.add(pool.submit(() -> one(addr, snap.prices())));
            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> usd = new ArrayList<>();
            for (Future<Map<String, Object>> job : jobs) {
                Map<String, Object> row = job.get();
                rows.add(row);
                for (Object asset : (List<?>) row.get("assets")) {
                    @SuppressWarnings("unchecked") Map<String, Object> line = (Map<String, Object>) asset;
                    if (Boolean.TRUE.equals(line.get("ok"))) usd.add(String.valueOf(line.get("usd")));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accountId", accountId);
            out.put("mode", chains.mode);
            out.put("network", chains.network);
            out.put("priceSource", snap.source());
            out.put("totalUsd", Money.sumUsd(usd));
            out.put("chains", rows);
            return out;
        } catch (AppError e) {
            throw e;
        } catch (Exception e) {
            if (e.getCause() instanceof AppError ae) throw ae;
            throw new RuntimeException(e);
        }
    }

    /**
     * 查询一个地址上的原生币和代币。原生币失败时，后面的代币不再请求。
     *
     * @param addr 链和地址
     * @param prices 符号到美元价格
     * @return 这一条链的资产行
     */
    private Map<String, Object> one(Address addr, Map<String, String> prices) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chain", addr.chain);
        row.put("name", Chains.name(addr.chain));
        row.put("address", addr.address);
        row.put("path", addr.path);
        row.put("ok", true);
        List<Map<String, Object>> lines = new ArrayList<>();
        row.put("assets", lines);
        Chains.Adapter adapter;
        try { adapter = chains.get(addr.chain); }
        catch (RuntimeException e) {
            row.put("ok", false);
            row.put("error", Support.explainBody(e));
            return row;
        }
        List<Asset> assets = Chains.assets(addr.chain, chains.network, chains.mode);
        boolean rowOk = true;
        var rowError = (aegis.domain.Models.ErrBody) null;
        for (int i = 0; i < assets.size(); i++) {
            Asset asset = assets.get(i);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("symbol", asset.symbol);
            line.put("name", asset.name);
            line.put("kind", asset.kind);
            line.put("decimals", asset.decimals);
            if (asset.contract != null && !asset.contract.isEmpty()) line.put("contract", asset.contract);
            if (asset.note != null && !asset.note.isEmpty()) line.put("note", asset.note);
            if (i > 0 && !rowOk) {
                line.put("ok", false);
                line.put("amount", "0");
                line.put("usd", "0.00");
                line.put("error", rowError);
                lines.add(line);
                continue;
            }
            try {
                String amount = adapter.balance(addr.address, asset);
                String usd = "0.00";
                String price = prices.get(asset.symbol);
                if (price != null) try { usd = Money.mulUsd(amount, price); } catch (Exception ignored) {}
                line.put("ok", true);
                line.put("amount", amount);
                line.put("usd", usd);
            } catch (Exception e) {
                var explained = Support.explainBody(e);
                line.put("ok", false);
                line.put("amount", "0");
                line.put("usd", "0.00");
                line.put("error", explained);
                if (i == 0) { rowOk = false; rowError = explained; row.put("ok", false); row.put("error", explained); }
            }
            lines.add(line);
        }
        return row;
    }
}
