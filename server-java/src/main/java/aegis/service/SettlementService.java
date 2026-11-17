package aegis.service;

import aegis.dao.AccountDao;
import aegis.dao.SettlementDao;
import aegis.domain.Models.AppError;
import aegis.domain.Models.Cents;
import aegis.domain.Models.EvmPayload;
import aegis.domain.Models.Fee;
import aegis.domain.Money;
import aegis.platform.Support;
import aegis.txbuild.TxBuild;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡账本和本地结算合约之间的桥。用户交易由浏览器签名，运营方交易由节点里解锁的账户发送。
 */
@Service
public class SettlementService {
    private static final long LOCAL_CHAIN = 31337;
    private final AccountDao accounts;
    private final SettlementDao settlements;
    private final Support.HttpJson http;

    /**
     * @param accounts 账户数据访问
     * @param settlements 消费和结算记录
     * @param http JSON-RPC 客户端
     */
    public SettlementService(AccountDao accounts, SettlementDao settlements, Support.HttpJson http) {
        this.accounts = accounts;
        this.settlements = settlements;
        this.http = http;
    }

    /**
     * 查询链上余额和未结算消费。节点不可用时仍返回流水。
     *
     * @param accountId 账户编号
     * @return 结算页视图
     */
    public Map<String, Object> status(String accountId) {
        accounts.get(accountId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("online", false);
        view.put("spends", spends(accountId));
        try { view.put("user", userAddress(accountId)); } catch (Exception ignored) {}
        try {
            Session sess = open();
            view.put("online", true);
            fill(view, sess.dep);
            if (!view.containsKey("user")) {
                view.put("hint", "这个账户还没有以太坊地址。重新解锁钱包，让地址同步到服务器。");
                return view;
            }
            String user = String.valueOf(view.get("user"));
            view.put("eth", Money.formatUnits(sess.rpc.nativeBalance(user), 18));
            view.put("walletUsdc", Money.formatUnits(sess.rpc.callUint(sess.dep.usdc, Abi.balanceOf(user)), 6));
            view.put("allowance", Money.formatUnits(sess.rpc.callUint(sess.dep.usdc, Abi.allowance(user, sess.dep.settlement)), 6));
            view.put("available", Money.formatUnits(sess.rpc.callUint(sess.dep.settlement, Abi.available(user, sess.dep.usdc)), 6));
            return view;
        } catch (Exception e) {
            view.put("hint", Support.hintOf(e));
            try { fill(view, loadDeployment()); } catch (Exception ignored) {}
            return view;
        }
    }

    /**
     * 为用户的授权、存入或提现构造未签名交易。
     *
     * @param accountId 账户编号
     * @param action approve、deposit 或 withdraw
     * @param amount USDC 数量，最多 6 位小数
     * @return 动作、金额、载荷和手续费
     */
    public Map<String, Object> prepare(String accountId, String action, String amount) {
        Session sess = open();
        String user = userAddress(accountId);
        BigInteger units;
        try { units = Money.parseUnits(amount, 6); }
        catch (IllegalArgumentException e) { throw AppError.of("INVALID_ARGUMENT", "金额不正确", "按 USDC 填写，最多 6 位小数。例如 25 表示 25 USDC。"); }
        if (units.signum() <= 0) throw AppError.of("INVALID_ARGUMENT", "金额不正确", "按 USDC 填写，最多 6 位小数。例如 25 表示 25 USDC。");
        String to;
        String data;
        switch (action == null ? "" : action) {
            case "approve" -> { to = sess.dep.usdc; data = Abi.approve(sess.dep.settlement, units); }
            case "deposit" -> { to = sess.dep.settlement; data = Abi.deposit(sess.dep.usdc, units); }
            case "withdraw" -> { to = sess.dep.settlement; data = Abi.withdraw(sess.dep.usdc, units); }
            default -> throw AppError.of("INVALID_ARGUMENT", "不支持这个动作", "用户只能授权、存入或提现。结算由服务端的运营方账户发送。");
        }
        Quoted quoted = sess.rpc.quote(user, to, data);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("amount", Money.formatUnits(units, 6));
        out.put("payload", quoted.payload);
        out.put("fee", quoted.fee);
        out.put("warnings", List.of("签名在浏览器完成，服务器看不到私钥。", "chainId 是 31337。这笔交易签给本地链，不能在主网重放。"));
        return out;
    }

    /**
     * 广播用户签好的原始交易，并等待最多 8 秒回执。
     *
     * @param accountId 账户编号
     * @param signed 已签名交易的十六进制
     * @return hash 和 status
     */
    public Map<String, String> broadcast(String accountId, String signed) {
        accounts.get(accountId);
        Session sess = open();
        if (signed == null || signed.isBlank()) throw AppError.of("INVALID_ARGUMENT", "缺少已签名交易", "先向结算接口报价，再用钱包里的以太坊私钥签名。");
        String hash = sess.rpc.sendRaw(signed);
        return Map.of("hash", hash, "status", sess.rpc.wait(hash));
    }

    /**
     * 从运营方账户转 1 ETH，并调用 MockUSDC 铸造 100 枚。只用于本地链。
     *
     * @param accountId 账户编号
     * @return 两笔交易哈希以及到账数量
     */
    public Map<String, String> faucet(String accountId) {
        Session sess = open();
        String user = userAddress(accountId);
        BigInteger oneEth = new BigInteger("1000000000000000000");
        String ethTx;
        String usdcTx;
        try {
            ethTx = sess.rpc.sendFrom(sess.dep.operator, user, Support.quantityHex(oneEth), "0x");
        } catch (RuntimeException e) {
            throw explainOperator(e);
        }
        sess.rpc.wait(ethTx);
        try {
            usdcTx = sess.rpc.sendFrom(sess.dep.operator, sess.dep.usdc, "", Abi.mint(user, BigInteger.valueOf(100_000_000)));
        } catch (RuntimeException e) {
            throw explainOperator(e);
        }
        sess.rpc.wait(usdcTx);
        return Map.of("ethTx", ethTx, "usdcTx", usdcTx, "eth", "1", "usdc", "100");
    }

    /**
     * 确认这笔交易是该账户向结算合约存入的等额 USDC。通过才允许给卡入账。
     *
     * @param accountId 账户编号
     * @param amount 要入账的美元，最多两位小数
     * @param txHash 存入交易哈希
     */
    public void confirmDeposit(String accountId, String amount, String txHash) {
        long cents;
        try { cents = Money.parseCents(amount); }
        catch (IllegalArgumentException e) { throw AppError.of("INVALID_ARGUMENT", "金额不正确", "金额用美元，最多两位小数，例如 22.30。"); }
        String hash = Abi.normalizeTxHash(txHash);
        String user = userAddress(accountId);
        Session sess = open();
        String status = sess.rpc.wait(hash);
        if (!"confirmed".equals(status)) throw AppError.of("RPC_TIMEOUT", "存入交易还没有成功回执", "在小狐狸里确认交易已经上链，再给卡入账。");
        Tx tx = sess.rpc.transaction(hash);
        if (!tx.from.equalsIgnoreCase(user)) throw AppError.of("INVALID_ARGUMENT", "这笔存入不是当前账户发出的", "小狐狸要选这个钱包的以太坊地址。");
        if (!tx.to.equalsIgnoreCase(sess.dep.settlement)) throw AppError.of("INVALID_ARGUMENT", "这笔交易没有打到结算合约", "只有 deposit 进结算合约才能给卡入账。");
        Deposit parsed = Abi.parseDeposit(tx.input);
        if (!parsed.token.equalsIgnoreCase(sess.dep.usdc)) throw AppError.of("INVALID_ARGUMENT", "存入的不是结算用的 USDC", "");
        if (parsed.amount.compareTo(Abi.centsToUnits(cents)) != 0) throw AppError.of("INVALID_ARGUMENT", "链上存入金额和要入账的美元不一致", "卡加多少钱，合约里就要存入多少 USDC。");
    }

    /**
     * 按一笔卡消费调用 settle。同一账单引用不能结算两次。
     *
     * @param accountId 账户编号
     * @param entryId 消费流水编号
     * @return 结算哈希和状态
     */
    public Map<String, String> settle(String accountId, String entryId) {
        Session sess = open();
        String user = userAddress(accountId);
        SettlementDao.SpendRecord row = settlements.spendForAccount(accountId, entryId);
        if (row.txHash != null && !row.txHash.isEmpty()) return Map.of("hash", row.txHash, "status", "confirmed");
        BigInteger units = Abi.centsToUnits(row.entry.amount.value);
        if (units.signum() <= 0) throw AppError.of("INVALID_ARGUMENT", "这笔消费金额不正确", "");
        BigInteger available = sess.rpc.callUint(sess.dep.settlement, Abi.available(user, sess.dep.usdc));
        if (available.compareTo(units) < 0) throw AppError.of("INSUFFICIENT_FUNDS", "链上可用余额不够结算这笔消费", "先在结算页把 USDC 存进合约。存入额要盖住还没结算的消费。");
        String data = Abi.settle(user, sess.dep.usdc, units, Abi.billRef(entryId));
        String hash;
        try {
            hash = sess.rpc.sendFrom(sess.dep.operator, sess.dep.settlement, "", data);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("DuplicateRef")) {
                settlements.put(entryId, accountId, "already-on-chain", units.toString());
                return Map.of("hash", "already-on-chain", "status", "confirmed");
            }
            throw explainOperator(e);
        }
        String status = sess.rpc.wait(hash);
        if ("confirmed".equals(status)) settlements.put(entryId, accountId, hash, units.toString());
        return Map.of("hash", hash, "status", status);
    }

    /**
     * 把消费流水转成结算页要的列表。
     *
     * @param accountId 账户编号
     * @return 消费项
     */
    private List<Map<String, Object>> spends(String accountId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SettlementDao.SpendRecord row : settlements.spends(accountId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("entryId", row.entry.id);
            item.put("cardId", row.entry.cardId);
            item.put("last4", row.last4);
            if (row.entry.merchant != null && !row.entry.merchant.isEmpty()) item.put("merchant", row.entry.merchant);
            item.put("amount", new Cents(row.entry.amount.value));
            item.put("units", Money.formatUnits(Abi.centsToUnits(row.entry.amount.value), 6));
            item.put("settled", row.txHash != null && !row.txHash.isEmpty());
            if (row.txHash != null && !row.txHash.isEmpty()) item.put("txHash", row.txHash);
            item.put("createdAt", row.entry.createdAt);
            out.add(item);
        }
        return out;
    }

    /**
     * 读取部署文件并确认节点是本机 chainId 31337，且合约代码还在。
     *
     * @return 部署信息和 RPC
     */
    private Session open() {
        Deployment dep = loadDeployment();
        if (dep.chainId != LOCAL_CHAIN) throw AppError.of("INVALID_ARGUMENT", "结算部署文件里的 chainId 不是 31337", "只连接本地 Hardhat。不要把这份配置指到主网或测试网。");
        if (!loopback(dep.rpc)) throw AppError.of("INVALID_ARGUMENT", "结算节点必须在本机", "只接受 127.0.0.1 或 localhost。运营方交易走节点里解锁的账户，不能指到公网 RPC。");
        Rpc rpc = new Rpc(dep.rpc, http);
        BigInteger id;
        try { id = rpc.chainId(); }
        catch (RuntimeException e) { throw AppError.of("RPC_UNREACHABLE", "本地结算链没有打开", "在 contracts 目录执行 npx hardhat node，另开一个终端执行 npm run deploy，然后刷新。"); }
        if (id.compareTo(BigInteger.valueOf(LOCAL_CHAIN)) != 0) throw AppError.of("INVALID_ARGUMENT", "节点 chainId 不是 31337", "结算通道只接受本地 Hardhat，避免运营方交易被发到公网。");
        String code = rpc.code(dep.settlement);
        if (code == null || code.isEmpty() || "0x".equals(code) || "0x0".equals(code)) throw AppError.of("NOT_FOUND", "结算合约不在部署文件写的地址上", "Hardhat 重启后链是空的。在 contracts 目录重新执行 npm run deploy。");
        return new Session(dep, rpc);
    }

    /**
     * 取出账户的以太坊地址。
     *
     * @param accountId 账户编号
     * @return 0x 地址
     */
    private String userAddress(String accountId) {
        for (var addr : accounts.get(accountId).addresses) {
            if ("ethereum".equals(addr.chain) && addr.address != null && !addr.address.isBlank()) return addr.address;
        }
        throw AppError.of("INVALID_ARGUMENT", "这个账户没有以太坊地址", "重新解锁钱包。同步接口会把派生地址登记到服务器。");
    }

    /**
     * 把部署地址填进视图。
     *
     * @param view 结算页视图
     * @param dep 部署信息
     */
    private static void fill(Map<String, Object> view, Deployment dep) {
        view.put("chainId", Long.toString(dep.chainId));
        view.put("rpc", dep.rpc);
        view.put("settlement", dep.settlement);
        view.put("usdc", dep.usdc);
        view.put("operator", dep.operator);
    }

    /**
     * 读取本地部署文件。环境变量 SETTLEMENT_FILE 和 SETTLEMENT_RPC 可以覆盖路径和节点。
     *
     * @return 部署信息
     */
    static Deployment loadDeployment() {
        String path = System.getenv("SETTLEMENT_FILE");
        if (path == null || path.isBlank()) {
            Path chosen = Path.of("..", "contracts", "deployments", "local.json");
            for (Path candidate : List.of(chosen, Path.of("contracts", "deployments", "local.json"))) {
                if (Files.exists(candidate)) { chosen = candidate; break; }
            }
            path = chosen.toString();
        }
        try {
            Deployment dep = Support.JSON.readValue(Files.readString(Path.of(path)), Deployment.class);
            String rpc = System.getenv("SETTLEMENT_RPC");
            if (rpc != null && !rpc.isBlank()) dep.rpc = rpc.trim();
            if (dep.rpc == null || dep.settlement == null || dep.usdc == null || dep.operator == null || dep.rpc.isBlank() || dep.settlement.isBlank() || dep.usdc.isBlank() || dep.operator.isBlank()) {
                throw AppError.of("INVALID_ARGUMENT", "部署文件缺少地址", "重新执行 npm run deploy。");
            }
            return dep;
        } catch (AppError e) {
            throw e;
        } catch (Exception e) {
            if (!Files.exists(Path.of(path))) throw AppError.of("NOT_FOUND", "还没有结算合约的部署文件", "在 contracts 目录先执行 npx hardhat node，再执行 npm run deploy。");
            throw AppError.of("INVALID_ARGUMENT", "部署文件不是合法 JSON", "重新执行 npm run deploy。");
        }
    }

    /**
     * 判断 RPC 主机是不是本机。
     *
     * @param raw RPC URL
     * @return 是 127.0.0.1、localhost 或 ::1 时为 true
     */
    private static boolean loopback(String raw) {
        try {
            String host = URI.create(raw).getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把节点未解锁运营方账户的错误换成可操作的说明。
     *
     * @param err 节点返回的异常
     * @return 业务异常或原异常
     */
    private static RuntimeException explainOperator(RuntimeException err) {
        String low = err.getMessage() == null ? "" : err.getMessage().toLowerCase();
        if (low.contains("unknown account") || low.contains("locked")) {
            return AppError.of("CHAIN_ERROR", "节点没有解锁运营方账户", "本地用 npx hardhat node，部署账户会自动解锁。生产环境应换成 KMS 签名，而不是把运营方私钥放进 API 进程。");
        }
        return err;
    }

    /**
     * 本地部署文件里的合约地址。
     */
    public static final class Deployment {
        public long chainId;
        public String rpc;
        public String settlement;
        public String usdc;
        public String operator;
    }

    /**
     * 一次已连上的本地链会话。
     *
     * @param dep 部署信息
     * @param rpc 节点客户端
     */
    private record Session(Deployment dep, Rpc rpc) {}

    /**
     * 报价结果。
     *
     * @param payload 未签名交易
     * @param fee 手续费
     */
    private record Quoted(EvmPayload payload, Fee fee) {}

    /**
     * 链上交易的发送方、接收方和输入数据。
     *
     * @param from 发送地址
     * @param to 接收地址
     * @param input calldata
     */
    private record Tx(String from, String to, String input) {}

    /**
     * 从 deposit calldata 解出的代币和数量。
     *
     * @param token 代币地址
     * @param amount 最小单位数量
     */
    public record Deposit(String token, BigInteger amount) {}

    /**
     * 结算合约和 MockUSDC 的 calldata 编解码。
     */
    public static final class Abi {
        private static final String APPROVE = "095ea7b3";
        private static final String BALANCE = "70a08231";
        private static final String ALLOWANCE = "dd62ed3e";
        private static final String MINT = "40c10f19";
        private static final String DEPOSIT = "47e7ef24";
        private static final String WITHDRAW = "f3fef3a3";
        private static final String SETTLE = "d4729c72";
        private static final String AVAILABLE = "bbe430de";

        /**
         * 1 分等于 10000 个 USDC 最小单位，因为 USDC 是 6 位小数。
         *
         * @param cents 金额，单位分
         * @return USDC 最小单位
         */
        public static BigInteger centsToUnits(long cents) { return BigInteger.valueOf(cents).multiply(BigInteger.valueOf(10_000)); }

        /**
         * 用流水编号的 SHA-256 作为账单引用，防止同一笔消费结算两次。
         *
         * @param entryId 流水编号
         * @return 0x 开头的 32 字节
         */
        public static String billRef(String entryId) { return "0x" + Support.hex(Support.sha256(entryId.getBytes())); }

        /**
         * 编码 ERC-20 approve。
         *
         * @param spender 被授权地址，这里是结算合约
         * @param amount 授权数量
         * @return calldata
         */
        public static String approve(String spender, BigInteger amount) { return pack(APPROVE, TxBuild.abiAddress(spender), TxBuild.abiUint(amount)); }

        /**
         * 编码 balanceOf。
         *
         * @param holder 持币地址
         * @return calldata
         */
        public static String balanceOf(String holder) { return pack(BALANCE, TxBuild.abiAddress(holder)); }

        /**
         * 编码 allowance。
         *
         * @param owner 持币地址
         * @param spender 被授权地址
         * @return calldata
         */
        public static String allowance(String owner, String spender) { return pack(ALLOWANCE, TxBuild.abiAddress(owner), TxBuild.abiAddress(spender)); }

        /**
         * 编码 MockUSDC.mint。
         *
         * @param to 接收地址
         * @param amount 铸造数量
         * @return calldata
         */
        public static String mint(String to, BigInteger amount) { return pack(MINT, TxBuild.abiAddress(to), TxBuild.abiUint(amount)); }

        /**
         * 编码结算合约 deposit。
         *
         * @param token USDC 地址
         * @param amount 存入数量
         * @return calldata
         */
        public static String deposit(String token, BigInteger amount) { return pack(DEPOSIT, TxBuild.abiAddress(token), TxBuild.abiUint(amount)); }

        /**
         * 编码结算合约 withdraw。
         *
         * @param token USDC 地址
         * @param amount 提走数量
         * @return calldata
         */
        public static String withdraw(String token, BigInteger amount) { return pack(WITHDRAW, TxBuild.abiAddress(token), TxBuild.abiUint(amount)); }

        /**
         * 编码 available 查询。
         *
         * @param user 用户地址
         * @param token USDC 地址
         * @return calldata
         */
        public static String available(String user, String token) { return pack(AVAILABLE, TxBuild.abiAddress(user), TxBuild.abiAddress(token)); }

        /**
         * 编码 settle。
         *
         * @param user 用户地址
         * @param token USDC 地址
         * @param amount 结算数量
         * @param ref 32 字节账单引用
         * @return calldata
         */
        public static String settle(String user, String token, BigInteger amount, String ref) {
            return pack(SETTLE, TxBuild.abiAddress(user), TxBuild.abiAddress(token), TxBuild.abiUint(amount), bytes32(ref));
        }

        /**
         * 要求交易哈希是 0x 加 64 位十六进制。
         *
         * @param txHash 用户传来的哈希
         * @return 小写且带 0x 的哈希
         */
        public static String normalizeTxHash(String txHash) {
            String raw = txHash == null ? "" : txHash.trim();
            if (raw.startsWith("0x") || raw.startsWith("0X")) raw = raw.substring(2);
            if (raw.length() != 64 || !raw.matches("[0-9a-fA-F]{64}")) throw AppError.of("INVALID_ARGUMENT", "缺少已确认的存入交易", "先在小狐狸里把同样金额的 USDC 存进结算合约。");
            return "0x" + raw.toLowerCase();
        }

        /**
         * 解析 deposit(address,uint256) 的输入数据。
         *
         * @param data 交易 input
         * @return 代币地址和数量
         */
        public static Deposit parseDeposit(String data) {
            String s = data == null ? "" : data.trim();
            if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
            if (s.length() < 8 + 64 + 64 || !s.substring(0, 8).equalsIgnoreCase(DEPOSIT)) throw AppError.of("INVALID_ARGUMENT", "这不是一笔存入交易", "卡充值只认结算合约的 deposit。");
            String tokenWord = s.substring(8, 72);
            if (!tokenWord.substring(0, 24).replace("0", "").isEmpty()) throw AppError.of("INVALID_ARGUMENT", "存入交易里的代币地址不合法", "");
            BigInteger amount;
            try { amount = new BigInteger(s.substring(72, 136), 16); }
            catch (Exception e) { throw AppError.of("INVALID_ARGUMENT", "存入金额无法解析", ""); }
            return new Deposit("0x" + tokenWord.substring(24), amount);
        }

        /**
         * 把 32 字节引用编成 ABI 字。
         *
         * @param ref 0x 开头的 64 位十六进制
         * @return 32 字节
         */
        private static byte[] bytes32(String ref) {
            String s = ref.startsWith("0x") || ref.startsWith("0X") ? ref.substring(2) : ref;
            if (s.length() != 64) throw AppError.of("INVALID_ARGUMENT", "账单号必须是 32 字节", "结算用流水 id 的 SHA-256。");
            byte[] b = Support.unhex(s);
            if (b.length != 32) throw AppError.of("INVALID_ARGUMENT", "账单号必须是 32 字节", "结算用流水 id 的 SHA-256。");
            return b;
        }

        /**
         * 把选择器和若干 32 字节字拼成 calldata。
         *
         * @param selector 4 字节选择器的十六进制，不带 0x
         * @param words ABI 字
         * @return 0x 开头的 calldata
         */
        private static String pack(String selector, byte[]... words) {
            byte[] out = Support.unhex(selector);
            for (byte[] word : words) {
                byte[] next = new byte[out.length + word.length];
                System.arraycopy(out, 0, next, 0, out.length);
                System.arraycopy(word, 0, next, out.length, word.length);
                out = next;
            }
            return "0x" + Support.hex(out);
        }
    }

    /**
     * 本地 Hardhat 节点的 JSON-RPC。运营方交易用 eth_sendTransaction，因为账户在节点里解锁。
     */
    static final class Rpc {
        final String url;
        final Support.HttpJson http;
        /**
         * @param url 节点地址
         * @param http HTTP 客户端
         */
        Rpc(String url, Support.HttpJson http) { this.url = url; this.http = http; }

        /**
         * 读取 chainId。
         *
         * @return 链编号
         */
        BigInteger chainId() { return Evmish.parse(call("eth_chainId", List.of())); }

        /**
         * 读取地址上的合约代码。
         *
         * @param address 合约地址
         * @return 代码十六进制，空合约通常是 0x
         */
        String code(String address) { return text(call("eth_getCode", List.of(address, "latest"))); }

        /**
         * 读取原生 ETH 余额。
         *
         * @param address 地址
         * @return wei
         */
        BigInteger nativeBalance(String address) { return Evmish.parse(call("eth_getBalance", List.of(address, "latest"))); }

        /**
         * 执行 eth_call，并把结果当成 uint256。
         *
         * @param to 合约地址
         * @param data calldata
         * @return 返回值
         */
        BigInteger callUint(String to, String data) { return Evmish.parse(call("eth_call", List.of(Map.of("to", to, "data", data), "latest"))); }

        /**
         * 估算 gas，并取 pending nonce，组装 EIP-1559 载荷。
         *
         * @param from 发送地址
         * @param to 接收地址
         * @param data calldata
         * @return 未签名载荷和手续费
         */
        Quoted quote(String from, String to, String data) {
            Map<String, String> call = Map.of("from", from, "to", to, "data", data);
            BigInteger gas = Evmish.parse(call("eth_estimateGas", List.of(call))).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
            BigInteger gasPrice = Evmish.parse(call("eth_gasPrice", List.of()));
            BigInteger tip = gasPrice.divide(BigInteger.TEN);
            try {
                BigInteger parsed = Evmish.parse(call("eth_maxPriorityFeePerGas", List.of()));
                if (parsed.signum() > 0) tip = parsed;
            } catch (Exception ignored) {}
            BigInteger maxFee = gasPrice.multiply(BigInteger.TWO).add(tip);
            if (tip.compareTo(maxFee) > 0) tip = maxFee;
            BigInteger nonce = Evmish.parse(call("eth_getTransactionCount", List.of(from, "pending")));
            EvmPayload payload = new EvmPayload();
            payload.type = 2;
            payload.chainId = chainId().toString();
            payload.nonce = nonce.toString();
            payload.to = to;
            payload.value = "0";
            payload.data = data;
            payload.gasLimit = gas.toString();
            payload.maxFeePerGas = maxFee.toString();
            payload.maxPriorityFeePerGas = tip.toString();
            return new Quoted(payload, aegis.chain.Chains.fee("ETH", Money.formatUnits(gas.multiply(maxFee), 18), Map.of(
                    "gasLimit", gas.toString(), "maxFeePerGas", maxFee.toString(), "maxPriorityFeePerGas", tip.toString())));
        }

        /**
         * 按哈希读取交易。
         *
         * @param hash 交易哈希
         * @return 发送方、接收方和 input
         */
        Tx transaction(String hash) {
            JsonNode raw = call("eth_getTransactionByHash", List.of(hash));
            if (raw == null || raw.isNull()) throw AppError.of("NOT_FOUND", "节点上看不到这笔交易", "等小狐狸广播完成后再入账。");
            return new Tx(raw.path("from").asText(""), raw.path("to").asText(""), raw.path("input").asText(""));
        }

        /**
         * 广播已签名交易。
         *
         * @param signed 原始交易十六进制
         * @return 交易哈希
         */
        String sendRaw(String signed) {
            if (!signed.startsWith("0x")) signed = "0x" + signed;
            String hash = text(call("eth_sendRawTransaction", List.of(signed)));
            if (hash.isEmpty()) throw new RuntimeException("节点没有返回交易哈希");
            return hash;
        }

        /**
         * 用节点里解锁的账户发送交易。
         *
         * @param from 运营方地址
         * @param to 接收地址
         * @param value 转出的 wei，空表示 0
         * @param data calldata，空表示没有
         * @return 交易哈希
         */
        String sendFrom(String from, String to, String value, String data) {
            Map<String, String> tx = new LinkedHashMap<>();
            tx.put("from", from);
            tx.put("to", to);
            if (value != null && !value.isEmpty() && !"0x0".equals(value)) tx.put("value", value);
            if (data != null && !data.isEmpty() && !"0x".equals(data)) tx.put("data", data);
            BigInteger gas = Evmish.parse(call("eth_estimateGas", List.of(tx))).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
            tx.put("gas", Support.quantityHex(gas));
            return text(call("eth_sendTransaction", List.of(tx)));
        }

        /**
         * 等回执最多 8 秒。成功返回 confirmed，超时返回 pending，失败抛出回滚错误。
         *
         * @param hash 交易哈希
         * @return confirmed 或 pending
         */
        String wait(String hash) {
            long deadline = System.currentTimeMillis() + 8_000;
            while (true) {
                JsonNode raw = call("eth_getTransactionReceipt", List.of(hash));
                if (raw != null && !raw.isNull() && !raw.isMissingNode()) {
                    String status = raw.path("status").asText("");
                    if ("0x1".equals(status)) return "confirmed";
                    throw AppError.of("REVERTED", "交易已上链但执行失败", "常见原因：没 approve、USDC 不够、链上可用余额不够，或这张账单已经结算过。");
                }
                if (System.currentTimeMillis() > deadline) return "pending";
                try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }
        }

        /**
         * 发送一次 JSON-RPC。
         *
         * @param method 方法名
         * @param params 参数
         * @return result 节点
         */
        private JsonNode call(String method, Object params) {
            JsonNode envelope = http.postJson(url, Support.rpcBody(method, params));
            if (envelope.has("error") && !envelope.get("error").isNull()) {
                throw new RuntimeException(method + ": " + envelope.path("error").path("message").asText(""));
            }
            return envelope.get("result");
        }

        /**
         * 把 JSON 文本节点转成字符串。
         *
         * @param raw JSON 节点
         * @return 文本，空节点时为空字符串
         */
        private static String text(JsonNode raw) { return raw == null || raw.isNull() ? "" : raw.asText(""); }
    }

    /**
     * 把以太坊十六进制数量解析成整数。
     */
    static final class Evmish {
        /**
         * @param raw JSON-RPC 返回的十六进制或数字
         * @return 整数，空值时为 0
         */
        static BigInteger parse(JsonNode raw) {
            if (raw == null || raw.isNull()) return BigInteger.ZERO;
            return Support.hexToInt(raw.asText(raw.toString().replace("\"", "")));
        }
    }
}
