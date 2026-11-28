package aegis.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import aegis.chain.live.SolClient;
import aegis.domain.Models.Asset;
import aegis.domain.Models.PrepareResult;
import aegis.domain.Models.SolPayload;
import aegis.domain.Money;
import aegis.platform.Support;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;

/**
 * Solana devnet 转账。报价和广播走 devnet 的 JSON-RPC，签名在本地完成。
 * SOL 走系统程序，USDC 走 SPL 代币账户。两笔都对 message 做 ed25519 签名。
 * 私钥和 ETH、TRON 那条 secp256k1 不是同一把。
 * 成交后的交易可以在 Solscan 的 devnet 上打开。
 */
public class SolanaTest {
    /** devnet 公共节点。主网是 https://api.mainnet-beta.solana.com，余额和交易不能混用。 */
    private static final String RPC = "https://api.devnet.solana.com";
    /**
     * Circle 在 devnet 上的 USDC mint，6 位小数。
     * 主网 USDC 是 EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v，mint 不同，不能拿到 devnet 用。
     */
    private static final String USDC = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU";
    /**
     * 32 字节 ed25519 种子的十六进制，64 个字符，不要带 0x。
     * 备份页里 Solana 那一行就是它。Phantom 有时导出 64 字节（128 个十六进制字符），这时只用前 32 字节。
     * 留空时跳过，避免测试套件向 devnet 广播一笔真交易。不要把真实私钥提交到仓库。
     */
    private static final String PRIVATE_KEY = "90639c4eda36326f0325d73e9a5a6e1605d2de73e1c5a5b008b2add7f4dd774c";
    /**
     * 收款地址。必须是另一个账户：这个项目把付款人和收款人写成交易里的两个账户，地址相同会在报价时被拒绝。
     * 新地址还要先有租金豁免的余额，否则节点不收这笔转账。
     */
    private static final String TO = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk";

    /**
     * 转出 0.001 SOL。付款地址要先在 devnet 领过 SOL。
     * 水龙头：https://faucet.solana.com/
     * blockhash 大约一分钟失效，签完会立刻广播。
     */
    @Test
    public void testSol() throws Exception {
        String hexKey = PRIVATE_KEY.startsWith("0x") || PRIVATE_KEY.startsWith("0X") ? PRIVATE_KEY.substring(2) : PRIVATE_KEY;
        assumeTrue(hexKey.matches("(?i)[0-9a-f]{64}|[0-9a-f]{128}"), "没有配置 devnet 私钥，跳过");
        if (hexKey.length() == 128) hexKey = hexKey.substring(0, 64);
        byte[] seed = Support.unhex(hexKey);
        String from = solanaAddress(seed);
        System.out.println("Solana address: " + from);
        assumeTrue(!TO.isBlank(), "填写另一个 devnet 收款地址后再跑");
        assumeTrue(!TO.equals(from), "收款地址要和付款地址不同");

        Asset sol = new Asset();
        sol.symbol = "SOL";
        sol.kind = "native";
        sol.decimals = 9;
        // 1 SOL = 1_000_000_000 lamports。lamports 是最小单位。这里转 0.001 SOL。
        // 手续费另算，固定大约 5000 lamports，从付款账户扣，不从这 0.001 里面扣。
        long lamports = 1_000_000;
        SolClient client = new SolClient("testnet", RPC, new Support.HttpJson());
        System.out.println("SOL balance: " + client.balance(from, sol));

        // 节点只提供最近的 blockhash。转账消息在本地组装：付款账户、收款账户、系统程序。
        PrepareResult quote = client.prepare(from, TO, Money.formatUnits(BigInteger.valueOf(lamports), 9), BigInteger.valueOf(lamports), sol);
        SolPayload payload = (SolPayload) quote.payload;
        System.out.println("fee: " + quote.fee.amount + " " + quote.fee.symbol + ", blockhash: " + payload.blockhash);

        String signed = sign(seed, payload);
        String txid = client.broadcast(signed);
        // Solana 的交易号就是第一把签名的 Base58，不是对交易再做一次哈希。
        byte[] raw = Base64.getDecoder().decode(signed);
        assertEquals(Support.base58Encode(Arrays.copyOfRange(raw, 1, 65)), txid);
        System.out.println("https://solscan.io/tx/" + txid + "?cluster=devnet");
        assertTrue(txid.length() >= 80);
        System.out.println("SOL balance after: " + client.balance(from, sol));
    }

    /**
     * 转出 1 个 devnet USDC。币记在 SPL 关联账户里，不进钱包地址本身。
     * 手续费用 SOL 支付。对方还没有这个币的关联账户时，这笔交易会先创建它，大约再花 0.00204 SOL。
     * 付款地址要同时有 devnet USDC 和少量 SOL。USDC 可以从 https://faucet.circle.com/ 领，网络选 Solana Devnet。
     */
    @Test
    public void testUsdc() throws Exception {
        String hexKey = PRIVATE_KEY.startsWith("0x") || PRIVATE_KEY.startsWith("0X") ? PRIVATE_KEY.substring(2) : PRIVATE_KEY;
        assumeTrue(hexKey.matches("(?i)[0-9a-f]{64}|[0-9a-f]{128}"), "没有配置 devnet 私钥，跳过");
        if (hexKey.length() == 128) hexKey = hexKey.substring(0, 64);
        byte[] seed = Support.unhex(hexKey);
        String from = solanaAddress(seed);
        System.out.println("Solana address: " + from);
        assumeTrue(!TO.isBlank(), "填写另一个 devnet 收款地址后再跑");
        assumeTrue(!TO.equals(from), "收款地址要和付款地址不同");

        Asset usdc = new Asset();
        usdc.symbol = "USDC";
        usdc.kind = "token";
        usdc.decimals = 6;
        usdc.contract = USDC;
        // 6 位小数：1 USDC = 1_000_000 个最小单位。这个单位不是 lamport，lamport 只计量 SOL。
        BigInteger units = BigInteger.valueOf(1_000_000);
        SolClient client = new SolClient("testnet", RPC, new Support.HttpJson());
        System.out.println("SOL balance: " + client.balance(from, solAsset()));
        System.out.println("USDC balance: " + client.balance(from, usdc));

        // 节点只提供 blockhash 和代币账户。转账消息在本地组装，调用的是 TransferChecked。
        // 签名步骤和 SOL 相同：对 message 做 ed25519，不需要再签 mint 或金额。
        PrepareResult quote = client.prepare(from, TO, Money.formatUnits(units, 6), units, usdc);
        SolPayload payload = (SolPayload) quote.payload;
        System.out.println("fee estimate: " + quote.fee.amount + " " + quote.fee.symbol + ", blockhash: " + payload.blockhash);

        String signed = sign(seed, payload);
        String txid = client.broadcast(signed);
        byte[] raw = Base64.getDecoder().decode(signed);
        assertEquals(Support.base58Encode(Arrays.copyOfRange(raw, 1, 65)), txid);
        System.out.println("https://solscan.io/tx/" + txid + "?cluster=devnet");
        assertTrue(txid.length() >= 80);
        System.out.println("USDC balance after: " + client.balance(from, usdc));
    }

    /**
     * 查手续费用的原生 SOL。USDC 转账的手续费从 SOL 余额扣。
     *
     * @return SOL 资产
     */
    private static Asset solAsset() {
        Asset sol = new Asset();
        sol.symbol = "SOL";
        sol.kind = "native";
        sol.decimals = 9;
        return sol;
    }

    /**
     * 对报价里的 message 做 ed25519 签名，再拼成节点能接收的交易。
     * 签名盖在 message 前面。第一个字节 1 是签名个数；教学交易只有付款人一把签名，个数小于 128，所以这一个字节就够了。
     * 返回的是整笔交易的 Base64，可以直接交给 sendTransaction。
     *
     * @param seed 32 字节种子，必须能推出 message 里的付款账户
     * @param payload 未签名消息。message 是 Base64，里面已经写好了 blockhash 和转账金额
     * @return 可广播的交易
     */
    private static String sign(byte[] seed, SolPayload payload) {
        byte[] message = Base64.getDecoder().decode(payload.message);
        Ed25519PrivateKeyParameters key = new Ed25519PrivateKeyParameters(seed, 0);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, key);
        // 签的是 message 原文。ed25519 自己会做哈希，这里不要先 SHA-256。
        signer.update(message, 0, message.length);
        byte[] signature = signer.generateSignature();
        assertEquals(64, signature.length);
        byte[] out = new byte[1 + signature.length + message.length];
        out[0] = 1;
        System.arraycopy(signature, 0, out, 1, signature.length);
        System.arraycopy(message, 0, out, 1 + signature.length, message.length);
        return Base64.getEncoder().encodeToString(out);
    }

    /**
     * 由种子推出地址。地址就是 ed25519 公钥的 Base58，没有单独的版本字节。
     *
     * @param seed 32 字节种子
     * @return 付款地址
     */
    private static String solanaAddress(byte[] seed) {
        byte[] publicKey = new Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().getEncoded();
        return Support.base58Encode(publicKey);
    }
}
