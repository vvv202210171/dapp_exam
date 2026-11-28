package aegis.txbuild;

import aegis.domain.Models.AppError;
import aegis.domain.Models.BtcInput;
import aegis.platform.Support;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装教学用的未签名交易：比特币选币、Solana 转账、ERC-20 和 TRC-20 calldata。
 */
public final class TxBuild {
    /** 工具类，不允许实例化。 */
    private TxBuild() {}

    /**
     * 选中的比特币输入和手续费。
     *
     * @param selected 选中的未花费输出
     * @param fee 手续费，单位聪
     * @param change 找零，单位聪
     */
    public record Coins(List<BtcInput> selected, long fee, long change) {}

    /**
     * 从大到小凑够金额加手续费的输入。
     *
     * @param utxos 可用输出
     * @param amount 转出金额，单位聪
     * @param feeRate 每虚拟字节的费率，单位聪
     * @return 选中的币、手续费和找零
     */
    public static Coins selectCoins(List<BtcInput> utxos, long amount, long feeRate) {
        if (amount <= 0) throw AppError.of("INVALID_ARGUMENT", "金额必须大于 0", "");
        if (feeRate <= 0) feeRate = 1;
        List<BtcInput> ordered = new ArrayList<>(utxos);
        ordered.sort((a, b) -> Long.compare(b.value, a.value));
        List<BtcInput> selected = new ArrayList<>();
        long total = 0;
        for (BtcInput u : ordered) {
            if (u.value <= 0) continue;
            selected.add(u);
            total += u.value;
            long[] fit = fitCoins(total, amount, feeRate, selected.size());
            if (fit != null) return new Coins(selected, fit[0], fit[1]);
        }
        throw AppError.of("INSUFFICIENT_FUNDS", "比特币余额不够支付金额和手续费", "确认已有足够的已确认 UTXO。刚收到的币要等确认后才能转出。");
    }

    /**
     * 按输入个数估算手续费后，看总额是否够付。
     *
     * @param total 已选输入总额
     * @param amount 转出金额
     * @param feeRate 费率
     * @param nIn 输入个数
     * @return 下标 0 是手续费，下标 1 是找零；不够时返回 null
     */
    private static long[] fitCoins(long total, long amount, long feeRate, int nIn) {
        if (total < amount) return null;
        //这里为什么是2个输出？因为有可能有找零输出，所以先算两个输出的手续费，如果够就返回找零；如果不够再算一个输出的手续费。
        long feeWithChange = estimateVSize(nIn, 2) * feeRate;
        // 546 是比特币最小找零金额，低于这个就不找零了。
        if (total >= amount + feeWithChange) {
            long change = total - amount - feeWithChange;
            if (change >= 546) return new long[] {feeWithChange, change};
        }
        long feeNoChange = estimateVSize(nIn, 1) * feeRate;
        if (total >= amount + feeNoChange) return new long[] {total - amount, 0};
        return null;
    }

    /**
     * 粗算隔离见证交易的虚拟字节。
     *
     * @param nIn 输入个数
     * @param nOut 输出个数
     * @return 虚拟字节
     */
    private static long estimateVSize(int nIn, int nOut) {
        long v = 11L + nIn * 68L + nOut * 31L;
        v += v / 10;
        return Math.max(v, 1);
    }

    /**
     * 组装一笔系统程序的 SOL 转账消息。
     *
     * @param from 付款公钥的 Base58
     * @param to 收款公钥的 Base58
     * @param lamports 转出数量
     * @param blockhash 最近区块哈希的 Base58
     * @return 要签名的消息字节
     */
    public static byte[] buildSolTransfer(String from, String to, long lamports, String blockhash) {
        byte[] fromKey = decode32(from, "Solana 付款地址不正确", "地址应是 32 字节公钥的 base58。");
        byte[] toKey = decode32(to, "Solana 收款地址不正确", "地址应是 32 字节公钥的 base58。");
        byte[] bh;
        try {
            bh = Support.base58Decode(blockhash);
        } catch (Exception e) {
            throw AppError.of("CHAIN_ERROR", "blockhash 无法解码", "重新向节点获取最新 blockhash。");
        }
        if (bh.length != 32) throw AppError.of("CHAIN_ERROR", "blockhash 无法解码", "重新向节点获取最新 blockhash。");
        if (Arrays.equals(fromKey, toKey)) {
            throw AppError.of("INVALID_ARGUMENT", "不能把 SOL 转给自己", "Solana 这笔教学交易把付款人和收款人当成两个账户。");
        }
        byte[] data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(lamports).array();
        byte[] buf = new byte[0];
        buf = append(buf, new byte[] {1, 0, 1});
        buf = append(buf, new byte[] {3});
        buf = append(buf, fromKey);
        buf = append(buf, toKey);
        buf = append(buf, new byte[32]);
        buf = append(buf, bh);
        buf = append(buf, new byte[] {1, 2, 2, 0, 1, (byte) data.length});
        buf = append(buf, data);
        return buf;
    }

    /**
     * 编码 ERC-20 transfer。
     *
     * @param to 收款地址
     * @param amount 最小单位数量
     * @return calldata
     */
    public static String erc20Transfer(String to, BigInteger amount) {
        return "0x" + Support.hex(append(new byte[] {(byte) 0xa9, 0x05, (byte) 0x9c, (byte) 0xbb}, abiAddress(to), abiUint(amount)));
    }

    /**
     * 编码 ERC-20 balanceOf。
     *
     * @param holder 持币地址
     * @return calldata
     */
    public static String erc20BalanceOf(String holder) {
        return "0x" + Support.hex(append(new byte[] {0x70, (byte) 0xa0, (byte) 0x82, 0x31}, abiAddress(holder)));
    }

    /**
     * 编码 TRC-20 transfer 的参数区，不含函数选择器。
     *
     * @param to TRON 地址
     * @param amount 最小单位数量
     * @return 参数十六进制
     */
    public static String trc20Parameter(String to, BigInteger amount) {
        byte[] payload;
        try {
            payload = Support.base58CheckDecode(to);
        } catch (Exception e) {
            throw AppError.of("INVALID_ARGUMENT", "TRON 收款地址不正确", "TRC20 参数里用的是去掉 0x41 版本字节后的 20 字节地址。");
        }
        if (payload.length != 21 || payload[0] != 0x41) {
            throw AppError.of("INVALID_ARGUMENT", "TRON 收款地址不正确", "TRC20 参数里用的是去掉 0x41 版本字节后的 20 字节地址。");
        }
        byte[] word = new byte[32];
        System.arraycopy(payload, 1, word, 12, 20);
        return Support.hex(append(word, abiUint(amount)));
    }

    /**
     * 用若干字段的 SHA-256 当作沙箱交易号。
     *
     * @param parts 参与哈希的字段
     * @return 交易号
     */
    public static String tronTxId(String... parts) {
        return Support.hex(Support.sha256(String.join("|", parts).getBytes()));
    }

    /**
     * 组装沙箱用的 TRON 交易 JSON。
     *
     * @param txId 交易号
     * @param contractType 合约类型
     * @return 交易对象
     */
    public static Map<String, Object> tronTransaction(String txId, String contractType) {
        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("contract", List.of(Map.of("type", contractType)));
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("visible", true);
        tx.put("txID", txId);
        tx.put("raw_data_hex", Support.hex(("sandbox:" + contractType).getBytes()));
        tx.put("raw_data", rawData);
        return tx;
    }

    /**
     * 把 0x 地址编码成 ABI 的 32 字节字。
     *
     * @param addr 地址
     * @return 32 字节
     */
    public static byte[] abiAddress(String addr) {
        String s = addr == null ? "" : addr.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        if (s.length() != 40) throw AppError.of("INVALID_ARGUMENT", "EVM 地址格式不正确", "地址应为 0x 开头的 40 位十六进制。");
        byte[] b;
        try {
            b = Support.unhex(s);
        } catch (Exception e) {
            throw AppError.of("INVALID_ARGUMENT", "EVM 地址格式不正确", "地址应为 0x 开头的 40 位十六进制。");
        }
        if (b.length != 20) throw AppError.of("INVALID_ARGUMENT", "EVM 地址格式不正确", "地址应为 0x 开头的 40 位十六进制。");
        byte[] out = new byte[32];
        System.arraycopy(b, 0, out, 12, 20);
        return out;
    }

    /**
     * 把无符号整数编码成 ABI 的 32 字节字。
     *
     * @param n 整数
     * @return 32 字节
     */
    public static byte[] abiUint(BigInteger n) {
        byte[] out = new byte[32];
        if (n == null || n.signum() <= 0) return out;
        byte[] b = n.toByteArray();
        if (b.length > 32 && b[0] == 0) b = Arrays.copyOfRange(b, 1, b.length);
        if (b.length > 32) throw new IllegalArgumentException("uint overflow");
        System.arraycopy(b, 0, out, 32 - b.length, b.length);
        return out;
    }

    /**
     * 把整数收成 long。超出范围时抛出业务错误。
     *
     * @param n 整数
     * @return long 值
     */
    public static long mustLong(BigInteger n) {
        try {
            return n.longValueExact();
        } catch (Exception e) {
            throw new IllegalArgumentException("金额超出范围");
        }
    }

    /**
     * 把 Base58 解成 32 字节公钥。
     *
     * @param s Base58
     * @param message 失败时的说明
     * @param hint 失败时的下一步
     * @return 32 字节
     */
    private static byte[] decode32(String s, String message, String hint) {
        try {
            byte[] raw = Support.base58Decode(s);
            if (raw.length == 32) return raw;
        } catch (Exception ignored) {
        }
        throw AppError.of("INVALID_ARGUMENT", message, hint);
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
