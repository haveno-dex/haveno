package haveno.core.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

public class HavenoUtilsTest {

    @Test
    public void testDaemonAndWalletFunctionLocksAreShared() {
        Object daemonLock = HavenoUtils.getDaemonLock();

        assertSame(daemonLock, HavenoUtils.getDaemonLock());
        assertSame(daemonLock, HavenoUtils.getWalletFunctionLock());
    }
}
