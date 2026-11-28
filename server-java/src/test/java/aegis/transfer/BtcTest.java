package aegis.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import aegis.chain.live.BtcClient;
import aegis.domain.Models.Asset;
import aegis.domain.Models.BtcInput;
import aegis.domain.Models.BtcOutput;
import aegis.domain.Models.BtcPayload;
import aegis.domain.Models.PrepareResult;
import aegis.domain.Money;
import aegis.platform.Support;
import java.math.BigInteger;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.core.Utils;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.jupiter.api.Test;

/**
 * 比特币测试网转账。报价和广播走 mempool.space，签名在本地完成。
 */
public class BtcTest {
    private static final String API = "https://mempool.space/testnet/api";
    private static final String FROM = "tb1qq4f5nda9kvzgamcn4xglgjvl2whkkmzlkgrvtg";
    private static final String TO = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx";
    /** 必须能推出 FROM。留空时跳过，避免测试套件广播一笔真交易。 */
    private static final String PRIVATE_KEY = "166931bd0fedcddc6c414978bd38994f79e5148756b2c3de72e8816bca3cea00";

    /**
     * 转出 10000 聪。只花已确认的 UTXO，找零退回 FROM。
     */
    @Test
    public void testBtc() {
        assumeTrue(PRIVATE_KEY.matches("(?i)(0x)?[0-9a-f]{64}"), "没有配置测试网私钥，跳过");
        NetworkParameters params = TestNet3Params.get();
        Context.propagate(new Context(params));
        String hexKey = PRIVATE_KEY.startsWith("0x") || PRIVATE_KEY.startsWith("0X") ? PRIVATE_KEY.substring(2) : PRIVATE_KEY;
        ECKey key = ECKey.fromPrivate(Utils.HEX.decode(hexKey), true);
        assertEquals(FROM, SegwitAddress.fromKey(params, key).toString(), "私钥推出的地址不是 fromAddress");

        Asset btc = new Asset();
        btc.symbol = "BTC";
        btc.kind = "native";
        btc.decimals = 8;
        long sats = 1_000;
        BtcClient client = new BtcClient("testnet", API, new Support.HttpJson());
        String balance = client.balance(FROM, btc);
        System.out.println("BTC balance: " + balance);

        PrepareResult quote = client.prepare(FROM, TO, Money.formatUnits(BigInteger.valueOf(sats), 8), BigInteger.valueOf(sats), btc);
        BtcPayload payload = (BtcPayload) quote.payload;
        System.out.println("fee sats: " + payload.feeSats + ", inputs: " + payload.inputs.size());

        String raw = sign(params, key, payload);
        String txid = client.broadcast(raw);
        System.out.println("https://mempool.space/testnet/tx/" + txid);
        assertTrue(txid.length() == 64);
    }

    /**
     * 把报价里的输入和输出签成一笔隔离见证交易。
     * P2WPKH 的签名不放在 scriptSig 里，而放在见证数据里。返回的十六进制可以直接 POST 到 /tx。
     *
     * @param params 测试网参数，用来识别 tb1 地址
     * @param key 付款私钥，必须能推出被花掉的那个地址
     * @param payload 未签名的输入和输出。输入要带上 UTXO 的聪数，隔离见证签名会把它算进去
     * @return 可广播的原始交易
     */
    private static String sign(NetworkParameters params, ECKey key, BtcPayload payload) {
        Transaction tx = new Transaction(params);
        // 先写下这笔钱要到哪里：收款输出，以及有找零时退回自己的输出。
        for (BtcOutput output : payload.outputs) {
            tx.addOutput(Coin.valueOf(output.value), Address.fromString(params, output.address));
        }
        // BIP143 用一段传统 P2PKH 脚本参与哈希。它不会写进交易，只用来表明“签的是这个公钥的隔离见证地址”。
        Script scriptCode = ScriptBuilder.createP2PKHOutputScript(key);
        for (BtcInput input : payload.inputs) {
            // 每个输入指向一笔已有交易的第 vout 个输出。金额必须带上，否则签出来的见证对不上。
            TransactionOutPoint outpoint = new TransactionOutPoint(params, input.vout, Sha256Hash.wrap(input.txid));
            tx.addInput(new TransactionInput(params, tx, new byte[0], outpoint, Coin.valueOf(input.value)));
        }
        for (int i = 0; i < payload.inputs.size(); i++) {
            TransactionInput input = tx.getInput(i);
            // SIGHASH_ALL 表示签名覆盖全部输入和输出。anyoneCanPay 为 false，别人不能再往这笔交易里加输入。
            var signature = tx.calculateWitnessSignature(i, key, scriptCode, input.getValue(), Transaction.SigHash.ALL, false);
            // 见证栈是两样东西：签名，以及压缩公钥。节点用公钥推出地址，再和被花的 UTXO 对比。
            input.setWitness(TransactionWitness.redeemP2WPKH(signature, key));
        }
        return Utils.HEX.encode(tx.bitcoinSerialize());
    }
}
