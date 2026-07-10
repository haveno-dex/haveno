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

package haveno.core.support.dispute.arbitration;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.crypto.Hash;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.core.api.XmrConnectionService;
import haveno.core.api.CoreNotificationService;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOfferManager;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.DisputeSummaryVerification;
import haveno.core.support.dispute.messages.DisputeClosedMessage;
import haveno.core.support.dispute.messages.DisputeOpenedMessage;
import haveno.core.support.messages.ChatMessage;
import haveno.core.support.messages.SupportMessage;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.Trade.DisputeState;
import haveno.core.xmr.wallet.TradeWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.lang3.exception.ExceptionUtils;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
@Singleton
public final class ArbitrationManager extends DisputeManager<ArbitrationDisputeList> {

    private Map<String, Integer> reprocessDisputeClosedMessageCounts = new HashMap<>();

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public ArbitrationManager(P2PService p2PService,
                              TradeWalletService tradeWalletService,
                              XmrWalletService walletService,
                              XmrConnectionService xmrConnectionService,
                              CoreNotificationService notificationService,
                              TradeManager tradeManager,
                              ClosedTradableManager closedTradableManager,
                              OpenOfferManager openOfferManager,
                              KeyRing keyRing,
                              ArbitrationDisputeListService arbitrationDisputeListService,
                              Config config,
                              PriceFeedService priceFeedService) {
        super(p2PService, tradeWalletService, walletService, xmrConnectionService, notificationService, tradeManager, closedTradableManager,
                openOfferManager, keyRing, arbitrationDisputeListService, config, priceFeedService);
        HavenoUtils.arbitrationManager = this; // TODO: storing static reference, better way?
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Implement template methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public SupportType getSupportType() {
        return SupportType.ARBITRATION;
    }

    @Override
    public void onSupportMessage(DecryptedMessageWithPubKey decryptedMessageWithPubKey, SupportMessage message) {
        if (canProcessMessage(message)) {
            try {
                log.info("Received {} from {} with tradeId {} and uid {}", message.getClass().getSimpleName(), message.getSenderNodeAddress(), message.getTradeId(), message.getUid());
                super.onSupportMessage(decryptedMessageWithPubKey, message);
                if (message instanceof DisputeOpenedMessage) {
                    handle(decryptedMessageWithPubKey, (DisputeOpenedMessage) message);
                } else if (message instanceof ChatMessage) {
                    handle((ChatMessage) message);
                } else if (message instanceof DisputeClosedMessage) {
                    handle((DisputeClosedMessage) message);
                } else {
                    log.warn("Unsupported message at dispatchMessage. message={}", message);
                }
            } catch (Throwable t) {
                log.warn("Failed to process support message of type {} from {} with tradeId {} and uid {}. Error: {}", message.getClass().getSimpleName(), message.getSenderNodeAddress(), message.getTradeId(), message.getUid(), t.getMessage(), t);
            }
        }
    }

    @Override
    public NodeAddress getAgentNodeAddress(Dispute dispute) {
        return dispute.getContract().getArbitratorNodeAddress();
    }

    @Override
    protected AckMessageSourceType getAckMessageSourceType() {
        return AckMessageSourceType.ARBITRATION_MESSAGE;
    }

    @Override
    public void cleanupDisputes() {
        List<Dispute> disputes = getDisputeList().getList();
        synchronized (disputes) {

            // collect disputes to remove
            Set<Dispute> toRemoves = new HashSet<>();
            for (Dispute dispute : disputes) {

                // get dispute's trade
                final Trade trade = tradeManager.getTrade(dispute.getTradeId());
                if (trade == null) {
                    log.warn("Dispute trade {} does not exist", dispute.getTradeId());
                    continue;
                }
    
                // remove dispute if owned by arbitrator
                if (dispute.getTraderPubKeyRing().equals(trade.getArbitrator().getPubKeyRing())) {
                    log.warn("Removing invalid dispute opened by arbitrator, disputeId={}", trade.getId(), dispute.getId());
                    toRemoves.add(dispute);
                }

                // remove dispute if preparing
                if (trade.getDisputeState() == DisputeState.DISPUTE_PREPARING) {
                    log.warn("Removing dispute for {} {} with disputeState={}, disputeId={}", trade.getClass().getSimpleName(), trade.getId(), trade.getDisputeState(), dispute.getId());
                    toRemoves.add(dispute);
                }

                // remove dispute if requested and not stored in mailbox
                if (trade.getDisputeState() == DisputeState.DISPUTE_REQUESTED) {
                    boolean storedInMailbox = false;
                    for (ChatMessage msg : dispute.getChatMessages()) {
                        if (Boolean.TRUE.equals(msg.getStoredInMailboxProperty().get())) {
                            storedInMailbox = true;
                            log.info("Keeping dispute for {} {} with disputeState={}, disputeId={}. Stored in mailbox", trade.getClass().getSimpleName(), trade.getId(), trade.getDisputeState(), dispute.getId());
                            break;
                        }
                    }
                    if (!storedInMailbox) {
                        log.warn("Removing dispute for {} {} with disputeState={}, disputeId={}. Not stored in mailbox", trade.getClass().getSimpleName(), trade.getId(), trade.getDisputeState(), dispute.getId());
                        toRemoves.add(dispute);
                    }
                }
            }

            // remove disputes and reset state
            for (Dispute dispute : toRemoves) {
                getDisputeList().remove(dispute);

                // get dispute's trade
                final Trade trade = tradeManager.getTrade(dispute.getTradeId());
                if (trade == null) {
                    log.warn("Dispute trade {} does not exist", dispute.getTradeId());
                    continue;
                }
                trade.setDisputeState(DisputeState.NO_DISPUTE);
            }

            // close open disputes with published payout
            for (Dispute dispute : disputes) {

                // skip if dispute is closed
                if (dispute.isClosed()) continue;

                // get dispute's trade
                final Trade trade = tradeManager.getTrade(dispute.getTradeId());
                if (trade == null) {
                    log.warn("Dispute trade {} does not exist", dispute.getTradeId());
                    continue;
                }

                // skip if trade's payout is not published
                if (!trade.isPayoutPublished()) continue;

                // skip if arbitrator's peer dispute is closed
                Optional<Dispute> peersDisputeOptional = null;
                if (trade.isArbitrator()) {
                    peersDisputeOptional = getDisputesAsObservableList().stream()
                        .filter(d -> dispute.getTradeId().equals(d.getTradeId()) && dispute.getTraderId() != d.getTraderId())
                        .findFirst();
                    if (peersDisputeOptional.isPresent()) {
                        if (peersDisputeOptional.get().isClosed()) continue;
                    } else {
                        log.warn("No peer dispute found for disputeId={}, tradeId={}", dispute.getId(), dispute.getTradeId());
                        continue;
                    }
                }

                // close trade disputes if payout published
                log.warn("Auto-closing dispute for {} {} with published payout, disputeId={}", trade.getClass().getSimpleName(), trade.getId(), dispute.getId());
                dispute.setIsClosed();
                if (peersDisputeOptional != null && peersDisputeOptional.isPresent()) peersDisputeOptional.get().setIsClosed();
                trade.setDisputeState(Trade.DisputeState.DISPUTE_CLOSED);
            }
        }
    }

    @Override
    protected String getDisputeInfo(Dispute dispute) {
        String role = Res.get("shared.arbitrator").toLowerCase();
        String link = "https://docs.haveno.exchange/trading-rules.html#legacy-arbitration";
        return Res.get("support.initialInfo", role, role, link);
    }

    @Override
    protected String getDisputeIntroForPeer(String disputeInfo) {
        return Res.get("support.peerOpenedDispute", disputeInfo, Version.VERSION);
    }

    @Override
    protected String getDisputeIntroForDisputeCreator(String disputeInfo) {
        return Res.get("support.youOpenedDispute", disputeInfo, Version.VERSION);
    }

    @Override
    protected void addPriceInfoMessage(Dispute dispute, int counter) {
        // Arbitrator is not used anymore.
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    // received by both peers when arbitrator closes disputes
    @Override
    public void handle(DisputeClosedMessage disputeClosedMessage) {
        handle(disputeClosedMessage, true);
    }

    public void handle(DisputeClosedMessage disputeClosedMessage, boolean reprocessOnError) {

        // get dispute's trade
        final Trade trade = tradeManager.getTrade(disputeClosedMessage.getTradeId());
        if (trade == null) {
            log.warn("Dispute trade {} does not exist", disputeClosedMessage.getTradeId());
            return;
        }

        // set dispute closed message for reprocessing, keeping the newest signed closeDate
        // so an older ruling cannot displace a newer one by arriving later; synchronized on
        // the arbitrator peer, not the trade lock, which can be held across a UserThread wait
        synchronized (trade.getArbitrator()) {
            DisputeClosedMessage pendingMsg = trade.getArbitrator().getDisputeClosedMessage();
            if (pendingMsg != null && pendingMsg != disputeClosedMessage) {
                Date pendingCloseDate = pendingMsg.getDisputeResult().getCloseDate();
                Date incomingCloseDate = disputeClosedMessage.getDisputeResult().getCloseDate();
                if (pendingCloseDate.after(incomingCloseDate)) {
                    log.warn("Ignoring DisputeClosedMessage with an older closeDate than the pending message for {} {}", trade.getClass().getSimpleName(), trade.getId());
                    return;
                }
                // at equal closeDate, a replayed message without a payout tx must not displace one carrying it
                if (pendingCloseDate.equals(incomingCloseDate) && pendingMsg.getUnsignedPayoutTxHex() != null && disputeClosedMessage.getUnsignedPayoutTxHex() == null) {
                    log.warn("Ignoring DisputeClosedMessage without a payout tx because the pending message with the same closeDate has one for {} {}", trade.getClass().getSimpleName(), trade.getId());
                    return;
                }
            }
            trade.getArbitrator().setDisputeClosedMessage(disputeClosedMessage);
        }

        // process on initialization thread after delay to get latest message
        ThreadUtils.execute(() -> {

            // get latest message
            HavenoUtils.waitFor(100);
            if (disputeClosedMessage != trade.getArbitrator().getDisputeClosedMessage()) {
                log.info("Ignoring DisputeClosedMessage because a newer message was received for {} {}", trade.getClass().getSimpleName(), trade.getId());
                return;
            }

            // persist trade and return when processing on trade thread
            CountDownLatch initLatch = new CountDownLatch(1);
            trade.persistNow(() -> {

                // try to process dispute closed message
                ThreadUtils.execute(() -> {
                    initLatch.countDown();
                    ChatMessage chatMessage = null;
                    Dispute dispute = null;
                    synchronized (trade.getLock()) {
                        try {
                            DisputeResult disputeResult = disputeClosedMessage.getDisputeResult();
                            chatMessage = disputeResult.getChatMessage();
                            checkNotNull(chatMessage, "chatMessage must not be null");
                            String tradeId = disputeResult.getTradeId();

                            log.info("Processing {} for {} {}", disputeClosedMessage.getClass().getSimpleName(), trade.getClass().getSimpleName(), disputeResult.getTradeId());

                            // re-check that this message is still the pending ruling before mutating state
                            if (disputeClosedMessage != trade.getArbitrator().getDisputeClosedMessage()) {
                                log.info("Ignoring DisputeClosedMessage because a newer message was received for {} {}", trade.getClass().getSimpleName(), trade.getId());
                                return;
                            }

                            // get dispute
                            Optional<Dispute> disputeOptional = findDispute(disputeResult);
                            String uid = disputeClosedMessage.getUid();
                            if (!disputeOptional.isPresent()) {
                                if (!delayMsgMap.containsKey(uid)) {
                                    log.warn("We got a dispute closed msg but we don't have a matching dispute. " +
                                            "That might happen when we get the DisputeClosedMessage before the dispute was created. " +
                                            "We try again after 2 sec. to apply the DisputeClosedMessage. TradeId = " + tradeId);
                                    // We delay 2 sec. to be sure the comm. msg gets added first
                                    Timer timer = UserThread.runAfter(() -> handle(disputeClosedMessage), 2);
                                    delayMsgMap.put(uid, timer);
                                    return;
                                }

                                // register missing dispute from verified message, e.g. when the DisputeOpenedMessage was never processed
                                log.warn("We got a dispute closed msg and still don't have a matching dispute after a delay, " +
                                        "so we register the dispute from the verified message. TradeId = " + tradeId);
                                try {
                                    dispute = registerDisputeFromClosedMessage(trade, disputeClosedMessage);
                                } catch (Exception e) {
                                    log.warn("Failed to register dispute from DisputeClosedMessage. TradeId = " + tradeId + ", error=" + e.getMessage());
                                    return;
                                }
                            } else {
                                dispute = disputeOptional.get();
                            }

                            // verify arbitrator signature of the summary text
                            String summaryText = chatMessage.getMessage();
                            if (summaryText == null || summaryText.isEmpty()) throw new IllegalArgumentException("Summary text for dispute is missing, tradeId=" + tradeId + (dispute == null ? "" : ", disputeId=" + dispute.getId()));
                            PubKeyRing arbitratorPubKeyRing = trade.getArbitrator().getPubKeyRing();
                            checkNotNull(arbitratorPubKeyRing, "Arbitrator pub key ring is null for trade " + tradeId);
                            DisputeSummaryVerification.verifySignature(summaryText, arbitratorPubKeyRing);

                            // verify arbitrator signature of the payout fields
                            if (disputeResult.getArbitratorSignature() == null) throw new IllegalArgumentException("DisputeResult is missing the arbitrator's payout signature, tradeId=" + tradeId + ", disputeId=" + dispute.getId());
                            HavenoUtils.verifySignature(arbitratorPubKeyRing, Hash.getSha256Hash(disputeResult.getPayoutSignaturePayload()), disputeResult.getArbitratorSignature());

                            // verify arbitrator does not receive DisputeClosedMessage
                            if (keyRing.getPubKeyRing().equals(arbitratorPubKeyRing)) {
                                log.error("Arbitrator received disputeResultMessage. That should never happen.");
                                clearPendingDisputeClosedMessage(trade, disputeClosedMessage); // don't reprocess
                                return;
                            }

                            // reject a stale ruling before mutating state; freshness is the signed closeDate,
                            // and a re-opened dispute (ruling recorded, not closed) requires a strictly newer one
                            cleanupRetryMap(uid);
                            DisputeResult storedDisputeResult = dispute.disputeResultProperty().get();
                            if (storedDisputeResult != null) {
                                long incomingCloseDate = disputeResult.getCloseDate().getTime();
                                long storedCloseDate = storedDisputeResult.getCloseDate().getTime();

                                // defer an equal-closeDate resend while a re-open awaits acknowledgement, keeping the
                                // pending message, so the retained ruling is neither published against the re-open nor lost
                                boolean reopenInFlight = trade.getDisputeState() == Trade.DisputeState.DISPUTE_PREPARING || trade.getDisputeState() == Trade.DisputeState.DISPUTE_REQUESTED;
                                if (incomingCloseDate == storedCloseDate && dispute.isClosed() && reopenInFlight) {
                                    log.info("Deferring DisputeClosedMessage processing while a dispute re-open awaits acknowledgement for {} {}", trade.getClass().getSimpleName(), tradeId);
                                    return;
                                }
                                if (incomingCloseDate < storedCloseDate || (incomingCloseDate == storedCloseDate && !dispute.isClosed())) {
                                    log.warn("Ignoring DisputeClosedMessage without a newer closeDate than the stored ruling for {} {}", trade.getClass().getSimpleName(), tradeId);
                                    clearPendingDisputeClosedMessage(trade, disputeClosedMessage); // roll back so the stale ruling is not reprocessed
                                    sendAckMessage(chatMessage, dispute.getAgentPubKeyRing(), true, null); // ack as handled duplicate
                                    requestPersistence(trade);
                                    return;
                                }
                                // a strictly newer ruling supersedes any previously signed payout, which must not be
                                // substituted at submission; an equal closeDate is a resend of the same ruling
                                if (incomingCloseDate > storedCloseDate) {
                                    if (!trade.isPayoutPublished()) trade.setPayoutTxHex(null);
                                } else {
                                    log.info("We already got a dispute result, indicating the message was resent after updating multisig info. TradeId = " + tradeId);
                                }
                            }

                            // set dispute state
                            synchronized (dispute.getChatMessages()) {
                                if (!dispute.getChatMessages().contains(chatMessage)) {
                                    dispute.addAndPersistChatMessage(chatMessage);
                                } else {
                                    log.warn("We got a dispute mail msg that we have already stored. TradeId = " + chatMessage.getTradeId());
                                }
                            }
                            dispute.setIsClosed();
                            dispute.setDisputeResult(disputeResult);

                            // set updated multisig info
                            if (disputeClosedMessage.getUpdatedMultisigHex() != null) trade.getArbitrator().setUpdatedMultisigHex(disputeClosedMessage.getUpdatedMultisigHex());
                            
                            // update wallet
                            if (trade.walletExists() || !trade.isPayoutPublished()) trade.updateWallet();

                            // attempt to sign and publish dispute payout tx if given and not already published
                            if (!trade.isPayoutPublished() && disputeClosedMessage.getUnsignedPayoutTxHex() != null) {

                                // wait to sign and publish payout tx if defer flag set
                                if (disputeClosedMessage.isDeferPublishPayout()) {
                                    log.info("Deferring signing and publishing dispute payout tx for {} {}", trade.getClass().getSimpleName(), trade.getId());
                                    trade.pollWalletNormallyForMs(Trade.POLL_WALLET_NORMALLY_DEFAULT_PERIOD_MS); // override idling
                                    for (int i = 0; i < 5; i++) {
                                        if (trade.isPayoutPublished()) break;
                                        HavenoUtils.waitFor(Trade.DEFER_PUBLISH_MS / 5);
                                    }
                                    if (!trade.isPayoutPublished()) trade.updateWallet();
                                }

                                // abort after waits and wallet i/o if a newer ruling replaced this message,
                                // so a superseded payout is not published; the newer message processes next
                                if (disputeClosedMessage != trade.getArbitrator().getDisputeClosedMessage()) {
                                    log.warn("Aborting dispute payout publication because a newer DisputeClosedMessage was received for {} {}", trade.getClass().getSimpleName(), trade.getId());
                                    return;
                                }

                                // sign and publish dispute payout tx if peer still has not published
                                if (trade.isPayoutPublished()) {
                                    log.info("Dispute payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
                                } else {
                                    try {
                                        log.info("Signing and publishing dispute payout tx for {} {}", trade.getClass().getSimpleName(), trade.getId());
                                        trade.processDisputePayoutTx();
                                    } catch (Exception e) {

                                        // check if payout published again
                                        trade.updateWallet();
                                        if (trade.isPayoutPublished()) {
                                            log.warn("Payout tx already published for {} {}, skipping dispute processing", trade.getClass().getSimpleName(), trade.getId());
                                        } else {
                                            if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) throw e;
                                            else throw new RuntimeException("Failed to sign and publish dispute payout tx from arbitrator for " + trade.getClass().getSimpleName() + " " + tradeId + ": " + e.getMessage(), e);
                                        }
                                    }
                                }
                            } else {
                                if (trade.isPayoutPublished()) log.info("Dispute payout tx already published for {} {}", trade.getClass().getSimpleName(), trade.getId());
                                else if (disputeClosedMessage.getUnsignedPayoutTxHex() == null) log.info("{} did not receive unsigned dispute payout tx for trade {} because the arbitrator did not have their updated multisig info (can happen if trader went offline after trade started)", trade.getClass().getSimpleName(), trade.getId());
                            }

                            // complete disputed trade
                            if (trade.isPayoutPublished()) {
                                tradeManager.closeDisputedTrade(trade.getId(), Trade.DisputeState.DISPUTE_CLOSED);
                            }

                            // We use the chatMessage as we only persist those not the DisputeClosedMessage.
                            // If we would use the DisputeClosedMessage we could not lookup for the msg when we receive the AckMessage.
                            sendAckMessage(chatMessage, dispute.getAgentPubKeyRing(), true, null);
                            requestPersistence(trade);
                        } catch (Exception e) {
                            log.warn("Error processing dispute closed message: {}", e.getMessage());
                            log.warn(ExceptionUtils.getStackTrace(e));
                            requestPersistence(trade);

                            // nack bad message and do not reprocess
                            if (HavenoUtils.isIllegal(e)) {
                                trade.setPayoutTxHex(null); // clear signed payout tx hex
                                clearPendingDisputeClosedMessage(trade, disputeClosedMessage); // message is processed
                                trade.setDisputeState(Trade.DisputeState.DISPUTE_CLOSED);
                                String warningMsg = "Error processing dispute closed message: " +  e.getMessage() + "\n\nOpen another dispute to try again (ctrl+o).";
                                trade.prependErrorMessage(warningMsg);
                                sendAckMessage(chatMessage, dispute.getAgentPubKeyRing(), false, e.getMessage());
                                HavenoUtils.havenoSetup.getTopErrorMsg().set(warningMsg);
                                requestPersistence(trade);
                                throw e;
                            }

                            // schedule to reprocess message unless deleted
                            if (trade.getArbitrator().getDisputeClosedMessage() != null && reprocessOnError) {
                                if (!reprocessDisputeClosedMessageCounts.containsKey(trade.getId())) reprocessDisputeClosedMessageCounts.put(trade.getId(), 0);
                                UserThread.runAfter(() -> {
                                    reprocessDisputeClosedMessageCounts.put(trade.getId(), reprocessDisputeClosedMessageCounts.get(trade.getId()) + 1); // increment reprocess count
                                    maybeReprocessDisputeClosedMessage(trade, reprocessOnError);
                                }, trade.getReprocessDelayInSeconds(reprocessDisputeClosedMessageCounts.get(trade.getId())));
                            }
                        }
                    }
                }, trade.getId());
            });
            HavenoUtils.awaitLatch(initLatch);
        }, trade.getProtocol().getInitId()); // TODO: getInitId() should be private. ideally this function is moved to TradeProtocol, but logic above depends on SupportManager internals
    }

    // clear the pending dispute closed message unless a newer one has been installed since
    private static void clearPendingDisputeClosedMessage(Trade trade, DisputeClosedMessage processedMsg) {
        synchronized (trade.getArbitrator()) {
            if (trade.getArbitrator().getDisputeClosedMessage() == processedMsg) trade.getArbitrator().setDisputeClosedMessage(null);
        }
    }

    // register dispute from a verified DisputeClosedMessage so the arbitrator's resolution applies without a locally opened dispute
    private Dispute registerDisputeFromClosedMessage(Trade trade, DisputeClosedMessage disputeClosedMessage) {
        DisputeResult disputeResult = disputeClosedMessage.getDisputeResult();

        // verify arbitrator signatures of summary text and payout fields
        PubKeyRing arbitratorPubKeyRing = trade.getArbitrator().getPubKeyRing();
        checkNotNull(arbitratorPubKeyRing, "Arbitrator pub key ring is null for trade " + trade.getId());
        DisputeSummaryVerification.verifySignature(disputeResult.getChatMessage().getMessage(), arbitratorPubKeyRing);
        checkNotNull(disputeResult.getArbitratorSignature(), "DisputeResult is missing the arbitrator's payout signature for trade " + trade.getId());
        HavenoUtils.verifySignature(arbitratorPubKeyRing, Hash.getSha256Hash(disputeResult.getPayoutSignaturePayload()), disputeResult.getArbitratorSignature());

        // verify the dispute result is in our context
        if (disputeResult.getTraderId() != keyRing.getPubKeyRing().hashCode()) throw new IllegalArgumentException("DisputeResult trader id does not match our pub key ring, tradeId=" + trade.getId());

        // create dispute with peer as opener, since ours would exist if we had opened it
        Dispute dispute = new Dispute(new Date().getTime(),
                trade.getId(),
                keyRing.getPubKeyRing().hashCode(),
                false,
                !trade.isBuyer(),
                !trade.isMaker(),
                keyRing.getPubKeyRing(),
                trade.getDate().getTime(),
                trade.getMaxTradePeriodDate().getTime(),
                trade.getContract(),
                trade.getContractHash(),
                null,
                null,
                trade.getContractAsJson(),
                trade.getMaker().getContractSignature(),
                trade.getTaker().getContractSignature(),
                trade.getMaker().getPaymentAccountPayload(),
                trade.getTaker().getPaymentAccountPayload(),
                arbitratorPubKeyRing,
                false,
                getSupportType());

        // add system message for peer-opened dispute
        ChatMessage chatMessage = new ChatMessage(
                getSupportType(),
                dispute.getTradeId(),
                keyRing.getPubKeyRing().hashCode(),
                false,
                Res.get("support.systemMsg", getDisputeIntroForPeer(getDisputeInfo(dispute))),
                p2PService.getAddress());
        chatMessage.setSystemMessage(true);
        dispute.addAndPersistChatMessage(chatMessage);

        // add dispute on user thread and wait so it's found when processing continues
        CountDownLatch latch = new CountDownLatch(1);
        UserThread.execute(() -> {
            getDisputeList().add(dispute);
            latch.countDown();
        });
        HavenoUtils.awaitLatch(latch);

        // update trade state
        trade.advanceDisputeState(Trade.DisputeState.DISPUTE_OPENED);
        requestPersistence(trade);
        return dispute;
    }

    public void maybeReprocessDisputeClosedMessage(Trade trade, boolean reprocessOnError) {
        if (trade.isShutDownStarted()) return;
        ThreadUtils.execute(() -> {
            synchronized (trade.getLock()) {

                // skip if no need to reprocess
                if (trade.isArbitrator() || trade.getArbitrator().getDisputeClosedMessage() == null || trade.getArbitrator().getDisputeClosedMessage().getUnsignedPayoutTxHex() == null || trade.getDisputeState().ordinal() >= Trade.DisputeState.DISPUTE_CLOSED.ordinal()) {
                    return;
                }

                log.warn("Reprocessing dispute closed message for {} {}", trade.getClass().getSimpleName(), trade.getId());
                handle(trade.getArbitrator().getDisputeClosedMessage(), reprocessOnError);
            }
        }, trade.getId());
    }
}
