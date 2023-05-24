package haveno.apitest.scenario.bot.protocol;

import haveno.apitest.method.BitcoinCliHelper;
import haveno.apitest.scenario.bot.BotClient;
import haveno.apitest.scenario.bot.RandomOffer;
import haveno.apitest.scenario.bot.script.BashScriptGenerator;
import haveno.apitest.scenario.bot.shutdown.ManualBotShutdownException;
import haveno.cli.table.builder.TableBuilder;
import haveno.proto.grpc.OfferInfo;
import haveno.proto.grpc.TradeInfo;
import lombok.extern.slf4j.Slf4j;
import protobuf.PaymentAccount;

import java.io.File;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static haveno.apitest.scenario.bot.protocol.ProtocolStep.DONE;
import static haveno.apitest.scenario.bot.protocol.ProtocolStep.WAIT_FOR_OFFER_TAKER;
import static haveno.apitest.scenario.bot.shutdown.ManualShutdown.checkIfShutdownCalled;
import static haveno.cli.table.builder.TableType.OFFER_TBL;
import static haveno.cli.table.builder.TableType.TRADE_DETAIL_TBL;

@Slf4j
public class MakerBotProtocol extends BotProtocol {

    public MakerBotProtocol(BotClient botClient,
                            PaymentAccount paymentAccount,
                            long protocolStepTimeLimitInMs,
                            BitcoinCliHelper bitcoinCli,
                            BashScriptGenerator bashScriptGenerator) {
        super(botClient,
                paymentAccount,
                protocolStepTimeLimitInMs,
                bitcoinCli,
                bashScriptGenerator);
    }

    @Override
    public void run() {
        checkIsStartStep();

        Function<Supplier<OfferInfo>, TradeInfo> makeTrade = waitForNewTrade.andThen(waitForTakerFeeTxConfirm);
        var trade = makeTrade.apply(randomOffer);

        var makerIsBuyer = trade.getOffer().getDirection().equalsIgnoreCase(BUY);
        Function<TradeInfo, TradeInfo> completeTraditionalTransaction = makerIsBuyer
                ? sendPaymentSentMessage.andThen(waitForPaymentReceivedConfirmation)
                : waitForPaymentSentMessage.andThen(sendPaymentReceivedMessage);
        completeTraditionalTransaction.apply(trade);

        currentProtocolStep = DONE;
    }

    private final Supplier<OfferInfo> randomOffer = () -> {
        checkIfShutdownCalled("Interrupted before creating random offer.");
        OfferInfo offer = new RandomOffer(botClient, paymentAccount).create().getOffer();
        log.info("Created random {} offer\n{}", currencyCode, new TableBuilder(OFFER_TBL, offer).build());
        return offer;
    };

    private final Function<Supplier<OfferInfo>, TradeInfo> waitForNewTrade = (randomOffer) -> {
        initProtocolStep.accept(WAIT_FOR_OFFER_TAKER);
        OfferInfo offer = randomOffer.get();
        createTakeOfferCliScript(offer);
        try {
            log.info("Impatiently waiting for offer {} to be taken, repeatedly calling gettrade.", offer.getId());
            while (isWithinProtocolStepTimeLimit()) {
                checkIfShutdownCalled("Interrupted while waiting for offer to be taken.");
                try {
                    var trade = getNewTrade(offer.getId());
                    if (trade.isPresent())
                        return trade.get();
                    else
                        sleep(randomDelay.get());
                } catch (Exception ex) {
                    throw new IllegalStateException(this.getBotClient().toCleanGrpcExceptionMessage(ex), ex);
                }
            } // end while
            throw new IllegalStateException("Offer was never taken; we won't wait any longer.");
        } catch (ManualBotShutdownException ex) {
            throw ex; // not an error, tells bot to shutdown
        } catch (Exception ex) {
            throw new IllegalStateException("Error while waiting for offer to be taken.", ex);
        }
    };

    private Optional<TradeInfo> getNewTrade(String offerId) {
        try {
            var trade = botClient.getTrade(offerId);
            log.info("Offer {} was taken, new trade:\n{}",
                    offerId,
                    new TableBuilder(TRADE_DETAIL_TBL, trade).build().toString());
            return Optional.of(trade);
        } catch (Exception ex) {
            // Get trade will throw a non-fatal gRPC exception if not found.
            log.info(this.getBotClient().toCleanGrpcExceptionMessage(ex));
            return Optional.empty();
        }
    }

    private void createTakeOfferCliScript(OfferInfo offer) {
        File script = bashScriptGenerator.createTakeOfferScript(offer);
        printCliHintAndOrScript(script, "The manual CLI side can take the offer");
    }
}
