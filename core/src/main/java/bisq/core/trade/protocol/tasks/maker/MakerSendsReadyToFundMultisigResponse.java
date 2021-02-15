/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.trade.protocol.tasks.maker;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Date;
import java.util.UUID;

import bisq.common.app.Version;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.common.util.Utilities;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.trade.BuyerAsMakerTrade;
import bisq.core.trade.Contract;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.MakerReadyToFundMultisigResponse;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class MakerSendsReadyToFundMultisigResponse extends TradeTask {
    @SuppressWarnings({"unused"})
    public MakerSendsReadyToFundMultisigResponse(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            System.out.println("MAKER SENDING READY TO FUND MULTISIG RESPONSE");
            
            // determine if maker is ready to fund to-be-created multisig
            XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
            MoneroWallet wallet = walletService.getWallet();
            wallet.sync();
            MoneroTxWallet offerFeeTx = wallet.getTx(trade.getOffer().getOfferFeePaymentTxId());
            if (offerFeeTx.isFailed()) throw new RuntimeException("Offer fee tx has failed"); // TODO (woodser): proper error handling
            System.out.println("Offer fee num confirmations; " + offerFeeTx.getNumConfirmations());
            System.out.println("Offer fee is locked; " + offerFeeTx.isLocked());
            boolean makerReadyToFundMultisigResponse =  !offerFeeTx.isLocked();
            
            String contractAsJson = null;
            String contractSignature = null;
            String payoutAddress = null;
            
            // TODO (woodser): creating and signing contract here, but should do this in own task handler
            if (makerReadyToFundMultisigResponse) {
              
              TradingPeer taker = processModel.getTradingPeer();
              PaymentAccountPayload makerPaymentAccountPayload = processModel.getPaymentAccountPayload(trade);
              checkNotNull(makerPaymentAccountPayload, "makerPaymentAccountPayload must not be null");
              PaymentAccountPayload takerPaymentAccountPayload = checkNotNull(taker.getPaymentAccountPayload());
              boolean isBuyerMakerAndSellerTaker = trade instanceof BuyerAsMakerTrade;

              NodeAddress buyerNodeAddress = isBuyerMakerAndSellerTaker ? processModel.getMyNodeAddress() : processModel.getTempTradingPeerNodeAddress();
              NodeAddress sellerNodeAddress = isBuyerMakerAndSellerTaker ? processModel.getTempTradingPeerNodeAddress() : processModel.getMyNodeAddress();
              String id = processModel.getOffer().getId();
              
              // get maker payout address
              XmrAddressEntry makerPayoutEntry = walletService.getNewAddressEntry(id, XmrAddressEntry.Context.TRADE_PAYOUT);
              checkNotNull(taker.getPayoutAddressString(), "taker.getPayoutAddressString()");

//              checkArgument(!walletService.getAddressEntry(id, XmrAddressEntry.Context.MULTI_SIG).isPresent(), "addressEntry must not be set here.");
//              XmrAddressEntry makerAddressEntry = walletService.getOrCreateAddressEntry(id, XmrAddressEntry.Context.MULTI_SIG);
//              byte[] makerMultiSigPubKey = makerAddressEntry.getPubKey();
              
              checkNotNull(processModel.getAccountId(), "processModel.getAccountId() must not be null");

              checkNotNull(trade.getTradeAmount(), "trade.getTradeAmount() must not be null");
              Contract contract = new Contract(
                      processModel.getOffer().getOfferPayload(),
                      trade.getTradeAmount().value,
                      trade.getTradePrice().getValue(),
                      buyerNodeAddress,
                      sellerNodeAddress,
                      trade.getArbitratorNodeAddress(),
                      isBuyerMakerAndSellerTaker,
                      processModel.getAccountId(),
                      taker.getAccountId(),
                      makerPaymentAccountPayload,
                      takerPaymentAccountPayload,
                      processModel.getPubKeyRing(),
                      taker.getPubKeyRing(),
                      makerPayoutEntry.getAddressString(),
                      taker.getPayoutAddressString(),
                      trade.getLockTime()
              );
              contractAsJson = Utilities.objectToJson(contract);
              contractSignature = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), contractAsJson);
              payoutAddress = makerPayoutEntry.getAddressString();

              trade.setContract(contract);
              trade.setContractAsJson(contractAsJson);
              trade.setMakerContractSignature(contractSignature);
              System.out.println("Contract as json:");
              System.out.println(contractAsJson);
              
              processModel.getTradeManager().requestPersistence();
            }
            
            // create message to indicate if maker is ready to fund to-be-created multisig wallet
            System.out.println("BUILDING READY RESPONSE");
            System.out.println("Payout address: " + payoutAddress);
            System.out.println("Account id: " + processModel.getAccountId());
            MakerReadyToFundMultisigResponse message = new MakerReadyToFundMultisigResponse(
                    processModel.getOffer().getId(),
                    makerReadyToFundMultisigResponse,
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    contractAsJson,
                    contractSignature,
                    payoutAddress,
                    processModel.getPaymentAccountPayload(trade),
                    processModel.getAccountId(),
                    new Date().getTime());
            
            // send message to taker
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getTakerNodeAddress(),
                    trade.getTakerPubKeyRing(),
                    message,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at taker: offerId={}; uid={}", message.getClass().getSimpleName(), message.getTradeId(), message.getUid());
                            complete();
                        }
                        @Override
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}", message.getClass().getSimpleName(), message.getUid(), trade.getTradingPeerNodeAddress(), errorMessage);
                            appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
                            failed();
                        }
                    }
            );
        } catch (Throwable t) {
            failed(t);
        }
    }
}
