package haveno.apitest.scenario.bot.protocol;

import haveno.apitest.method.BitcoinCliHelper;
import haveno.apitest.scenario.bot.BotClient;
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
import static haveno.apitest.scenario.bot.protocol.ProtocolStep.FIND_OFFER;
import static haveno.apitest.scenario.bot.protocol.ProtocolStep.TAKE_OFFER;
import static haveno.apitest.scenario.bot.shutdown.ManualShutdown.checkIfShutdownCalled;
import static haveno.cli.table.builder.TableType.OFFER_TBL;
import static haveno.core.payment.payload.PaymentMethod.F2F_ID;

@Slf4j
public class TakerBotProtocol extends BotProtocol {

    public TakerBotProtocol(BotClient botClient,
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

        Function<OfferInfo, TradeInfo> takeTrade = takeOffer.andThen(waitForTakerFeeTxConfirm);
        var trade = takeTrade.apply(findOffer.get());

        var takerIsSeller = trade.getOffer().getDirection().equalsIgnoreCase(BUY);
        Function<TradeInfo, TradeInfo> completeTraditionalTransaction = takerIsSeller
                ? waitForPaymentSentMessage.andThen(sendPaymentReceivedMessage)
                : sendPaymentSentMessage.andThen(waitForPaymentReceivedConfirmation);
        completeTraditionalTransaction.apply(trade);

        currentProtocolStep = DONE;
    }

    private final Supplier<Optional<OfferInfo>> firstOffer = () -> {
        var offers = botClient.getOffers(currencyCode);
        if (offers.size() > 0) {
            log.info("Offers found:\n{}", new TableBuilder(OFFER_TBL, offers).build());
            OfferInfo offer = offers.get(0);
            log.info("Will take first offer {}", offer.getId());
            return Optional.of(offer);
        } else {
            log.info("No buy or sell {} offers found.", currencyCode);
            return Optional.empty();
        }
    };

    private final Supplier<OfferInfo> findOffer = () -> {
        initProtocolStep.accept(FIND_OFFER);
        createMakeOfferScript();
        try {
            log.info("Impatiently waiting for at least one {} offer to be created, repeatedly calling getoffers.", currencyCode);
            while (isWithinProtocolStepTimeLimit()) {
                checkIfShutdownCalled("Interrupted while checking offers.");
                try {
                    Optional<OfferInfo> offer = firstOffer.get();
                    if (offer.isPresent())
                        return offer.get();
                    else
                        sleep(randomDelay.get());
                } catch (Exception ex) {
                    throw new IllegalStateException(this.getBotClient().toCleanGrpcExceptionMessage(ex), ex);
                }
            } // end while
            throw new IllegalStateException("Offer was never created; we won't wait any longer.");
        } catch (ManualBotShutdownException ex) {
            throw ex; // not an error, tells bot to shutdown
        } catch (Exception ex) {
            throw new IllegalStateException("Error while waiting for a new offer.", ex);
        }
    };

    private final Function<OfferInfo, TradeInfo> takeOffer = (offer) -> {
        initProtocolStep.accept(TAKE_OFFER);
        checkIfShutdownCalled("Interrupted before taking offer.");
        return botClient.takeOffer(offer.getId(), paymentAccount);
    };

    private void createMakeOfferScript() {
        String direction = RANDOM.nextBoolean() ? "BUY" : "SELL";
        boolean createMarginPricedOffer = RANDOM.nextBoolean();
        // If not using an F2F account, don't go over possible 0.01 BTC
        // limit if account is not signed.
        String amount = paymentAccount.getPaymentMethod().getId().equals(F2F_ID)
                ? "0.25"
                : "0.01";
        File script;
        if (createMarginPricedOffer) {
            script = bashScriptGenerator.createMakeMarginPricedOfferScript(direction,
                    currencyCode,
                    amount,
                    "0.0",
                    "15.0");
        } else {
            script = bashScriptGenerator.createMakeFixedPricedOfferScript(direction,
                    currencyCode,
                    amount,
                    botClient.getCurrentBTCMarketPriceAsIntegerString(currencyCode),
                    "15.0");
        }
        printCliHintAndOrScript(script, "The manual CLI side can create an offer");
    }
}
