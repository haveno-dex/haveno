/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.trade.protocol.tasks;

import haveno.common.app.Version;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.messages.SignContractRequest;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;
import monero.daemon.model.MoneroNetworkType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProcessSignContractRequestTest {
    private static final String ADDRESS = "shared-multisig-address";
    private static final String PAYOUT = "4BJHitCigGy6giuYsJFP26KGkTKiQDJ6HJP1pan2ir2CCV8Twc2WWmo4fu1NVXt8XLGYAkjo5cJ3yH68Lfz9ZXEUJ9MeqPW";
    private final TradePeer maker = new TradePeer();
    private Trade trade;
    private ProcessModel processModel;
    private TaskRunner<Trade> runner;
    private ResultHandler resultHandler;
    private ErrorMessageHandler errorHandler;

    @BeforeEach
    void setUp() {
        Version.setBaseCryptoNetworkId(1);
        trade = mock(Trade.class);
        processModel = mock(ProcessModel.class);
        resultHandler = mock(ResultHandler.class);
        errorHandler = mock(ErrorMessageHandler.class);
        runner = new TaskRunner<>(trade, Trade.class, resultHandler, errorHandler);
        runner.addTasks(ProcessSignContractRequest.class);
        NodeAddress sender = new NodeAddress("maker", 1);
        when(trade.getProcessModel()).thenReturn(processModel);
        when(trade.getId()).thenReturn("trade");
        when(trade.getMaker()).thenReturn(maker);
        when(trade.getTradePeer(sender)).thenReturn(maker);
        when(processModel.getTempTradePeerNodeAddress()).thenReturn(sender);
        when(processModel.getMultisigAddress()).thenReturn(ADDRESS);
        when(processModel.getMaker()).thenReturn(maker);
        when(processModel.getTaker()).thenReturn(new TradePeer());
        when(processModel.getTradeManager()).thenReturn(mock(TradeManager.class));
    }

    @Test
    void missingAddressFailsBeforePeerMutation() {
        assertRejectedAddress(null);
    }

    @Test
    void emptyAddressFailsBeforePeerMutation() {
        assertRejectedAddress("");
    }

    @Test
    void blankAddressFailsBeforePeerMutation() {
        assertRejectedAddress(" ");
    }

    @Test
    void mismatchedAddressFailsBeforePeerMutation() {
        assertRejectedAddress("different-address");
    }

    private void assertRejectedAddress(String address) {
        runRequest(address);
        verify(errorHandler).handleErrorMessage(contains("Missing or mismatched multisig address"));
        verify(resultHandler, never()).handleResult();
        verify(trade, never()).createContract();
        assertNull(maker.getPaymentAccountPayloadHash());
        assertNull(maker.getAccountId());
        assertNull(maker.getDepositTxHash());
        assertNull(maker.getPayoutAddressString());
        assertNull(maker.getAccountAgeWitnessNonce());
    }

    @Test
    void matchingAddressAcceptsRequestAfterWireRoundTrip() {
        try (MockedStatic<XmrWalletService> wallet = mockStatic(XmrWalletService.class)) {
            wallet.when(XmrWalletService::getMoneroNetworkType).thenReturn(MoneroNetworkType.MAINNET);
            runRequest(ADDRESS);
        }
        verify(resultHandler).handleResult();
        assertEquals("account", maker.getAccountId());
        assertEquals(PAYOUT, maker.getPayoutAddressString());
        assertArrayEquals(new byte[32], maker.getPaymentAccountPayloadHash());
        verify(trade, never()).createContract(); // The second peer has not requested a signature yet.
    }

    private void runRequest(String address) {
        SignContractRequest request = new SignContractRequest("trade", "uid", Version.getP2PMessageVersion(), 1,
                "account", new byte[32], PAYOUT, "deposit-hash", new byte[]{1}, address);
        SignContractRequest received = SignContractRequest.fromProto(request.toProtoNetworkEnvelope().getSignContractRequest(), null, Version.getP2PMessageVersion());
        when(processModel.getTradeMessage()).thenReturn(received);
        runner.run();
    }
}
