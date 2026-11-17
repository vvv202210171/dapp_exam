package aegis.chain;

import aegis.config.Config;
import aegis.domain.Models.AppError;
import aegis.domain.Models.Asset;
import aegis.domain.Models.BtcInput;
import aegis.domain.Models.BtcOutput;
import aegis.domain.Models.BtcPayload;
import aegis.domain.Models.ChainInfo;
import aegis.domain.Models.EvmPayload;
import aegis.domain.Models.Fee;
import aegis.domain.Models.Health;
import aegis.domain.Models.PrepareResult;
import aegis.domain.Models.Probe;
import aegis.domain.Models.SolPayload;
import aegis.domain.Models.TronPayload;
import aegis.domain.Models.TxState;
import aegis.domain.Money;
import aegis.platform.Support;
import aegis.txbuild.TxBuild;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 五条链的名称、地址校验、资产清单，以及沙箱或真实节点适配器。
 */
public final class Chains {
    public static final List<String> ORDER = List.of("bitcoin", "ethereum", "bnb", "solana", "tron");
    public static final String SANDBOX_EVM = "84532026";

    /** 工具类，不允许实例化。 */
    private Chains() {}

    /**
     * 一条链的余额、报价、广播和探针。
     */
    public interface Adapter {
        /**
         * @return 链编号
         */
        String id();
        /**
         * @return 这条链现在是否可达
         */
        Health health();
        /**
         * @param address 查询地址
         * @param asset 资产
         * @return 人类可读余额
         */
        String balance(String address, Asset asset);
        /**
         * @param from 付款地址
         * @param to 收款地址
         * @param amount 人类可读金额
         * @param units 最小单位金额
         * @param asset 资产
         * @return 未签名载荷
         */
        PrepareResult prepare(String from, String to, String amount, BigInteger units, Asset asset);
        /**
         * @param signed 已签名交易
         * @return 交易哈希
         */
        String broadcast(String signed);
        /**
         * @param hash 交易哈希
         * @return 链上状态
         */
        TxState status(String hash);
        /**
         * @return 可手动执行的探针
         */
        List<Probe> probes();
        /**
         * @param name 探针名称
         * @param args 参数，通常含 address
         * @return 探针结果
         */
        Object runProbe(String name, Map<String, String> args);
    }

    /**
     * 是否是本系统支持的链。
     *
     * @param id 链编号
     * @return 支持时为 true
     */
    public static boolean valid(String id) { return ORDER.contains(id); }

    /**
     * 链的展示名称。
     *
     * @param id 链编号
     * @return 名称
     */
    public static String name(String id) {
        return switch (id) {
            case "bitcoin" -> "Bitcoin";
            case "ethereum" -> "Ethereum";
            case "bnb" -> "BNB Smart Chain";
            case "solana" -> "Solana";
            case "tron" -> "TRON";
            default -> id;
        };
    }

    /**
     * 这条链的原生币符号。
     *
     * @param id 链编号
     * @return 符号
     */
    public static String nativeSymbol(String id) {
        return switch (id) {
            case "bitcoin" -> "BTC";
            case "ethereum" -> "ETH";
            case "bnb" -> "BNB";
            case "solana" -> "SOL";
            case "tron" -> "TRX";
            default -> "";
        };
    }

    /**
     * 地址排序用的固定顺序。
     *
     * @param id 链编号
     * @return 序号，未知链放在最后
     */
    public static int rank(String id) {
        int i = ORDER.indexOf(id);
        return i < 0 ? 9 : i;
    }

    /**
     * 地址规范化。EVM 和比特币转成小写。
     *
     * @param id 链编号
     * @param address 原地址
     * @return 规范化地址
     */
    public static String normalize(String id, String address) {
        address = address == null ? "" : address.trim();
        if ("ethereum".equals(id) || "bnb".equals(id) || "bitcoin".equals(id)) return address.toLowerCase();
        return address;
    }

    /**
     * 比较两个地址是否同一个。
     *
     * @param id 链编号
     * @param a 地址
     * @param b 另一个地址
     * @return 相同为 true
     */
    public static boolean same(String id, String a, String b) {
        return normalize(id, a).equals(normalize(id, b));
    }

    /**
     * 按链和网络检查地址格式。
     *
     * @param id 链编号
     * @param network testnet 或 mainnet
     * @param address 地址
     */
    public static void validate(String id, String network, String address) {
        address = address == null ? "" : address.trim();
        switch (id) {
            case "ethereum", "bnb" -> {
                if (!isHexAddress(address)) throw AppError.of("INVALID_ARGUMENT", "EVM 地址格式不正确", "地址应为 0x 开头的 40 位十六进制。");
            }
            case "bitcoin" -> {
                String hrp = "mainnet".equals(network) ? "bc1q" : "tb1q";
                String low = address.toLowerCase();
                if (!low.startsWith(hrp) || low.length() < 20 || low.length() > 90 || !bech32(low.substring(hrp.length()))) {
                    throw AppError.of("INVALID_ARGUMENT", "比特币地址格式不正确", "当前只收原生隔离见证地址，主网 bc1q…，测试网 tb1q…。");
                }
            }
            case "solana" -> {
                try {
                    if (Support.base58Decode(address).length != 32) throw new IllegalArgumentException();
                } catch (Exception e) {
                    throw AppError.of("INVALID_ARGUMENT", "Solana 地址格式不正确", "地址是 32 字节 ed25519 公钥的 base58。");
                }
            }
            case "tron" -> {
                try {
                    byte[] raw = Support.base58CheckDecode(address);
                    if (raw.length != 21 || raw[0] != 0x41) throw new IllegalArgumentException();
                } catch (AppError e) {
                    throw e;
                } catch (Exception e) {
                    throw AppError.of("INVALID_ARGUMENT", "TRON 地址格式不正确", "地址是带 0x41 版本字节的 base58check，一般以 T 开头。");
                }
            }
            default -> throw AppError.of("INVALID_ARGUMENT", "不支持的链", "");
        }
    }

    /**
     * 这条链在当前网络可展示的资产。
     *
     * @param id 链编号
     * @param network 网络
     * @param mode sandbox 或 live
     * @return 原生币和代币
     */
    public static List<Asset> assets(String id, String network, String mode) {
        Asset coin = new Asset();
        coin.symbol = nativeSymbol(id);
        coin.name = name(id);
        coin.decimals = nativeDecimals(id);
        coin.kind = "native";
        List<Asset> out = new ArrayList<>();
        out.add(coin);
        for (TokenSpec spec : catalog(id)) {
            String contract = spec.contracts.get(network);
            String note = "";
            if (contract == null || contract.isEmpty()) {
                if ("sandbox".equals(mode)) {
                    contract = spec.contracts.get("mainnet");
                    note = "沙箱展示主网合约地址，余额是模拟值。";
                } else {
                    note = "当前网络没有配置该代币的官方合约，余额按 0 处理。";
                }
            }
            Asset a = new Asset();
            a.symbol = spec.symbol;
            a.name = spec.name;
            a.decimals = spec.decimals;
            a.kind = "token";
            a.contract = contract;
            a.note = note;
            out.add(a);
        }
        return out;
    }

    /**
     * 按符号找资产。
     *
     * @param id 链编号
     * @param network 网络
     * @param mode 模式
     * @param symbol 资产符号
     * @return 资产
     */
    public static Asset findAsset(String id, String network, String mode, String symbol) {
        String want = symbol == null ? "" : symbol.trim().toUpperCase();
        for (Asset a : assets(id, network, mode)) if (a.symbol.equals(want)) return a;
        throw AppError.of("INVALID_ARGUMENT", "这条链不支持该资产", "Bitcoin 原生链没有 USDT/USDC 合约。稳定币在 EVM、TRON 和 Solana 上。");
    }

    /**
     * 按地址稳定生成模拟余额，同一个地址每次相同。
     *
     * @param address 地址
     * @param symbol 资产符号
     * @param decimals 小数位
     * @return 模拟余额
     */
    public static String sandboxAmount(String address, String symbol, int decimals) {
        byte[] sum = Support.sha256((address.toLowerCase() + "|" + symbol).getBytes());
        int n = ByteBuffer.wrap(sum, 0, 2).getShort() & 0xffff;
        return switch (symbol) {
            case "BTC" -> Money.formatUnits(BigInteger.valueOf(1_000_000L + (n % 750) * 100_000L), 8);
            case "ETH" -> Money.formatUnits(BigInteger.valueOf(50_000_000_000_000_000L + (long) (n % 1500) * 1_000_000_000_000_000L), 18);
            case "BNB" -> Money.formatUnits(BigInteger.valueOf(20_000_000_000_000_000L + (long) (n % 800) * 10_000_000_000_000_000L), 18);
            case "SOL" -> Money.formatUnits(BigInteger.valueOf(500_000_000L + (long) (n % 19_500) * 1_000_000L), 9);
            case "TRX" -> Money.formatUnits(BigInteger.valueOf(100_000_000L + (long) n * 75_000L), 6);
            case "USDT", "USDC" -> Money.formatUnits(BigInteger.valueOf(20L + n % 1980).multiply(Money.pow10(decimals)), decimals);
            default -> "0";
        };
    }

    /**
     * 区块浏览器交易页前缀。
     *
     * @param id 链编号
     * @param network 网络
     * @return URL 前缀
     */
    public static String explorer(String id, String network) {
        boolean main = "mainnet".equals(network);
        return switch (id) {
            case "ethereum" -> main ? "https://etherscan.io/tx/" : "https://sepolia.etherscan.io/tx/";
            case "bnb" -> main ? "https://bscscan.com/tx/" : "https://testnet.bscscan.com/tx/";
            case "bitcoin" -> main ? "https://mempool.space/tx/" : "https://mempool.space/testnet/tx/";
            case "solana" -> "https://solscan.io/tx/";
            case "tron" -> main ? "https://tronscan.org/#/transaction/" : "https://shasta.tronscan.org/#/transaction/";
            default -> "";
        };
    }

    /**
     * 按模式选择沙箱或真实节点适配器。
     */
    public static final class Registry {
        private final Map<String, Adapter> adapters = new LinkedHashMap<>();
        public final String mode;
        public final String network;

        /**
         * @param cfg 模式、网络和节点地址
         * @param http 访问节点的客户端
         */
        public Registry(Config cfg, Support.HttpJson http) {
            this.mode = cfg.mode;
            this.network = cfg.network;
            if ("sandbox".equals(cfg.mode)) {
                for (String id : ORDER) adapters.put(id, new Sandbox(id, cfg.network));
            } else {
                adapters.put("bitcoin", new aegis.chain.live.BtcClient(cfg.network, cfg.rpcs.get("bitcoin"), http));
                adapters.put("ethereum", new aegis.chain.live.EvmClient("ethereum", cfg.network, cfg.rpcs.get("ethereum"), http));
                adapters.put("bnb", new aegis.chain.live.EvmClient("bnb", cfg.network, cfg.rpcs.get("bnb"), http));
                adapters.put("solana", new aegis.chain.live.SolClient(cfg.network, cfg.rpcs.get("solana"), http));
                adapters.put("tron", new aegis.chain.live.TronClient(cfg.network, cfg.rpcs.get("tron"), http));
            }
        }

        /**
         * 取出一条链的适配器。
         *
         * @param id 链编号
         * @return 适配器
         */
        public Adapter get(String id) {
            Adapter a = adapters.get(id);
            if (a == null) throw AppError.of("INVALID_ARGUMENT", "不支持的链", "");
            return a;
        }

        /**
         * 检查已注册的链是否可达。
         *
         * @return 健康结果
         */
        public List<Health> health() {
            List<Future<Health>> jobs = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (String id : ORDER) jobs.add(pool.submit(() -> adapters.get(id).health()));
                List<Health> out = new ArrayList<>();
                for (Future<Health> job : jobs) out.add(job.get());
                return out;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * 给元信息接口的链清单。
         *
         * @return 每条链的名称、原生币和资产
         */
        public List<Map<String, Object>> chains() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String id : ORDER) {
                ChainInfo info = new ChainInfo();
                info.id = id;
                info.name = name(id);
                info.nativeSymbol = nativeSymbol(id);
                info.explorerTx = explorer(id, network);
                info.assets = assets(id, network, mode);
                out.add(info.json());
            }
            return out;
        }
    }

    /**
     * 沙箱适配器。余额是模拟值，广播不会发到公网。
     */
    static final class Sandbox implements Adapter {
        final String chain;
        final String network;

        /**
         * @param chain 链编号
         * @param network testnet 或 mainnet，决定沙箱地址校验用哪套前缀
         */
        Sandbox(String chain, String network) { this.chain = chain; this.network = network; }

        /**
         * @return 链编号
         */
        public String id() { return chain; }

        /**
         * 沙箱始终视为可达。
         *
         * @return 健康结果
         */
        public Health health() {
            Health h = new Health();
            h.chain = chain;
            h.name = name(chain);
            h.ok = true;
            h.latencyMs = 1;
            h.block = "sandbox";
            h.endpoint = "sandbox://" + chain;
            h.mode = "sandbox";
            return h;
        }

        /**
         * @param address 地址
         * @param asset 资产
         * @return 模拟余额
         */
        public String balance(String address, Asset asset) { return sandboxAmount(address, asset.symbol, asset.decimals); }

        /**
         * 按链生成教学用未签名交易。
         *
         * @param from 付款地址
         * @param to 收款地址
         * @param amount 人类可读金额
         * @param units 最小单位
         * @param asset 资产
         * @return 报价
         */
        public PrepareResult prepare(String from, String to, String amount, BigInteger units, Asset asset) {
            return switch (chain) {
                case "bitcoin" -> btc(from, to, amount, units, asset);
                case "solana" -> sol(from, to, amount, units, asset);
                case "tron" -> tron(from, to, amount, units, asset);
                default -> evm(from, to, amount, units, asset);
            };
        }

        /**
         * 构造沙箱 EVM 转账。chainId 固定为 84532026，不能在公网重放。
         *
         * @param from 付款地址
         * @param to 收款地址
         * @param amount 人类可读金额
         * @param units 最小单位
         * @param asset 资产
         * @return 报价
         */
        private PrepareResult evm(String from, String to, String amount, BigInteger units, Asset asset) {
            BigInteger gas = BigInteger.valueOf(21_000);
            BigInteger value = units;
            String data = "0x";
            String target = to;
            List<String> warnings = new ArrayList<>(List.of("沙箱 chainId 是 84532026，签出来的交易不能在主网或测试网重放。"));
            if (!"native".equals(asset.kind)) {
                gas = BigInteger.valueOf(65_000);
                value = BigInteger.ZERO;
                data = TxBuild.erc20Transfer(to, units);
                target = asset.contract;
                warnings.add("代币转账的 to 是合约地址，金额在 calldata 里。");
            }
            BigInteger maxFee = BigInteger.valueOf(20_000_000_000L);
            BigInteger tip = BigInteger.valueOf(1_000_000_000L);
            BigInteger feeWei = gas.multiply(maxFee);
            EvmPayload payload = new EvmPayload();
            payload.type = 2;
            payload.chainId = SANDBOX_EVM;
            payload.nonce = "0";
            payload.to = target;
            payload.value = value.toString();
            payload.data = data;
            payload.gasLimit = gas.toString();
            payload.maxFeePerGas = maxFee.toString();
            payload.maxPriorityFeePerGas = tip.toString();
            Fee fee = fee(nativeSymbol(chain), Money.formatUnits(feeWei, 18), Map.of(
                    "gasLimit", gas.toString(), "maxFeePerGas", maxFee.toString(), "maxPriorityFeePerGas", tip.toString()));
            return result(chain, network, "sandbox", asset.symbol, from, to, amount, fee, payload, warnings);
        }

        /**
         * 构造沙箱比特币转账。
         *
         * @param from 付款地址
         * @param to 收款地址
         * @param amount 人类可读金额
         * @param units 聪
         * @param asset 资产
         * @return 报价
         */
        private PrepareResult btc(String from, String to, String amount, BigInteger units, Asset asset) {
            if (!"native".equals(asset.kind)) throw AppError.of("INVALID_ARGUMENT", "不能在比特币主链上直接转这个资产", "稳定币请换 Ethereum、BNB、TRON 或 Solana。");
            long sats = TxBuild.mustLong(units);
            if (sats <= 0) throw AppError.of("INVALID_ARGUMENT", "金额超出范围", "");
            BtcInput utxo = new BtcInput();
            utxo.txid = Support.hex(Support.sha256(("sandbox-utxo|" + from).getBytes()));
            utxo.vout = 0;
            utxo.value = sats + 500_000;
            TxBuild.Coins coins = TxBuild.selectCoins(List.of(utxo), sats, 15);
            List<BtcOutput> outputs = new ArrayList<>();
            BtcOutput pay = new BtcOutput();
            pay.address = to;
            pay.value = sats;
            outputs.add(pay);
            if (coins.change() > 0) {
                BtcOutput change = new BtcOutput();
                change.address = from;
                change.value = coins.change();
                outputs.add(change);
            }
            BtcPayload payload = new BtcPayload();
            payload.network = network;
            payload.inputs = coins.selected();
            payload.outputs = outputs;
            payload.feeSats = coins.fee();
            return result("bitcoin", network, "sandbox", "BTC", from, to, amount,
                    fee("BTC", Money.formatUnits(BigInteger.valueOf(coins.fee()), 8), Map.of("feeRateSatVb", "15")),
                    payload, List.of("沙箱 UTXO 是假的。签名结构是真的 P2WPKH，但这笔交易不会被比特币网络接受。"));
        }

        /**
         * 构造沙箱 Solana 转账。
         *
         * @param from 付款地址
         * @param to 收款地址
         * @param amount 人类可读金额
         * @param units lamports
         * @param asset 资产
         * @return 报价
         */
        private PrepareResult sol(String from, String to, String amount, BigInteger units, Asset asset) {
            if (!"native".equals(asset.kind)) throw AppError.of("INVALID_ARGUMENT", "沙箱里的 Solana 转账只覆盖原生 SOL", "USDT/USDC 可以在资产页看到模拟余额。SPL 转账需要关联代币账户。");
            long lamports = TxBuild.mustLong(units);
            if (lamports == 0) throw AppError.of("INVALID_ARGUMENT", "金额超出范围", "");
            byte[] block = new byte[32];
            java.util.Arrays.fill(block, (byte) 7);
            String blockhash = Support.base58Encode(block);
            byte[] msg = TxBuild.buildSolTransfer(from, to, lamports, blockhash);
            SolPayload payload = new SolPayload();
            payload.message = Base64.getEncoder().encodeToString(msg);
            payload.blockhash = blockhash;
            payload.lamports = units.toString();
            payload.from = from;
            payload.to = to;
            return result("solana", network, "sandbox", "SOL", from, to, amount,
                    fee("SOL", "0.000005", Map.of("lamports", "5000")), payload,
                    List.of("沙箱 blockhash 是固定值，签出来的交易不会被 Solana 接受。"));
        }

        /**
         * 构造沙箱 TRON 转账。
         *
         * @param from 付款地址
         * @param to 收款地址
         * @param amount 人类可读金额
         * @param units sun
         * @param asset 资产
         * @return 报价
         */
        private PrepareResult tron(String from, String to, String amount, BigInteger units, Asset asset) {
            String feeAmount = "0";
            String kind = "TransferContract";
            List<String> warnings = new ArrayList<>(List.of("沙箱里的 txID 不是 raw_data 的哈希，广播会被真实节点拒绝。签名步骤和主网相同：对 txID 做 secp256k1。"));
            if (!"native".equals(asset.kind)) {
                feeAmount = "15";
                kind = "TriggerSmartContract";
                warnings.add("TRC20 的能量费用这里用 15 TRX 作为教学估算。");
            }
            String txId = TxBuild.tronTxId("sandbox", from, to, amount, asset.symbol, units.toString());
            TronPayload payload = new TronPayload();
            payload.txID = txId;
            payload.transaction = TxBuild.tronTransaction(txId, kind);
            return result("tron", network, "sandbox", asset.symbol, from, to, amount, fee("TRX", feeAmount, null), payload, warnings);
        }

        /**
         * 沙箱不广播，只返回由签名内容派生的假哈希。
         *
         * @param signed 已签名内容
         * @return 假哈希
         */
        public String broadcast(String signed) {
            if (signed == null || signed.isBlank()) throw AppError.of("INVALID_ARGUMENT", "缺少已签名交易", "");
            String hash = Support.hex(Support.sha256((chain + "|" + signed).getBytes()));
            if ("ethereum".equals(chain) || "bnb".equals(chain)) hash = "0x" + hash;
            return hash;
        }

        /**
         * 沙箱里的交易直接视为已确认。
         *
         * @param hash 哈希
         * @return 已确认状态
         */
        public TxState status(String hash) {
            TxState s = new TxState();
            s.state = "confirmed";
            s.hash = hash;
            s.block = "sandbox";
            s.detail = "沙箱没有广播到真实网络。";
            return s;
        }

        /**
         * @return 沙箱探针说明
         */
        public List<Probe> probes() {
            Probe p = new Probe();
            p.name = "echo";
            p.title = "沙箱探针";
            p.description = "不访问节点，用来确认控制台本身是通的";
            return List.of(p);
        }

        /**
         * 沙箱探针不访问网络。
         *
         * @param name 探针名称
         * @param args 参数
         * @return 说明当前是沙箱
         */
        public Object runProbe(String name, Map<String, String> args) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mode", "sandbox");
            out.put("chain", chain);
            out.put("probe", name);
            out.put("address", args.get("address"));
            out.put("note", "当前 MODE=sandbox，没有发出网络请求。改成 live 后，同一条探针会打到真实节点。");
            out.put("at", Instant.now().toString());
            return out;
        }
    }

    /**
     * 组装统一的报价对象。
     *
     * @param chain 链编号
     * @param network 网络
     * @param mode 模式
     * @param asset 资产符号
     * @param from 付款地址
     * @param to 收款地址
     * @param amount 金额
     * @param fee 手续费
     * @param payload 未签名载荷
     * @param warnings 给前端的提示
     * @return 报价
     */
    public static PrepareResult result(String chain, String network, String mode, String asset, String from, String to, String amount, Fee fee, Object payload, List<String> warnings) {
        PrepareResult r = new PrepareResult();
        r.chain = chain;
        r.network = network;
        r.mode = mode;
        r.asset = asset;
        r.from = from;
        r.to = to;
        r.amount = amount;
        r.fee = fee;
        r.payload = payload;
        r.warnings = new ArrayList<>(warnings);
        return r;
    }

    /**
     * 组装手续费。
     *
     * @param symbol 手续费币种
     * @param amount 数量
     * @param detail 估算明细
     * @return 手续费
     */
    public static Fee fee(String symbol, String amount, Map<String, String> detail) {
        Fee f = new Fee();
        f.symbol = symbol;
        f.amount = amount;
        f.detail = detail == null || detail.isEmpty() ? null : new LinkedHashMap<>(detail);
        return f;
    }

    /**
     * 原生币小数位。
     *
     * @param id 链编号
     * @return 小数位
     */
    private static int nativeDecimals(String id) {
        return switch (id) {
            case "bitcoin" -> 8;
            case "solana" -> 9;
            case "tron" -> 6;
            default -> 18;
        };
    }

    /**
     * 稳定币在各网络上的合约地址。
     *
     * @param symbol 符号
     * @param name 名称
     * @param decimals 小数位
     * @param contracts 网络到合约地址
     */
    private record TokenSpec(String symbol, String name, int decimals, Map<String, String> contracts) {}

    /**
     * 这条链配置了哪些稳定币。
     *
     * @param id 链编号
     * @return 代币规格
     */
    private static List<TokenSpec> catalog(String id) {
        return switch (id) {
            case "ethereum" -> List.of(
                    new TokenSpec("USDT", "Tether USD", 6, Map.of("mainnet", "0xdAC17F958D2ee523a2206206994597C13D831ec7")),
                    new TokenSpec("USDC", "USD Coin", 6, Map.of("mainnet", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48")));
            case "bnb" -> List.of(
                    new TokenSpec("USDT", "Tether USD", 18, Map.of("mainnet", "0x55d398326f99059fF775485246999027B3197955")),
                    new TokenSpec("USDC", "USD Coin", 18, Map.of("mainnet", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d")));
            case "tron" -> List.of(
                    new TokenSpec("USDT", "Tether USD", 6, Map.of("mainnet", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t")),
                    new TokenSpec("USDC", "USD Coin", 6, Map.of("mainnet", "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8")));
            case "solana" -> List.of(
                    new TokenSpec("USDT", "Tether USD", 6, Map.of("mainnet", "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB")),
                    new TokenSpec("USDC", "USD Coin", 6, Map.of("mainnet", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")));
            default -> List.of();
        };
    }

    /**
     * 是否为 0x 加 40 位十六进制。
     *
     * @param address 地址
     * @return 格式正确时为 true
     */
    private static boolean isHexAddress(String address) {
        if (address.length() != 42 || !(address.startsWith("0x") || address.startsWith("0X"))) return false;
        for (int i = 2; i < address.length(); i++) {
            char c = address.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    /**
     * 数据部分是否只用 bech32 字符。这里不校验校验和。
     *
     * @param s 去掉前缀后的字符
     * @return 字符合法时为 true
     */
    private static boolean bech32(String s) {
        if (s.isEmpty()) return false;
        String alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
        for (int i = 0; i < s.length(); i++) if (alphabet.indexOf(s.charAt(i)) < 0) return false;
        return true;
    }
}
