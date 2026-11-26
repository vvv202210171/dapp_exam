package aegis.txbuild;

import aegis.domain.Models.AppError;
import aegis.platform.Support;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SPL 代币转账。关联账户由钱包、Token 程序和 mint 推导，消息仍只有付款人一个签名。
 */
public final class Spl {
    /** 经典 Token 程序。USDT、USDC 用这一张，不用 Token-2022。 */
    public static final String TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    private static final String ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL";
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D = BigInteger.valueOf(-121665).mod(P).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);

    private Spl() {}

    /**
     * 推导某个钱包持有某种代币的关联账户。
     *
     * @param owner 钱包公钥的 Base58
     * @param mint 代币 mint 的 Base58
     * @return 关联账户地址
     */
    public static String associatedTokenAddress(String owner, String mint) {
        byte[] ownerKey = key(owner, "Solana 付款地址不正确");
        byte[] mintKey = key(mint, "代币合约不正确");
        byte[] tokenProgram = key(TOKEN_PROGRAM, "Token 程序地址不正确");
        byte[] program = key(ATA_PROGRAM, "关联账户程序地址不正确");
        return Support.base58Encode(findAddress(List.of(ownerKey, tokenProgram, mintKey), program));
    }

    /**
     * 组装 SPL TransferChecked。对方还没有关联账户时，前面加一条幂等创建指令。
     *
     * @param owner 付款钱包，也是手续费支付人
     * @param source 付款代币账户
     * @param destination 收款关联账户
     * @param destWallet 收款钱包。只有创建关联账户时才写进账户列表
     * @param mint 代币 mint
     * @param amount 最小单位
     * @param decimals 代币小数位，必须和 mint 一致
     * @param createAccount 为真时先创建收款关联账户
     * @param blockhash 最近区块哈希
     * @return 要签名的消息字节
     */
    public static byte[] buildTokenTransfer(String owner, String source, String destination, String destWallet, String mint, long amount, int decimals, boolean createAccount, String blockhash) {
        byte[] ownerKey = key(owner, "Solana 付款地址不正确");
        byte[] sourceKey = key(source, "付款代币账户不正确");
        byte[] destKey = key(destination, "收款代币账户不正确");
        byte[] mintKey = key(mint, "代币合约不正确");
        byte[] walletKey = createAccount ? key(destWallet, "Solana 收款地址不正确") : null;
        byte[] bh = blockhash(blockhash);
        if (Arrays.equals(ownerKey, walletKey) || Arrays.equals(sourceKey, destKey)) {
            throw AppError.of("INVALID_ARGUMENT", "不能把代币转给自己", "付款钱包和收款钱包要是两个地址。");
        }
        if (amount <= 0) throw AppError.of("INVALID_ARGUMENT", "金额超出范围", "");
        if (decimals < 0 || decimals > 255) throw AppError.of("INVALID_ARGUMENT", "代币小数位不正确", "");
        byte[] tokenProgram = key(TOKEN_PROGRAM, "Token 程序地址不正确");
        List<byte[]> accounts = new ArrayList<>();
        accounts.add(ownerKey);
        accounts.add(sourceKey);
        accounts.add(destKey);
        int transferProgram;
        int[] transferAccounts;
        byte[] buf;
        if (createAccount) {
            accounts.add(walletKey);
            accounts.add(mintKey);
            accounts.add(new byte[32]);
            accounts.add(tokenProgram);
            accounts.add(key(ATA_PROGRAM, "关联账户程序地址不正确"));
            buf = head(accounts, 5, bh, 2);
            buf = instruction(buf, 7, new int[] {0, 2, 3, 4, 5, 6}, new byte[] {1});
            transferProgram = 6;
            transferAccounts = new int[] {1, 4, 2, 0};
        } else {
            accounts.add(mintKey);
            accounts.add(tokenProgram);
            buf = head(accounts, 2, bh, 1);
            transferProgram = 4;
            transferAccounts = new int[] {1, 3, 2, 0};
        }
        byte[] data = new byte[10];
        data[0] = 12;
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong(1, amount);
        data[9] = (byte) decimals;
        return instruction(buf, transferProgram, transferAccounts, data);
    }

    /**
     * 写入一个签名者的消息头、账户、区块哈希和指令条数。
     *
     * @param accounts 按可写签名者、可写账户、只读账户排好的公钥
     * @param readonlyUnsigned 末尾只读且未签名的账户个数
     * @param blockhash 32 字节区块哈希
     * @param instructions 指令条数
     * @return 指令正文之前的字节
     */
    private static byte[] head(List<byte[]> accounts, int readonlyUnsigned, byte[] blockhash, int instructions) {
        byte[] buf = new byte[] {1, 0, (byte) readonlyUnsigned, (byte) accounts.size()};
        for (byte[] account : accounts) buf = append(buf, account);
        buf = append(buf, blockhash);
        return append(buf, new byte[] {(byte) instructions});
    }

    /**
     * 追加一条指令。账户数和数据长度都小于 128，compact-u16 就是一个字节。
     *
     * @param buf 已有字节
     * @param program 程序在账户列表中的下标
     * @param indexes 指令用到的账户下标
     * @param data 指令数据
     * @return 追加后的字节
     */
    private static byte[] instruction(byte[] buf, int program, int[] indexes, byte[] data) {
        buf = append(buf, new byte[] {(byte) program, (byte) indexes.length});
        byte[] idx = new byte[indexes.length];
        for (int i = 0; i < indexes.length; i++) idx[i] = (byte) indexes[i];
        buf = append(buf, idx);
        buf = append(buf, new byte[] {(byte) data.length});
        return append(buf, data);
    }

    /**
     * 找第一个不落在 ed25519 曲线上的程序派生地址。
     *
     * @param seeds 种子
     * @param program 程序公钥
     * @return 32 字节地址
     */
    private static byte[] findAddress(List<byte[]> seeds, byte[] program) {
        byte[] marker = "ProgramDerivedAddress".getBytes(StandardCharsets.US_ASCII);
        for (int bump = 255; bump >= 0; bump--) {
            byte[] material = new byte[0];
            for (byte[] seed : seeds) material = append(material, seed);
            material = append(material, new byte[] {(byte) bump}, program, marker);
            byte[] hash = Support.sha256(material);
            if (!onCurve(hash)) return hash;
        }
        throw AppError.of("CHAIN_ERROR", "推导不了关联账户", "检查钱包地址和代币合约。");
    }

    /**
     * 压缩点能否解开成曲线上的点。派生地址必须选解不开的那个哈希。
     *
     * @param compressed 32 字节小端 y，最高位是 x 的奇偶
     * @return 在曲线上时为真
     */
    static boolean onCurve(byte[] compressed) {
        if (compressed.length != 32) return false;
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) be[i] = compressed[31 - i];
        BigInteger y = new BigInteger(1, be);
        y = y.clearBit(255);
        if (y.compareTo(P) >= 0) return false;
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        if (v.signum() == 0) return false;
        BigInteger x2 = u.multiply(v.modInverse(P)).mod(P);
        return sqrt(x2) != null;
    }

    /**
     * 在 2^255-19 上开平方。p 除以 8 余 5。
     *
     * @param n 被开方数
     * @return 平方根，不存在则为空
     */
    private static BigInteger sqrt(BigInteger n) {
        BigInteger x = n.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (x.multiply(x).mod(P).equals(n)) return x;
        BigInteger i = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
        x = x.multiply(i).mod(P);
        if (x.multiply(x).mod(P).equals(n)) return x;
        return null;
    }

    /**
     * 解 32 字节公钥。
     *
     * @param text Base58
     * @param message 失败说明
     * @return 公钥
     */
    private static byte[] key(String text, String message) {
        try {
            byte[] raw = Support.base58Decode(text);
            if (raw.length == 32) return raw;
        } catch (Exception ignored) {
        }
        throw AppError.of("INVALID_ARGUMENT", message, "地址应是 32 字节公钥的 base58。");
    }

    /**
     * 解区块哈希。
     *
     * @param text Base58
     * @return 32 字节
     */
    private static byte[] blockhash(String text) {
        try {
            byte[] raw = Support.base58Decode(text);
            if (raw.length == 32) return raw;
        } catch (Exception ignored) {
        }
        throw AppError.of("CHAIN_ERROR", "blockhash 无法解码", "重新向节点获取最新 blockhash。");
    }

    /**
     * 按顺序拼接字节。
     *
     * @param a 前半段
     * @param parts 后续各段
     * @return 拼接结果
     */
    private static byte[] append(byte[] a, byte[]... parts) {
        int n = a.length;
        for (byte[] p : parts) n += p.length;
        byte[] out = Arrays.copyOf(a, n);
        int o = a.length;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }
}
