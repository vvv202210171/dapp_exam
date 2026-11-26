package aegis;

import aegis.config.CardKey;
import aegis.dao.AccountDao;
import aegis.dao.CardDao;
import aegis.dao.Database;
import aegis.domain.Money;
import aegis.platform.Support;
import aegis.service.CardService;
import aegis.service.SettlementService;
import aegis.txbuild.Spl;
import aegis.txbuild.TxBuild;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.List;

/**
 * 不启动 Spring 的自测：金额、deposit 编解码、卡账本幂等和跨卡冲突。
 */
public final class SelfTest {
    /**
     * 跑完全部断言。失败时抛出 AssertionError。
     *
     * @param args 不使用
     */
    public static void main(String[] args) throws Exception {
        eq("25.5", Money.formatUnits(Money.parseUnits("25.50", 2), 2));
        eq("2550", Long.toString(Money.parseCents("25.50")));
        eq(Money.formatCents(2550), "25.50");
        byte[] round = Support.base58Decode(Support.base58Encode(new byte[] {0, 1, 2, 3, 4}));
        if (round.length != 5 || round[4] != 4) throw new AssertionError("base58");
        String data = SettlementService.Abi.deposit("0x5FbDB2315678afecb367f032d93F642f64180aa3", BigInteger.valueOf(22_300_000));
        var parsed = SettlementService.Abi.parseDeposit(data);
        if (!parsed.token().equalsIgnoreCase("0x5FbDB2315678afecb367f032d93F642f64180aa3") || parsed.amount().compareTo(BigInteger.valueOf(22_300_000)) != 0) {
            throw new AssertionError("deposit abi");
        }
        try { SettlementService.Abi.parseDeposit("0x095ea7b3"); throw new AssertionError("approve must not parse"); }
        catch (RuntimeException expected) {}
        var dir = Files.createTempDirectory("aegis-java");
        Database database = new Database(dir.resolve("aegis.db"));
        AccountDao accountDao = new AccountDao(database);
        CardDao cardDao = new CardDao(database);
        var eth = address("ethereum", "0x9858effd232b4033e47d90003d41ec34ecaeda94");
        var btc = address("bitcoin", "tb1qcr8te4kr609gcawutmrza0j4xv80jy8z306fy0");
        var bnb = address("bnb", "0x9858effd232b4033e47d90003d41ec34ecaeda94");
        var sol = address("solana", "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk");
        var tron = address("tron", "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb");
        var acc = accountDao.sync("测试", List.of(btc, eth, bnb, sol, tron));
        if (acc.id == null || acc.addresses.size() != 5) throw new AssertionError("sync");
        var cards = new CardService(accountDao, cardDao, database, new CardKey(new byte[32]));
        var issued = cards.create(acc.id, "virtual", "测试");
        if (!issued.pan().startsWith("990016") || issued.pan().length() != 16) throw new AssertionError("pan");
        var move = new CardService.Move();
        move.amount = "10.00";
        move.idempotencyKey = "abc";
        cards.topup(issued.card().id, move);
        move.idempotencyKey = "spend-1";
        move.merchant = "星巴克";
        move.amount = "4.00";
        cards.spend(issued.card().id, move);
        if (cards.get(issued.card().id).available.value != 600) throw new AssertionError("balance");
        cards.topup(issued.card().id, moveWith("10.00", "abc"));
        if (cards.get(issued.card().id).available.value != 600) throw new AssertionError("replay");
        try { cards.spend(issued.card().id, spend("100.00", "too-much")); throw new AssertionError("should reject"); }
        catch (aegis.domain.Models.AppError expected) {}
        var other = cards.create(acc.id, "virtual", "另一张");
        cards.creditDeposit(issued.card().id, moveWith("1.00", "aa".repeat(32)));
        try { cards.creditDeposit(other.card().id, moveWith("1.00", "0x" + "aa".repeat(32))); throw new AssertionError("cross card"); }
        catch (aegis.domain.Models.AppError expected) {}
        String solMsg = Support.base58Encode(TxBuild.buildSolTransfer(sol.address, "4fYNw3dojWmQ4dXtSGE9epjRGy9pFSx62YypT7avPYvA", 1, Support.base58Encode(new byte[32])));
        if (solMsg.isEmpty()) throw new AssertionError("sol");
        try { aegis.chain.Chains.rejectZero("tron", "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb"); throw new AssertionError("zero"); }
        catch (aegis.domain.Models.AppError expected) {}
        eq(Spl.associatedTokenAddress(sol.address, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"), "5N3f1tj9v1vc5TUZ8S7mCAnVmjVKrfnzXWhxLaxyZAgt");
        eq(Spl.associatedTokenAddress(sol.address, "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"), "Bjr2Shw9sy31UNdm8kYs5y72iTbnxB4JemK98vJqyQWm");
        byte[] tokenMsg = Spl.buildTokenTransfer(sol.address, "5N3f1tj9v1vc5TUZ8S7mCAnVmjVKrfnzXWhxLaxyZAgt", "Bjr2Shw9sy31UNdm8kYs5y72iTbnxB4JemK98vJqyQWm", "4fYNw3dojWmQ4dXtSGE9epjRGy9pFSx62YypT7avPYvA", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", 1_000_000, 6, false, Support.base58Encode(new byte[32]));
        if (tokenMsg[0] != 1 || tokenMsg[1] != 0 || tokenMsg[2] != 2 || tokenMsg[3] != 5 || tokenMsg[tokenMsg.length - 1] != 6) throw new AssertionError("spl");
        System.out.println("self-test ok");
    }

    /**
     * 组装一笔只带金额和幂等键的动账。
     *
     * @param amount 美元文本
     * @param ref 幂等键
     * @return 动账参数
     */
    private static CardService.Move moveWith(String amount, String ref) {
        var move = new CardService.Move();
        move.amount = amount;
        move.idempotencyKey = ref;
        return move;
    }

    /**
     * 组装一笔星巴克消费。
     *
     * @param amount 美元文本
     * @param ref 幂等键
     * @return 动账参数
     */
    private static CardService.Move spend(String amount, String ref) {
        var move = moveWith(amount, ref);
        move.merchant = "星巴克";
        return move;
    }

    /**
     * 组装测试地址。
     *
     * @param chain 链编号
     * @param value 地址
     * @return 地址对象
     */
    private static aegis.domain.Models.Address address(String chain, String value) {
        var a = new aegis.domain.Models.Address();
        a.chain = chain;
        a.address = value;
        a.path = "m/44'/0'/0'/0/0";
        return a;
    }

    /**
     * 断言两段文本相同。
     *
     * @param got 实际值
     * @param want 期望值
     */
    private static void eq(String got, String want) {
        if (!want.equals(got)) throw new AssertionError(got + " != " + want);
    }
}
