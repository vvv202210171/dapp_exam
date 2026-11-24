package aegis;

public class HexTest {
    public static void main(String[] args) {
        // 测试十六进制字符串转10进制整数
        String hexString = "0x63c2f9e4"; // 十六进制字符串
        int eth_gasPrice = Integer.parseInt(hexString.substring(2), 16); // 去掉前缀"0x"并转换为10进制整数
        System.out.println("Hex: " + hexString + " ->单价 eth_gasPrice：Decimal: " + eth_gasPrice); // 输出结果1673722340
        hexString = "0x3b9aca00";
        int eth_maxPriorityFeePerGas = Integer.parseInt(hexString.substring(2), 16); // 去掉前缀"0x"并转换为10进制整数
        System.out.println("Hex: " + hexString + " -> eth_maxPriorityFeePerGas：Decimal: " + eth_maxPriorityFeePerGas); // 输出结果1000000000
        hexString = "0xad0a";
        int eth_estimateGas = Integer.parseInt(hexString.substring(2), 16); // 去掉前缀"0x"并转换为10进制整数
        System.out.println("Hex: " + hexString + " ->动作 eth_estimateGas：Decimal: " + eth_estimateGas); // 输出结果

        //gasLimit = eth_estimateGas × 12 / 10
        int gasLimit = eth_estimateGas * 12 / 10;
        System.out.println("gasLimit = eth_estimateGas × 12 / 10 = " + gasLimit);
        //tip = eth_maxPriorityFeePerGas
        //maxFeePerGas = eth_gasPrice × 2 + tip
        int tip = eth_maxPriorityFeePerGas;
        int maxFeePerGas = eth_gasPrice * 2 + tip;
        System.out.println("tip = eth_maxPriorityFeePerGas = " + tip);
        System.out.println("maxFeePerGas = eth_gasPrice × 2 + tip = " + maxFeePerGas);
        //手续费 wei = gasLimit × maxFeePerGas
        int feeWei = gasLimit * maxFeePerGas;
        System.out.println("手续费 wei = gasLimit × maxFeePerGas = " + feeWei);
        //手续费 eth = wei / 10^18
        double feeEth = (double) feeWei / Math.pow(10, 18);
        System.out.println("手续费 eth = wei / 10^18 = " + feeEth);

    }
}
