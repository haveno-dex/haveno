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
import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.BuyerTrade;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.MakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.Trade.State;
import haveno.core.trade.messages.SignContractRequest;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.model.MoneroOutput;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

// TODO (woodser): separate classes for deposit tx creation and contract request, or combine into ProcessInitMultisigRequest
@Slf4j
public class MaybeSendSignContractRequest extends TradeTask {

    private boolean ack1 = false; // TODO (woodser) these represent onArrived(), not the ack
    private boolean ack2 = false;

    @SuppressWarnings({"unused"})
    public MaybeSendSignContractRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();

          // skip if arbitrator
          if (trade instanceof ArbitratorTrade) {
              complete();
              return;
          }

          // skip if multisig wallet not complete
          if (processModel.getMultisigAddress() == null) {
              complete();
              return;
          }

          // skip if deposit tx already created
          if (processModel.getDepositTxXmr() != null) {
              complete();
              return;
          }

          // initialize progress steps
          trade.addInitProgressStep();

          // create deposit tx and freeze inputs
          Integer subaddressIndex = null;
          boolean reserveExactAmount = false;
          if (trade instanceof MakerTrade) {
            reserveExactAmount = processModel.getOpenOfferManager().getOpenOfferById(trade.getId()).get().isReserveExactAmount();
            if (reserveExactAmount) subaddressIndex = model.getXmrWalletService().getAddressEntry(trade.getId(), XmrAddressEntry.Context.OFFER_FUNDING).get().getSubaddressIndex();
          }
          MoneroTxWallet depositTx = trade.getXmrWalletService().createDepositTx(trade, reserveExactAmount, subaddressIndex);

          // collect reserved key images
          List<String> reservedKeyImages = new ArrayList<String>();
          for (MoneroOutput input : depositTx.getInputs()) reservedKeyImages.add(input.getKeyImage().getHex());

          // save process state
          processModel.setDepositTxXmr(depositTx); // TODO: redundant with trade.getSelf().setDepositTx(), remove?
          trade.getSelf().setDepositTx(depositTx);
          trade.getSelf().setDepositTxHash(depositTx.getHash());
          trade.getSelf().setDepositTxFee(depositTx.getFee());
          trade.getSelf().setReserveTxKeyImages(reservedKeyImages);
          trade.getSelf().setPayoutAddressString(trade.getXmrWalletService().getOrCreateAddressEntry(processModel.getOffer().getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString()); // TODO (woodser): allow custom payout address?
          trade.getSelf().setPaymentAccountPayload(trade.getProcessModel().getPaymentAccountPayload(trade.getSelf().getPaymentAccountId()));

          // TODO: security deposit should be based on trade amount, not max offer amount
          BigInteger securityDeposit = trade instanceof BuyerTrade ? trade.getBuyerSecurityDepositBeforeMiningFee() : trade.getSellerSecurityDepositBeforeMiningFee();
          trade.getSelf().setSecurityDeposit(securityDeposit.subtract(depositTx.getFee()));

          // maker signs deposit hash nonce to avoid challenge protocol
          byte[] sig = null;
          if (trade instanceof MakerTrade) {
            sig = HavenoUtils.sign(processModel.getP2PService().getKeyRing(), depositTx.getHash());
          }

          // create request for peer and arbitrator to sign contract
          SignContractRequest request = new SignContractRequest(
                  trade.getOffer().getId(),
                  UUID.randomUUID().toString(),
                  Version.getP2PMessageVersion(),
                  new Date().getTime(),
                  trade.getProcessModel().getAccountId(),
                  trade.getSelf().getPaymentAccountPayload().getHash(),
                  trade.getSelf().getPayoutAddressString(),
                  depositTx.getHash(),
                  sig);

          // send request to trading peer
          processModel.getP2PService().sendEncryptedDirectMessage(trade.getTradePeer().getNodeAddress(), trade.getTradePeer().getPubKeyRing(), request, new SendDirectMessageListener() {
              @Override
              public void onArrived() {
                  log.info("{} arrived: trading peer={}; offerId={}; uid={}", request.getClass().getSimpleName(), trade.getTradePeer().getNodeAddress(), trade.getId());
                  ack1 = true;
                  if (ack1 && ack2) completeAux();
              }
              @Override
              public void onFault(String errorMessage) {
                  log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), trade.getTradePeer().getNodeAddress(), trade.getId(), errorMessage);
                  appendToErrorMessage("Sending message failed: message=" + request + "\nerrorMessage=" + errorMessage);
                  failed();
              }
          });

          // send request to arbitrator
          processModel.getP2PService().sendEncryptedDirectMessage(trade.getArbitrator().getNodeAddress(), trade.getArbitrator().getPubKeyRing(), request, new SendDirectMessageListener() {
              @Override
              public void onArrived() {
                  log.info("{} arrived: trading peer={}; offerId={}; uid={}", request.getClass().getSimpleName(), trade.getArbitrator().getNodeAddress(), trade.getId());
                  ack2 = true;
                  if (ack1 && ack2) completeAux();
              }
              @Override
              public void onFault(String errorMessage) {
                  log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), trade.getArbitrator().getNodeAddress(), trade.getId(), errorMessage);
                  appendToErrorMessage("Sending message failed: message=" + request + "\nerrorMessage=" + errorMessage);
                  failed();
              }
          });
        } catch (Throwable t) {
          failed(t);
        }
    }

    private void completeAux() {
        trade.setState(State.CONTRACT_SIGNATURE_REQUESTED);
        trade.addInitProgressStep();
        processModel.getTradeManager().requestPersistence();
        complete();
    }
}
