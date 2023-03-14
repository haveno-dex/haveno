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


import com.google.common.base.Charsets;
import haveno.common.app.Version;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.Hash;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.ScryptUtil;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.Contract;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.Trade.State;
import haveno.core.trade.messages.SignContractRequest;
import haveno.core.trade.messages.SignContractResponse;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.util.JsonUtil;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class ProcessSignContractRequest extends TradeTask {

    private boolean ack1 = false;
    private boolean ack2 = false;

    @SuppressWarnings({"unused"})
    public ProcessSignContractRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();

          // extract fields from request
          // TODO (woodser): verify request and from maker or taker
          SignContractRequest request = (SignContractRequest) processModel.getTradeMessage();
          TradePeer trader = trade.getTradePeer(processModel.getTempTradePeerNodeAddress());
          trader.setDepositTxHash(request.getDepositTxHash());
          trader.setAccountId(request.getAccountId());
          trader.setPaymentAccountPayloadHash(request.getPaymentAccountPayloadHash());
          trader.setPayoutAddressString(request.getPayoutAddress());

          // maker sends witness signature of deposit tx hash
          if (trader == trade.getMaker()) {
            trader.setAccountAgeWitnessNonce(request.getDepositTxHash().getBytes(Charsets.UTF_8));
            trader.setAccountAgeWitnessSignature(request.getAccountAgeWitnessSignatureOfDepositHash());
          }

          // sign contract only when both deposit txs hashes known
          // TODO (woodser): remove makerDepositTxId and takerDepositTxId from Trade
          if (processModel.getMaker().getDepositTxHash() == null || processModel.getTaker().getDepositTxHash() == null) {
              complete();
              return;
          }

          // create and sign contract
          Contract contract = trade.createContract();
          String contractAsJson = JsonUtil.objectToJson(contract);
          byte[] signature = HavenoUtils.sign(processModel.getKeyRing(), contractAsJson);

          // save contract and signature
          trade.setContract(contract);
          trade.setContractAsJson(contractAsJson);
          trade.setContractHash(Hash.getSha256Hash(checkNotNull(contractAsJson)));
          trade.getSelf().setContractSignature(signature);

          // traders send encrypted payment account payload
          byte[] encryptedPaymentAccountPayload = null;
          if (!trade.isArbitrator()) {

              // generate random key to encrypt payment account payload
              byte[] decryptionKey = ScryptUtil.getKeyCrypterScrypt().deriveKey(UUID.randomUUID().toString()).getKey();
              trade.getSelf().setPaymentAccountKey(decryptionKey);

              // encrypt payment account payload
              byte[] unencrypted = trade.getSelf().getPaymentAccountPayload().toProtoMessage().toByteArray();
              SecretKey sk = Encryption.getSecretKeyFromBytes(trade.getSelf().getPaymentAccountKey());
              encryptedPaymentAccountPayload = Encryption.encrypt(unencrypted, sk);
          }

          // create response with contract signature
          SignContractResponse response = new SignContractResponse(
                  trade.getOffer().getId(),
                  UUID.randomUUID().toString(),
                  Version.getP2PMessageVersion(),
                  new Date().getTime(),
                  contractAsJson,
                  signature,
                  encryptedPaymentAccountPayload);

          // get response recipients. only arbitrator sends response to both peers
          NodeAddress recipient1 = trade instanceof ArbitratorTrade ? trade.getMaker().getNodeAddress() : trade.getTradePeer().getNodeAddress();
          PubKeyRing recipient1PubKey = trade instanceof ArbitratorTrade ? trade.getMaker().getPubKeyRing() : trade.getTradePeer().getPubKeyRing();
          NodeAddress recipient2 = trade instanceof ArbitratorTrade ? trade.getTaker().getNodeAddress() : null;
          PubKeyRing recipient2PubKey = trade instanceof ArbitratorTrade ? trade.getTaker().getPubKeyRing() : null;

          // send response to recipient 1
          processModel.getP2PService().sendEncryptedDirectMessage(recipient1, recipient1PubKey, response, new SendDirectMessageListener() {
              @Override
              public void onArrived() {
                  log.info("{} arrived: trading peer={}; offerId={}; uid={}", response.getClass().getSimpleName(), recipient1, trade.getId());
                  ack1 = true;
                  if (ack1 && (recipient2 == null || ack2)) completeAux();
              }
              @Override
              public void onFault(String errorMessage) {
                  log.error("Sending {} failed: uid={}; peer={}; error={}", response.getClass().getSimpleName(), recipient1, trade.getId(), errorMessage);
                  appendToErrorMessage("Sending message failed: message=" + response + "\nerrorMessage=" + errorMessage);
                  failed();
              }
          });

          // send response to recipient 2 if applicable
          if (recipient2 != null) {
              processModel.getP2PService().sendEncryptedDirectMessage(recipient2, recipient2PubKey, response, new SendDirectMessageListener() {
                  @Override
                  public void onArrived() {
                      log.info("{} arrived: trading peer={}; offerId={}; uid={}", response.getClass().getSimpleName(), recipient2, trade.getId());
                      ack2 = true;
                      if (ack1 && ack2) completeAux();
                  }
                  @Override
                  public void onFault(String errorMessage) {
                      log.error("Sending {} failed: uid={}; peer={}; error={}", response.getClass().getSimpleName(), recipient2, trade.getId(), errorMessage);
                      appendToErrorMessage("Sending message failed: message=" + response + "\nerrorMessage=" + errorMessage);
                      failed();
                  }
              });
          }
        } catch (Throwable t) {
          failed(t);
        }
    }

    private void completeAux() {
        trade.setState(State.CONTRACT_SIGNED);
        processModel.getTradeManager().requestPersistence();
        complete();
    }
}
