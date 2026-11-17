package aegis.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 金额换算。链上最小单位用整数，展示用十进制字符串，不使用浮点数。
 */
public final class Money {
    /** 工具类，不允许实例化。 */
    private Money() {}

    /**
     * 把人类可读金额换成最小单位。
     *
     * @param amount 例如 25.50
     * @param decimals 小数位数，ETH 是 18，USDC 是 6
     * @return 最小单位
     */
    public static BigInteger parseUnits(String amount, int decimals) {
        amount = amount == null ? "" : amount.trim();
        if (amount.isEmpty() || amount.startsWith("+") || amount.startsWith("-")) {
            throw new IllegalArgumentException("金额格式不正确");
        }
        if (decimals < 0 || decimals > 36) throw new IllegalArgumentException("精度不支持");
        String[] parts = amount.split("\\.", -1);
        if (parts.length > 2) throw new IllegalArgumentException("金额格式不正确");
        String whole = parts[0].isEmpty() ? "0" : parts[0];
        String frac = parts.length == 2 ? parts[1] : "";
        if (!digits(whole) || !digits(frac)) throw new IllegalArgumentException("金额格式不正确");
        if (frac.length() > decimals) throw new IllegalArgumentException("最多支持 " + decimals + " 位小数");
        frac = frac + "0".repeat(decimals - frac.length());
        String combined = (whole + frac).replaceFirst("^0+", "");
        if (combined.isEmpty()) return BigInteger.ZERO;
        if (combined.length() > 78) throw new IllegalArgumentException("金额过大");
        return new BigInteger(combined);
    }

    /**
     * 把最小单位格式化成去掉多余尾零的十进制字符串。
     *
     * @param value 最小单位
     * @param decimals 小数位数
     * @return 人类可读金额
     */
    public static String formatUnits(BigInteger value, int decimals) {
        if (value == null || value.signum() == 0) return "0";
        boolean neg = value.signum() < 0;
        String s = value.abs().toString();
        if (decimals == 0) return neg ? "-" + s : s;
        if (s.length() <= decimals) s = "0".repeat(decimals - s.length() + 1) + s;
        String whole = s.substring(0, s.length() - decimals);
        String frac = s.substring(s.length() - decimals).replaceFirst("0+$", "");
        String out = frac.isEmpty() ? whole : whole + "." + frac;
        return neg ? "-" + out : out;
    }

    /**
     * 把美元文本解析成分。最多两位小数。
     *
     * @param amount 例如 22.30
     * @return 分
     */
    public static long parseCents(String amount) {
        BigInteger n;
        try {
            n = parseUnits(amount, 2);
        } catch (IllegalArgumentException e) {
            throw e;
        }
        if (n.bitLength() > 63) throw new IllegalArgumentException("金额过大");
        if (n.signum() <= 0) throw new IllegalArgumentException("金额必须大于 0");
        return n.longValueExact();
    }

    /**
     * 把分格式化成两位美元。
     *
     * @param cents 分
     * @return 例如 25.50
     */
    public static String formatCents(long cents) {
        String sign = "";
        if (cents < 0) {
            sign = "-";
            cents = -cents;
        }
        return sign + (cents / 100) + "." + String.format("%02d", cents % 100);
    }

    /**
     * 数量乘单价，得到美元，保留两位小数。
     *
     * @param amount 资产数量
     * @param price 单价，美元
     * @return 美元文本
     */
    public static String mulUsd(String amount, String price) {
        BigDecimal a = new BigDecimal(amount.trim());
        BigDecimal p = new BigDecimal(price.trim());
        return a.multiply(p).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 把若干美元文本相加。
     *
     * @param values 美元文本
     * @return 合计
     */
    public static String sumUsd(Iterable<String> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (String v : values) {
            try {
                total = total.add(new BigDecimal(v));
            } catch (Exception ignored) {
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 10 的 n 次方。
     *
     * @param n 指数，不能为负
     * @return 10^n
     */
    public static BigInteger pow10(int n) {
        return BigInteger.TEN.pow(n);
    }

    /**
     * 判断字符串是否只含数字。空字符串算通过。
     *
     * @param s 文本
     * @return 全是数字时为 true
     */
    private static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
