package aegis;

import org.junit.jupiter.api.Test;

/**
 * 让 Maven 测试跑 SelfTest。
 */
class SelfTestTest {
    /**
     * 覆盖金额、合约 calldata 和卡账本。
     */
    @Test
    void moneyAbiAndLedger() throws Exception {
        SelfTest.main(new String[0]);
    }
}
