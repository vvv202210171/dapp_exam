package aegis.transfer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

import aegis.service.SettlementService;
import aegis.txbuild.TxBuild;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

/**
 * 对本机 Hardhat 做一笔真实的 EIP-1559 签名和广播测试。
 */
class EvmTransferTest {
    private static final String RPC = "http://127.0.0.1:8545";
    private static final String PRIVATE_KEY =
            "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final String TO = "0x8a8bc31678b81e094ceed04e11a4faf8c89af626";

    private static final String USDC_CONTRACT_ADDRESS = "0x5FC8d32690cc91D4c39d9d3abcBD16989F875707";

    /**
     * 对本机 Hardhat 做一笔真实的 EIP-1559 签名和广播测试。
     *
     * @throws Exception
     */
    @Test
    void signsAndBroadcastsOneWeiTransferToLocalHardhat() throws Exception {
        try (Web3j web3j = Web3j.build(new HttpService(RPC))) {
            boolean available;
            try {
                EthChainId chain = web3j.ethChainId().send();
                available = !chain.hasError() && BigInteger.valueOf(31337).equals(chain.getChainId());
            } catch (Exception ignored) {
                available = false;
            }
            assumeTrue(available, "本机 Hardhat 未运行，或 chainId 不是 31337");
            //生成令牌
            Credentials credentials = Credentials.create(PRIVATE_KEY);
            //获取下一次的序列号
            BigInteger nonce = web3j.ethGetTransactionCount(
                    credentials.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
            //获取链编号
            BigInteger chainId = web3j.ethChainId().send().getChainId();
            //获取燃气上限
            BigInteger gasLimit = web3j.ethEstimateGas(
                            Transaction.createEtherTransaction(credentials.getAddress(), null, null, null, TO, BigInteger.ONE))
                    .send()
                    .getAmountUsed();
            // 上浮 20%。1.2 是小数，BigInteger 只能做整数，所以写成 × 12 / 10。
            gasLimit = gasLimit.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
            //获取燃气价
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            // 计算优先费
            BigInteger priorityFee = gasPrice.divide(BigInteger.TEN);
            if (priorityFee.signum() == 0) {
                priorityFee = BigInteger.ONE;
            }
            // 计算最大费用
            BigInteger maxFee = gasPrice.multiply(BigInteger.TWO).add(priorityFee);

            //计算出手续费
            BigInteger fee = gasLimit.multiply(maxFee);
            System.out.println(
                    "fee: " + fee + ", ether=" +
                            Convert.fromWei(new BigDecimal(fee), Convert.Unit.ETHER)
            );
            //获取余额
            BigInteger balance = web3j.ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance();
            System.out.println("balance: " + balance + ",ether=" + Convert.fromWei(new BigDecimal(balance), Convert.Unit.ETHER));
            assertTrue(balance.compareTo(BigInteger.ONE.add(fee)) > 0, "余额不足以支付 1 wei 转账和手续费");
            // 构造 EIP-1559 原始交易

            // 转账金额：1 ETH 转换成 Wei
            BigInteger value = Convert.toWei(
                    BigDecimal.ONE,
                    Convert.Unit.ETHER
            ).toBigIntegerExact();
            RawTransaction transaction = RawTransaction.createEtherTransaction(
                    chainId.longValue(),
                    nonce,
                    gasLimit,
                    TO,
                    value, // 1 ether = 10^18 wei
                    priorityFee,
                    maxFee);
            // 签名交易
            byte[] signed = TransactionEncoder.signMessage(transaction, credentials);
            String signedHex = Numeric.toHexString(signed);
            assertTrue(signedHex.startsWith("0x02"), "签名结果应为 EIP-1559 的 type-2 原始交易");
            EthSendTransaction sent = web3j.ethSendRawTransaction(signedHex).send();
            assertTrue(!sent.hasError(), () -> "节点拒绝交易：" + sent.getError().getMessage());
            String hash = sent.getTransactionHash();
            assertNotNull(hash);
            TransactionReceipt receipt = null;
            for (int i = 0; i < 40; i++) {
                EthGetTransactionReceipt response = web3j.ethGetTransactionReceipt(hash).send();
                Optional<TransactionReceipt> found = response.getTransactionReceipt();
                if (found.isPresent()) {
                    receipt = found.get();
                    break;
                }
                Thread.sleep(250);
            }
            assertNotNull(receipt, "交易已广播，但 10 秒内没有收到回执");
            assertTrue("0x1".equals(receipt.getStatus()), "本地转账执行失败");
        }
    }

    /**
     * USDC
     * 对本机 Hardhat 做一笔真实的 EIP-1559 签名和广播测试。
     *
     * @throws Exception
     */

    @Test
    void usdcSignsAndBroadcastsOneWeiTransferToLocalHardhat() throws Exception {
        try (Web3j web3j = Web3j.build(new HttpService(RPC))) {
            boolean available;
            try {
                EthChainId chain = web3j.ethChainId().send();
                available = !chain.hasError() && BigInteger.valueOf(31337).equals(chain.getChainId());
            } catch (Exception ignored) {
                available = false;
            }
            assumeTrue(available, "本机 Hardhat 未运行，或 chainId 不是 31337");
            //生成令牌
            Credentials credentials = Credentials.create(PRIVATE_KEY);
            //获取下一次的序列号
            BigInteger nonce = web3j.ethGetTransactionCount(
                    credentials.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
            //获取链编号
            BigInteger chainId = web3j.ethChainId().send().getChainId();
            // mint(address to, uint256 amount)。地址是收款人，数量是 6 位小数的最小单位。合约地址不放进参数。
            BigInteger amount = BigInteger.valueOf(1_000_000);
            String calldata = SettlementService.Abi.mint(TO, amount);
            // 检查合约地址上是否有代码。MockUSDC 的地址是 Hardhat 部署时固定的。
            String code = web3j.ethGetCode(USDC_CONTRACT_ADDRESS, DefaultBlockParameterName.LATEST).send().getCode();
            assumeTrue(code != null && code.length() > 2, "这个地址上没有 MockUSDC：" + USDC_CONTRACT_ADDRESS);
            //获取燃气上限。to 必须是合约地址，value 为 0。
            BigInteger gasLimit = web3j.ethEstimateGas(
                            Transaction.createFunctionCallTransaction(credentials.getAddress(), null, null, null, USDC_CONTRACT_ADDRESS, BigInteger.ZERO, calldata))
                    .send()
                    .getAmountUsed();
            // 上浮 20%。1.2 是小数，BigInteger 只能做整数，所以写成 × 12 / 10。
            gasLimit = gasLimit.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
            //获取燃气价
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            // 计算优先费
            BigInteger priorityFee = gasPrice.divide(BigInteger.TEN);
            if (priorityFee.signum() == 0) {
                priorityFee = BigInteger.ONE;
            }
            // 计算最大费用
            BigInteger maxFee = gasPrice.multiply(BigInteger.TWO).add(priorityFee);
            //计算出手续费
            BigInteger fee = gasLimit.multiply(maxFee);
            System.out.println(
                    "fee: " + fee + ", ether=" +
                            Convert.fromWei(new BigDecimal(fee), Convert.Unit.ETHER)
            );
            //获取余额
            BigInteger balance = web3j.ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance();
            System.out.println("balance: " + balance + ",ether=" + Convert.fromWei(new BigDecimal(balance), Convert.Unit.ETHER));
            assertTrue(balance.compareTo(fee) > 0, "余额不足以支付手续费");
            // 交易发给 MockUSDC。value 是附带的 ETH，铸币时为 0；1 USDC 已经写在 calldata 里。
            RawTransaction transaction = RawTransaction.createTransaction(
                    chainId.longValue(),
                    nonce,
                    gasLimit,
                    USDC_CONTRACT_ADDRESS,
                    BigInteger.ZERO,
                    calldata,
                    priorityFee,
                    maxFee);
            // 签名交易
            byte[] signed = TransactionEncoder.signMessage(transaction, credentials);
            String signedHex = Numeric.toHexString(signed);
            assertTrue(signedHex.startsWith("0x02"), "签名结果应为 EIP-1559 的 type-2 原始交易");
            EthSendTransaction sent = web3j.ethSendRawTransaction(signedHex).send();
            assertTrue(!sent.hasError(), () -> "节点拒绝交易：" + sent.getError().getMessage());
            String hash = sent.getTransactionHash();
            assertNotNull(hash);
            TransactionReceipt receipt = null;
            for (int i = 0; i < 40; i++) {
                EthGetTransactionReceipt response = web3j.ethGetTransactionReceipt(hash).send();
                Optional<TransactionReceipt> found = response.getTransactionReceipt();
                if (found.isPresent()) {
                    receipt = found.get();
                    break;
                }
                Thread.sleep(250);
            }
            assertNotNull(receipt, "交易已广播，但 10 秒内没有收到回执");
            assertTrue("0x1".equals(receipt.getStatus()), "本地 USDC 转账执行失败");

        }
    }


    private String TEST_RPC = "https://ethereum-sepolia.publicnode.com";
    private String TEST_CONTRACT_ADDRESS = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"; // USDC Sepolia
    private String TEST_PRIVATE_KEY = "fac4bb92ee8d591c5fce6684c8553e0a1f30f3a99a4bd98c497ca283818f23cb"; // Replace with your test private key
//0xC879B4C414DcEa2AaeEb163D632998C15367b79C

    /**
     * 在 Sepolia 上转官方 USDC。官方合约没有公开的 mint，只能 transfer 自己已经持有的余额。
     */
    @Test
    void testUsdcTransfer() throws Exception {
        assumeTrue(TEST_PRIVATE_KEY.matches("(?i)(0x)?[0-9a-f]{64}"), "没有配置 Sepolia 测试私钥，跳过");
        try (Web3j web3j = Web3j.build(new HttpService(TEST_RPC))) {
            Credentials credentials = Credentials.create(TEST_PRIVATE_KEY);
            BigInteger chainId = web3j.ethChainId().send().getChainId();
            assumeTrue(BigInteger.valueOf(11155111).equals(chainId), "RPC 不是 Sepolia");
            BigInteger nonce = web3j.ethGetTransactionCount(
                    credentials.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
            // transfer(address to, uint256 amount)。1 USDC = 10^6。交易 to 是合约，收款人在 calldata 里。
            BigInteger amount = BigInteger.valueOf(1_000_000);
            String calldata = TxBuild.erc20Transfer(TO, amount);
            String code = web3j.ethGetCode(TEST_CONTRACT_ADDRESS, DefaultBlockParameterName.LATEST).send().getCode();
            assertTrue(code != null && code.length() > 2, "这个地址上没有 USDC：" + TEST_CONTRACT_ADDRESS);
            String balanceOf = TxBuild.erc20BalanceOf(credentials.getAddress());
            String rawBalance = web3j.ethCall(
                    Transaction.createEthCallTransaction(credentials.getAddress(), TEST_CONTRACT_ADDRESS, balanceOf),
                    DefaultBlockParameterName.LATEST).send().getValue();
            // eth_call 返回的是 32 字节十六进制。toBigInt 得到最小单位，USDC 再除以 10^6 才是枚数。
            BigInteger usdcUnits = Numeric.toBigInt(rawBalance);
            System.out.println("Sepolia USDC 余额: " + usdcUnits + " (" + new BigDecimal(usdcUnits).movePointLeft(6) + " USDC)");
            assertTrue(usdcUnits.compareTo(amount) >= 0, "Sepolia USDC 余额不够 1 枚");
            //获取燃气s上限。to 必须是合约地址，value 为 0。
            BigInteger gasLimit = web3j.ethEstimateGas(
                            Transaction.createFunctionCallTransaction(credentials.getAddress(), null, null, null, TEST_CONTRACT_ADDRESS, BigInteger.ZERO, calldata))
                    .send()
                    .getAmountUsed();
            // 上浮 20%。1.2 是小数，BigInteger 只能做整数，所以写成 × 12 / 10。
            gasLimit = gasLimit.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
            //获取燃气价
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            // 计算优先费
            BigInteger priorityFee = gasPrice.divide(BigInteger.TEN);
            if (priorityFee.signum() == 0) {
                priorityFee = BigInteger.ONE;
            }
            // 计算最大费用
            BigInteger maxFee = gasPrice.multiply(BigInteger.TWO).add(priorityFee);
            //计算出手续费
            BigInteger fee = gasLimit.multiply(maxFee);
            System.out.println(
                    "fee: " + fee + ", ether=" +
                            Convert.fromWei(new BigDecimal(fee), Convert.Unit.ETHER)
            );
            //获取余额
            BigInteger balance = web3j.ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance();
            System.out.println("from=" + credentials.getAddress() + ",balance: " + balance + ",ether=" + Convert.fromWei(new BigDecimal(balance), Convert.Unit.ETHER));
            assertTrue(balance.compareTo(fee) > 0, "余额不足以支付手续费");
            // 交易发给 Sepolia USDC。value 是附带的 ETH，转代币时为 0；1 USDC 已经写在 calldata 里。
            RawTransaction transaction = RawTransaction.createTransaction(
                    chainId.longValue(),
                    nonce,
                    gasLimit,
                    TEST_CONTRACT_ADDRESS,
                    BigInteger.ZERO,
                    calldata,
                    priorityFee,
                    maxFee);
            // 签名交易
            byte[] signed = TransactionEncoder.signMessage(transaction, credentials);
            String signedHex = Numeric.toHexString(signed);
            assertTrue(signedHex.startsWith("0x02"), "签名结果应为 EIP-1559 的 type-2 原始交易");
            EthSendTransaction sent = web3j.ethSendRawTransaction(signedHex).send();
            assertTrue(!sent.hasError(),
                    () -> "节点拒绝交易：" + sent.getError().getMessage());
            String hash = sent.getTransactionHash();
            assertNotNull(hash);
            TransactionReceipt receipt = null;
            for (int i = 0; i < 40; i++) {
                EthGetTransactionReceipt response = web3j.ethGetTransactionReceipt(hash).send();
                Optional<TransactionReceipt> found = response.getTransactionReceipt();
                if (found.isPresent()) {
                    receipt = found.get();
                    break;
                }
                Thread.sleep(250);
            }
            assertNotNull(receipt, "交易已广播，但 10 秒内没有收到回执");
            assertTrue("0x1".equals(receipt.getStatus()), "USDC 转账执行失败");
        }
    }

    /**
     * 在 Sepolia 上转官方 ETH。官方合约没有公开的 mint，只能 transfer 自己已经持有的余额。
     */
    @Test
    void testEthTransfer() throws Exception {
        assumeTrue(TEST_PRIVATE_KEY.matches("(?i)(0x)?[0-9a-f]{64}"), "没有配置 Sepolia 测试私钥，跳过");
        try (Web3j web3j = Web3j.build(new HttpService(TEST_RPC))) {
            // 生成令牌
            Credentials credentials = Credentials.create(TEST_PRIVATE_KEY);
            // 获取链编号
            BigInteger chainId = web3j.ethChainId().send().getChainId();
            assumeTrue(BigInteger.valueOf(11155111).equals(chainId), "RPC 不是 Sepolia");
            // 获取下一次的序列号
            BigInteger nonce = web3j.ethGetTransactionCount(
                    credentials.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
            // 获取燃气上限
            BigInteger gasLimit = web3j.ethEstimateGas(
                            Transaction.createEtherTransaction(credentials.getAddress(), null, null, null, TO, BigInteger.ONE))
                    .send()
                    .getAmountUsed();
            // 上浮 20%。1.2 是小数，BigInteger 只能做整数，所以写成 × 12 / 10。
            gasLimit = gasLimit.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
            // 获取燃气价
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            // 计算优先费
            BigInteger priorityFee = gasPrice.divide(BigInteger.TEN);
            if (priorityFee.signum() == 0) {
                priorityFee = BigInteger.ONE;
            }
            // 计算最大费用
            BigInteger maxFee = gasPrice.multiply(BigInteger.TWO).add(priorityFee);
            // 计算出手续费
            BigInteger fee = gasLimit.multiply(maxFee);
            System.out.println(
                    "fee: " + fee + ", ether=" +
                            Convert.fromWei(new BigDecimal(fee), Convert.Unit.ETHER).toPlainString()
            );
            // 获取余额
            BigInteger balance = web3j.ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance();
            System.out.println("from=" + credentials.getAddress() + ",balance: " + balance + ",ether=" + Convert.fromWei(new BigDecimal(balance), Convert.Unit.ETHER));
            assertTrue(balance.compareTo(BigInteger.ONE.add(fee)) > 0, "余额不足以支付 1 wei 转账和手续费");
            // 构造 EIP-1559 原始交易
            RawTransaction transaction = RawTransaction.createEtherTransaction(
                    chainId.longValue(),
                    nonce,
                    gasLimit,
                    TO,
                    Convert.toWei("0.001", Convert.Unit.ETHER).toBigIntegerExact(), // 0.001 eth
                    priorityFee,
                    maxFee);
            // 签名交易
            byte[] signed = TransactionEncoder.signMessage(transaction, credentials);
            String signedHex = Numeric.toHexString(signed);
            assertTrue(signedHex.startsWith("0x02"), "签名结果应为 EIP-1559 的 type-2 原始交易");
            EthSendTransaction sent = web3j.ethSendRawTransaction(signedHex).send();
            assertTrue(!sent.hasError(),
                    () -> "节点拒绝交易：" + sent.getError().getMessage());
            String hash = sent.getTransactionHash();
            assertNotNull(hash);
            TransactionReceipt receipt = null;
            for (int i = 0; i < 40; i++) {
                EthGetTransactionReceipt response = web3j.ethGetTransactionReceipt(hash).send();
                Optional<TransactionReceipt> found = response.getTransactionReceipt();
                if (found.isPresent()) {
                    receipt = found.get();
                    break;
                }
                Thread.sleep(250);

            }
            assertNotNull(receipt, "交易已广播，但 10 秒内没有收到回执");
            assertTrue("0x1".equals(receipt.getStatus()), "ETH 转账执行失败");
        }
    }
}