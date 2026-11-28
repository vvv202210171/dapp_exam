package aegis.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import aegis.chain.live.TronClient;
import aegis.domain.Models.Asset;
import aegis.domain.Models.PrepareResult;
import aegis.domain.Models.TronPayload;
import aegis.domain.Money;
import aegis.platform.Support;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/**
 * 波场 Shasta 测试网转账。报价和广播走 TronGrid 的钱包接口，签名在本地完成。
 * TRX 是原生转账，USDT 是调用 TRC20 合约。两笔都对 txID 签名。
 * 成交后的交易可以在 Shasta TRONSCAN 上打开。
 */
public class TronTest {
    /** Shasta 公共节点。主网是 https://api.trongrid.io，两边的地址格式一样，但余额和交易不能混用。 */
    private static final String API = "https://api.shasta.trongrid.io";
    /**
     * Shasta 水龙头发放的测试 USDT，6 位小数。
     * 主网官方 USDT 是 TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t，合约不同，不能拿到测试网用。
     */
    private static final String USDT = "TG3XXyExBkPp9nzdajDZsozEu4BkaSJozs";
    /**
     * 64 位十六进制私钥，和以太坊同一条 secp256k1，不要带 0x。
     * 留空时跳过，避免测试套件向 Shasta 广播一笔真交易。不要把真实私钥提交到仓库。
     */
    private static final String PRIVATE_KEY = "10d647b6515a459fba626e8681cac3f8450f92b036c79e03b719cdd8549a6231";
    /**
     * 收款地址，T 开头。留空则转回付款地址自己，用来确认签名和广播是否成功。
     * 换成别人的地址前，先确认那是你自己的测试网账户。
     */
    private static final String TO = "TKrpykDjbZhWCBycXBERQPAPrjfrmTfLGG";

    /**
     * 给自己转 1 TRX。账户必须已经在 Shasta 收过 TRX，否则节点认为它还没激活。
     * 水龙头：https://shasta.tronex.io 或 https://www.trongrid.io/faucet （选 Shasta）。
     */
    @Test
    public void testTron() throws Exception {
        assumeTrue(PRIVATE_KEY.matches("(?i)(0x)?[0-9a-f]{64}"), "没有配置 Shasta 私钥，跳过");
        String hexKey = PRIVATE_KEY.startsWith("0x") || PRIVATE_KEY.startsWith("0X") ? PRIVATE_KEY.substring(2) : PRIVATE_KEY;
        ECKeyPair key = ECKeyPair.create(new BigInteger(hexKey, 16));
        String from = tronAddress(key);
        String to = TO.isBlank() ? from : TO;
        System.out.println("TRON address: " + from);

        Asset trx = new Asset();
        trx.symbol = "TRX";
        trx.kind = "native";
        trx.decimals = 6;
        // 1 TRX = 1_000_000 sun。sun 是链上的最小单位，和聪、wei 是同一类东西。
        long sun = 2_000_000;
        TronClient client = new TronClient("testnet", API, new Support.HttpJson());
        String balance = client.balance(from, trx);
        System.out.println("TRX balance before: " + balance);

        // 节点按付款地址、收款地址和 sun 数造一笔未签名交易，并给出 txID。
        PrepareResult quote = client.prepare(from, to, Money.formatUnits(BigInteger.valueOf(sun), 6), BigInteger.valueOf(sun), trx);
        TronPayload payload = (TronPayload) quote.payload;
        System.out.println("fee: " + quote.fee.amount + " " + quote.fee.symbol + ", txID: " + payload.txID);
        assertEquals(64, payload.txID.length(), "txID 应是 raw_data 的 32 字节哈希");

        String signed = sign(key, payload);
        String txid = client.broadcast(signed);
        System.out.println("https://shasta.tronscan.org/#/transaction/" + txid);
        assertTrue(txid.length() == 64);
         balance = client.balance(from, trx);
        System.out.println("TRX balance after: " + balance);
    }

    /**
     * 转出 1 个测试 USDT。钱进的是 TRC20 合约，不是普通的 TRX 转账。
     * 手续费仍然用 TRX 支付：优先扣能量和带宽，不够就燃烧 TRX，燃烧上限是 fee_limit（100 TRX）。
     * 付款地址要同时有测试 USDT 和少量 TRX。USDT 可以从 Shasta 水龙头领。
     */
    @Test
    public void testUsdt() throws Exception {
        assumeTrue(PRIVATE_KEY.matches("(?i)(0x)?[0-9a-f]{64}"), "没有配置 Shasta 私钥，跳过");
        String hexKey = PRIVATE_KEY.startsWith("0x") || PRIVATE_KEY.startsWith("0X") ? PRIVATE_KEY.substring(2) : PRIVATE_KEY;
        ECKeyPair key = ECKeyPair.create(new BigInteger(hexKey, 16));
        String from = tronAddress(key);
        String to = TO.isBlank() ? from : TO;
        System.out.println("TRON address: " + from);

        Asset usdt = new Asset();
        usdt.symbol = "USDT";
        usdt.kind = "token";
        usdt.decimals = 6;
        usdt.contract = USDT;
        // 和 TRX 一样是 6 位小数：1 USDT = 1_000_000 个最小单位。这里的单位不是 sun，sun 只用来计量 TRX。
        BigInteger units = BigInteger.valueOf(1_000_000);
        TronClient client = new TronClient("testnet", API, new Support.HttpJson());
        System.out.println("TRX balance before: " + client.balance(from, trxAsset()));
        System.out.println("USDT balance before: " + client.balance(from, usdt));

        // 节点调用合约的 transfer(address,uint256)。参数里是收款地址和数量，函数选择器由节点另填。
        // 返回的仍是一笔未签名交易。签名步骤和 TRX 完全一样：对 txID 签名，不需要再签合约参数。
        PrepareResult quote = client.prepare(from, to, Money.formatUnits(units, 6), units, usdt);
        TronPayload payload = (TronPayload) quote.payload;
        System.out.println("fee estimate: " + quote.fee.amount + " " + quote.fee.symbol + ", txID: " + payload.txID);
        assertEquals(64, payload.txID.length(), "txID 应是 raw_data 的 32 字节哈希");

        String signed = sign(key, payload);
        String txid = client.broadcast(signed);
        System.out.println("https://shasta.tronscan.org/#/transaction/" + txid);
        assertTrue(txid.length() == 64);
        System.out.println("TRX balance after: " + client.balance(from, trxAsset()));
        System.out.println("USDT balance after: " + client.balance(from, usdt));
    }

    /**
     * 查手续费用的原生 TRX。TRC20 转账燃烧的是 TRX，不是 USDT。
     *
     * @return TRX 资产
     */
    private static Asset trxAsset() {
        Asset trx = new Asset();
        trx.symbol = "TRX";
        trx.kind = "native";
        trx.decimals = 6;
        return trx;
    }

    /**
     * 对 txID 做 secp256k1 签名，再把 65 字节签名填回节点给的那笔交易。
     * 签的是 txID，不是整段 JSON。txID 本身已经是 raw_data 的 SHA-256，所以这里不再哈希一次。
     * 签名布局和 TronWeb 一致：32 字节 r、32 字节 s、最后 1 字节是 27 加恢复编号。
     *
     * @param key 付款私钥，必须能推出 createtransaction 里的 owner_address
     * @param payload 节点返回的未签名交易，里面有 txID 和完整 transaction
     * @return 可交给 /wallet/broadcasttransaction 的交易 JSON
     */
    private static String sign(ECKeyPair key, TronPayload payload) throws Exception {
        // needToHash 传 false：txID 已经是 32 字节哈希。再做一次 Keccak 会和节点验签对不上。
        // web3j 会把 s 收成低位形式，避免同一笔交易出现两种合法签名。
        Sign.SignatureData signature = Sign.signMessage(Support.unhex(payload.txID), key, false);
        byte[] full = new byte[65];
        System.arraycopy(signature.getR(), 0, full, 0, 32);
        System.arraycopy(signature.getS(), 0, full, 32, 32);
        // 最后一个字节是 27 或 28。节点用它从签名里恢复公钥，再对比 owner_address。
        full[64] = signature.getV()[0];

        Map<String, Object> tx = Support.JSON.convertValue(payload.transaction, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        // signature 是数组，因为一笔交易可以有多个签名。普通转账只放这一条，十六进制不带 0x。
        tx.put("signature", List.of(Support.hex(full)));
        return Support.JSON.writeValueAsString(tx);
    }

    /**
     * 由私钥推出 T 开头的地址。算法是：取以太坊那 20 字节，前面加上版本字节 0x41，再做 Base58Check。
     *
     * @param key 私钥
     * @return Base58Check 地址
     */
    private static String tronAddress(ECKeyPair key) {
        byte[] eth = Numeric.hexStringToByteArray(Keys.getAddress(key));
        byte[] payload = new byte[21];
        payload[0] = 0x41;
        System.arraycopy(eth, 0, payload, 1, 20);
        byte[] checksum = Arrays.copyOf(Support.sha256(Support.sha256(payload)), 4);
        byte[] checked = new byte[payload.length + checksum.length];
        System.arraycopy(payload, 0, checked, 0, payload.length);
        System.arraycopy(checksum, 0, checked, payload.length, checksum.length);
        return Support.base58Encode(checked);
    }
}
