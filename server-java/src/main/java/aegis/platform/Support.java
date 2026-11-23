package aegis.platform;

import aegis.domain.Models.AppError;
import aegis.domain.Models.ErrBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * JSON、哈希、Base58、卡号加密和节点错误翻译。
 */
public final class Support {
    public static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /** 工具类，不允许实例化。 */
    private Support() {}

    /**
     * 生成 UUID。
     *
     * @return 编号
     */
    public static String newId() { return UUID.randomUUID().toString(); }

    /**
     * 计算 SHA-256。
     *
     * @param in 原文
     * @return 摘要
     */
    public static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 字节转小写十六进制，不带 0x。
     *
     * @param b 字节
     * @return 十六进制
     */
    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte v : b) s.append(String.format("%02x", v));
        return s.toString();
    }

    /**
     * 十六进制转字节。可以带 0x。
     *
     * @param s 十六进制
     * @return 字节
     */
    public static byte[] unhex(String s) {
        String t = s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
        if ((t.length() & 1) == 1) t = "0" + t;
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    /**
     * 十六进制数量转整数。空值当 0。
     *
     * @param s 十六进制
     * @return 整数
     */
    public static BigInteger hexToInt(String s) {
        if (s == null) return BigInteger.ZERO;
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        if (s.isEmpty()) return BigInteger.ZERO;
        try {
            return new BigInteger(s, 16);
        } catch (NumberFormatException e) {
            return BigInteger.ZERO;
        }
    }

    /**
     * 整数转以太坊数量十六进制，至少是 0x0。
     *
     * @param n 整数
     * @return 0x 开头的十六进制
     */
    public static String quantityHex(BigInteger n) {
        if (n == null || n.signum() == 0) return "0x0";
        return "0x" + n.toString(16);
    }

    /**
     * Base58 编码，保留前导零。
     *
     * @param input 原文
     * @return Base58
     */
    public static String base58Encode(byte[] input) {
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) zeros++;
        BigInteger n = new BigInteger(1, input);
        BigInteger base = BigInteger.valueOf(58);
        StringBuilder encoded = new StringBuilder();
        while (n.signum() > 0) {
            BigInteger[] div = n.divideAndRemainder(base);
            n = div[0];
            encoded.append(B58.charAt(div[1].intValue()));
        }
        for (int i = 0; i < zeros; i++) encoded.append('1');
        return encoded.reverse().toString();
    }

    /**
     * Base58 解码。
     *
     * @param s Base58 文本
     * @return 原文
     */
    public static byte[] base58Decode(String s) {
        if (s == null || s.isEmpty()) throw new IllegalArgumentException("empty base58");
        int zeros = 0;
        while (zeros < s.length() && s.charAt(zeros) == '1') zeros++;
        BigInteger n = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(58);
        for (int i = 0; i < s.length(); i++) {
            int idx = B58.indexOf(s.charAt(i));
            if (idx < 0) throw new IllegalArgumentException("invalid base58 character");
            n = n.multiply(base).add(BigInteger.valueOf(idx));
        }
        byte[] decoded = n.toByteArray();
        if (decoded.length > 0 && decoded[0] == 0) decoded = Arrays.copyOfRange(decoded, 1, decoded.length);
        byte[] out = new byte[zeros + decoded.length];
        System.arraycopy(decoded, 0, out, zeros, decoded.length);
        return out;
    }

    /**
     * Base58Check 解码并校验双 SHA-256。
     *
     * @param s Base58Check 文本
     * @return 去掉校验和后的载荷
     */
    public static byte[] base58CheckDecode(String s) {
        byte[] raw = base58Decode(s);
        if (raw.length < 5) throw new IllegalArgumentException("base58check too short");
        byte[] payload = Arrays.copyOf(raw, raw.length - 4);
        byte[] want = Arrays.copyOf(sha256(sha256(payload)), 4);
        byte[] got = Arrays.copyOfRange(raw, raw.length - 4, raw.length);
        if (!Arrays.equals(want, got)) throw new IllegalArgumentException("base58check checksum mismatch");
        return payload;
    }

    /**
     * 用 AES-GCM 加密。密文前 12 字节是随机数，再做 Base64。
     *
     * @param key 32 字节密钥
     * @param plain 明文
     * @return Base64 密文
     */
    public static String encrypt(byte[] key, byte[] plain) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 解密 AES-GCM 密文。
     *
     * @param key 32 字节密钥
     * @param encoded Base64 密文
     * @return 明文
     */
    public static byte[] decrypt(byte[] key, String encoded) {
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            if (raw.length < 12) throw new IllegalArgumentException("ciphertext too short");
            byte[] nonce = Arrays.copyOf(raw, 12);
            byte[] ct = Arrays.copyOfRange(raw, 12, raw.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return cipher.doFinal(ct);
        } catch (AppError e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("卡号无法解密");
        }
    }

    /**
     * 取一个密码学随机整数。
     *
     * @param n 上界，不包含
     * @return 0 到 n-1
     */
    public static int cryptoInt(int n) {
        return RANDOM.nextInt(n);
    }

    /**
     * 把节点或网络异常翻成业务错误。已经是业务错误则原样返回。
     *
     * @param err 异常
     * @return 业务错误
     */
    public static AppError explain(Throwable err) {
        if (err == null) return null;
        if (err instanceof AppError ae) return ae;
        if (err instanceof HttpTimeoutException || (err.getCause() instanceof HttpTimeoutException)) {
            return new AppError("RPC_TIMEOUT", "节点请求超时", "检查本机网络和 RPC 地址。公共节点在高峰会变慢，可以换备用节点，或先把单次超时从 8 秒往上调。", true);
        }
        String msg = err.getMessage() == null ? err.toString() : err.getMessage();
        String low = msg.toLowerCase();
        if (low.contains("insufficient funds") || low.contains("insufficient balance") || low.contains("insufficient lamports")) {
            return new AppError("INSUFFICIENT_FUNDS", "余额不够支付金额和手续费", "代币转账也要消耗链上的原生币。先确认 ETH/BNB/TRX/SOL 余额覆盖 gas，再看代币余额。", false);
        }
        if (low.contains("nonce too low")) {
            return new AppError("NONCE_TOO_LOW", "Nonce 过低", "这个 nonce 已经被用掉了。重新报价，拿到 pending nonce 后再签。如果上一笔还在内存池，等它确认或用更高 gas 替换。", true);
        }
        if (low.contains("replacement transaction underpriced") || low.contains("underpriced")) {
            return new AppError("UNDERPRICED", "Gas 价格不足以替换已有交易", "同一 nonce 已有一笔待打包交易。把 maxFeePerGas 提高至少 10% 后重新签名。", true);
        }
        if (low.contains("already known") || low.contains("already imported")) {
            return new AppError("ALREADY_KNOWN", "节点已经收过这笔交易", "不用重复广播。用交易哈希查询回执即可。", false);
        }
        if (low.contains("execution reverted") || low.contains("revert")) {
            return new AppError("REVERTED", "合约执行被回滚", "常见原因：代币余额不足、USDT 黑名单、没有 approve、收款地址拒绝接收代币。用 eth_call 重放同一笔 calldata 可以看 revert 原因。", false);
        }
        if (low.contains("429") || low.contains("rate limit") || low.contains("too many requests")) {
            return new AppError("RATE_LIMITED", "节点限流", "公共 RPC 对 IP 有频率限制。降低轮询，或换成自己的节点 / 带 API Key 的服务。", true);
        }
        if (low.contains("connection refused") || low.contains("no such host") || low.contains("tls") || low.contains("i/o timeout") || low.contains("network is unreachable") || low.contains("timed out")) {
            return new AppError("RPC_UNREACHABLE", "节点不可达", "核对 RPC URL、DNS 和代理。确认当前选的是主网还是测试网，两条网的节点不能混用。", true);
        }
        if (low.contains("account does not exist") || low.contains("account not found") || low.contains("does not exist")) {
            return new AppError("ACCOUNT_INACTIVE", "链上账户尚未激活或不存在", "TRON 地址要先收到一笔 TRX 才会被激活。Solana 新地址转账还要考虑租金豁免。", false);
        }
        if (low.contains("blockhash") && low.contains("not found")) {
            return new AppError("BLOCKHASH_EXPIRED", "Solana blockhash 已过期", "blockhash 大约 60–90 秒失效。重新报价后再签名，不要复用旧的未签名交易。", true);
        }
        return new AppError("CHAIN_ERROR", clip(msg, 300), "对照节点原文检查：地址、链 ID、金额精度、nonce、以及签名是否签在了正确的 payload 上。", true);
    }

    /**
     * 业务错误码对应的 HTTP 状态。
     *
     * @param err 异常
     * @return 状态码
     */
    public static int status(Throwable err) {
        AppError de = explain(err);
        if (de == null) return 500;
        return switch (de.code) {
            case "INVALID_ARGUMENT" -> 400;
            case "NOT_FOUND" -> 404;
            case "CONFLICT" -> 409;
            case "INSUFFICIENT_FUNDS", "CARD_INACTIVE", "ACCOUNT_INACTIVE" -> 422;
            case "RATE_LIMITED" -> 429;
            case "RPC_TIMEOUT", "RPC_UNREACHABLE", "RPC_UNAVAILABLE", "CHAIN_ERROR", "UNDERPRICED", "NONCE_TOO_LOW", "REVERTED", "ALREADY_KNOWN", "BLOCKHASH_EXPIRED" -> 502;
            default -> de.code == null || de.code.isEmpty() ? 500 : 400;
        };
    }

    /**
     * 取出给用户的下一步提示。没有 hint 时用错误说明。
     *
     * @param err 异常
     * @return 提示
     */
    public static String hintOf(Throwable err) {
        AppError de = explain(err);
        if (de == null) return "";
        return de.hint.isEmpty() ? de.getMessage() : de.hint;
    }

    /**
     * 截断过长文本。
     *
     * @param s 原文
     * @param n 最大长度
     * @return 截断后的文本
     */
    public static String clip(String s, int n) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() <= n) return s;
        return s.substring(0, n) + "…";
    }

    /**
     * 从 URL 取出主机名。
     *
     * @param raw URL
     * @return 主机名，解析失败时为空
     */
    public static String hostOf(String raw) {
        try {
            URI u = URI.create(raw);
            if (u.getHost() == null) return "configured-endpoint";
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return "configured-endpoint";
        }
    }

    /**
     * 超时 12 秒的 JSON 和文本 HTTP 客户端。
     */
    public static final class HttpJson {
        // Hardhat 不接受 Java 默认的 HTTP/2 升级请求，会直接回 400。本地结算节点走 HTTP/1.1。
        private final HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(12))
                .build();

        /**
         * POST JSON 并解析响应。
         *
         * @param endpoint 地址
         * @param body 请求对象
         * @return 响应 JSON
         */
        public JsonNode postJson(String endpoint, Object body) {
            return exchange("POST", endpoint, "application/json", write(body), true);
        }

        /**
         * GET JSON。
         *
         * @param endpoint 地址
         * @return 响应 JSON
         */
        public JsonNode getJson(String endpoint) {
            return exchange("GET", endpoint, null, null, true);
        }

        /**
         * POST 纯文本。
         *
         * @param endpoint 地址
         * @param body 文本
         * @return 响应文本
         */
        public String postText(String endpoint, String body) {
            JsonNode node = exchange("POST", endpoint, "text/plain", body, false);
            return node == null ? "" : node.asText();
        }

        /**
         * GET 纯文本。
         *
         * @param endpoint 地址
         * @return 响应文本
         */
        public String getText(String endpoint) {
            JsonNode node = exchange("GET", endpoint, null, null, false);
            return node == null ? "" : node.asText();
        }

        /**
         * 发送请求并处理超时、429 和 4xx。
         *
         * @param method GET 或 POST
         * @param endpoint 地址
         * @param contentType 内容类型
         * @param body 请求体，GET 可空
         * @param json 响应是否按 JSON 解析
         * @return JSON；纯文本请求时不使用返回值
         */
        private JsonNode exchange(String method, String endpoint, String contentType, String body, boolean json) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(12))
                        .header("User-Agent", "AegisLearningWallet/1.0")
                        .header("Accept", "application/json");
                if (contentType != null) b.header("Content-Type", contentType);
                if ("POST".equals(method)) b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
                else b.GET();
                HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String text = resp.body() == null ? "" : resp.body().trim();
                if (resp.statusCode() == 429) throw new RuntimeException("429 rate limit");
                if (resp.statusCode() == 404 && !json) throw new RuntimeException("404 not found");
                if (resp.statusCode() >= 400) throw new RuntimeException("http " + resp.statusCode() + ": " + clip(text.replaceAll("\\s+", " "), 240));
                if (!json) return JSON.getNodeFactory().textNode(text);
                if (text.isEmpty()) return JSON.nullNode();
                return JSON.readTree(text);
            } catch (HttpTimeoutException e) {
                throw new RuntimeException("i/o timeout", e);
            } catch (IOException e) {
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                throw new RuntimeException(m, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("i/o timeout", e);
            } catch (RuntimeException e) {
                throw e;
            }
        }

        /**
         * 把对象写成 JSON 文本。
         *
         * @param body 对象
         * @return JSON
         */
        private static String write(Object body) {
            try {
                return JSON.writeValueAsString(body == null ? Map.of() : body);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * 组装 JSON-RPC 2.0 请求体。
     *
     * @param method 方法名
     * @param params 参数
     * @return 请求体
     */
    public static Map<String, Object> rpcBody(String method, Object params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", 1);
        m.put("method", method);
        m.put("params", params);
        return m;
    }

    /**
     * 把异常转成可放进 JSON 的错误体。
     *
     * @param err 异常
     * @return 错误体
     */
    public static ErrBody explainBody(Throwable err) {
        AppError ae = explain(err);
        return ae == null ? null : ae.body();
    }
}
