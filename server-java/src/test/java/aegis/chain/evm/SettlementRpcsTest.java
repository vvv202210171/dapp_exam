package aegis.chain.evm;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import aegis.platform.Support;
import org.junit.jupiter.api.Test;

class SettlementRpcsTest {
    /**
     * 不设或写成 http 时用手写客户端，写成 web3j 时用 web3j。别的值拒绝。
     */
    @Test
    void choosesClient() {
        Support.HttpJson http = new Support.HttpJson();
        assertInstanceOf(HttpSettlementRpc.class, SettlementRpcs.open("http", "http://127.0.0.1:8545", http));
        assertInstanceOf(HttpSettlementRpc.class, SettlementRpcs.open("", "http://127.0.0.1:8545", http));
        assertInstanceOf(Web3jSettlementRpc.class, SettlementRpcs.open("web3j", "http://127.0.0.1:8545", http));
        assertThrows(IllegalArgumentException.class, () -> SettlementRpcs.open("other", "http://127.0.0.1:8545", http));
    }
}
