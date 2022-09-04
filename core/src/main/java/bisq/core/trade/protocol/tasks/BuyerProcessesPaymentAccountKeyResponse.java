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

package bisq.core.trade.protocol.tasks;


import bisq.common.crypto.Encryption;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.proto.network.CoreNetworkProtoResolver;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentAccountKeyResponse;
import java.time.Clock;
import java.util.Arrays;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BuyerProcessesPaymentAccountKeyResponse extends TradeTask {
    
    @SuppressWarnings({"unused"})
    public BuyerProcessesPaymentAccountKeyResponse(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();

          // update peer node address if not from arbitrator
          if (!processModel.getTempTradingPeerNodeAddress().equals(trade.getArbitratorNodeAddress())) {
              trade.setTradingPeerNodeAddress(processModel.getTempTradingPeerNodeAddress());
          }

          // buyer may already have decrypted payment account payload from arbitrator request
          if (trade.getTradingPeer().getPaymentAccountPayload() != null) {
              complete();
              return;
          }

          // get peer's payment account payload key
          PaymentAccountKeyResponse request = (PaymentAccountKeyResponse) processModel.getTradeMessage(); // TODO (woodser): verify request
          trade.getTradingPeer().setPaymentAccountKey(request.getPaymentAccountKey());

          // decrypt peer's payment account payload
          SecretKey sk = Encryption.getSecretKeyFromBytes(trade.getTradingPeer().getPaymentAccountKey());
          byte[] decryptedPaymentAccountPayload = Encryption.decrypt(trade.getTradingPeer().getEncryptedPaymentAccountPayload(), sk);
          CoreNetworkProtoResolver resolver = new CoreNetworkProtoResolver(Clock.systemDefaultZone()); // TODO: reuse resolver from elsewhere?
          PaymentAccountPayload paymentAccountPayload = resolver.fromProto(protobuf.PaymentAccountPayload.parseFrom(decryptedPaymentAccountPayload));

          // verify hash of payment account payload
          byte[] peerPaymentAccountPayloadHash = trade instanceof MakerTrade ? trade.getContract().getTakerPaymentAccountPayloadHash() : trade.getContract().getMakerPaymentAccountPayloadHash();
          if (!Arrays.equals(paymentAccountPayload.getHash(), peerPaymentAccountPayloadHash)) throw new RuntimeException("Hash of peer's payment account payload does not match contract");

          // set payment account payload
          trade.getTradingPeer().setPaymentAccountPayload(paymentAccountPayload);

          // store updated multisig hex for processing on payment sent
          trade.getTradingPeer().setUpdatedMultisigHex(request.getUpdatedMultisigHex());

          // persist and complete
          processModel.getTradeManager().requestPersistence();
          complete();
        } catch (Throwable t) {
          failed(t);
        }
    }
}
