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

package haveno.core.app;

import com.google.inject.Inject;
import haveno.common.ClockWatcher;
import haveno.common.persistence.PersistenceManager;
import haveno.core.account.sign.SignedWitnessService;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.alert.PrivateNotificationManager;
import haveno.core.alert.PrivateNotificationPayload;
import haveno.core.filter.FilterManager;
import haveno.core.notifications.MobileNotificationService;
import haveno.core.notifications.alerts.DisputeMsgEvents;
import haveno.core.notifications.alerts.MyOfferTakenEvents;
import haveno.core.notifications.alerts.TradeEvents;
import haveno.core.notifications.alerts.market.MarketAlerts;
import haveno.core.notifications.alerts.price.PriceAlert;
import haveno.core.offer.OpenOfferManager;
import haveno.core.offer.TriggerPriceService;
import haveno.core.payment.AmazonGiftCardAccount;
import haveno.core.payment.RevolutAccount;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.support.dispute.refund.refundagent.RefundAgentManager;
import haveno.core.support.traderchat.TraderChatManager;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.TradeManager;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.User;
import haveno.core.xmr.Balances;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.mailbox.MailboxMessageService;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Handles the initialisation of domain classes. We should refactor to the model that the domain classes listen on the
 * relevant start up state from AppStartupState instead to get called. Only for initialisation which has a required
 * order we will still need this class. For now it helps to keep HavenoSetup more focussed on the process and not getting
 * overloaded with domain initialisation code.
 */
public class DomainInitialisation {
    private final ClockWatcher clockWatcher;
    private final ArbitrationManager arbitrationManager;
    private final MediationManager mediationManager;
    private final RefundManager refundManager;
    private final TraderChatManager traderChatManager;
    private final TradeManager tradeManager;
    private final ClosedTradableManager closedTradableManager;
    private final FailedTradesManager failedTradesManager;
    private final OpenOfferManager openOfferManager;
    private final Balances balances;
    private final WalletAppSetup walletAppSetup;
    private final ArbitratorManager arbitratorManager;
    private final MediatorManager mediatorManager;
    private final RefundAgentManager refundAgentManager;
    private final PrivateNotificationManager privateNotificationManager;
    private final P2PService p2PService;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final SignedWitnessService signedWitnessService;
    private final PriceFeedService priceFeedService;
    private final FilterManager filterManager;
    private final MobileNotificationService mobileNotificationService;
    private final MyOfferTakenEvents myOfferTakenEvents;
    private final TradeEvents tradeEvents;
    private final DisputeMsgEvents disputeMsgEvents;
    private final PriceAlert priceAlert;
    private final MarketAlerts marketAlerts;
    private final User user;
    private final TriggerPriceService triggerPriceService;
    private final MailboxMessageService mailboxMessageService;

    @Inject
    public DomainInitialisation(ClockWatcher clockWatcher,
                                ArbitrationManager arbitrationManager,
                                MediationManager mediationManager,
                                RefundManager refundManager,
                                TraderChatManager traderChatManager,
                                TradeManager tradeManager,
                                ClosedTradableManager closedTradableManager,
                                FailedTradesManager failedTradesManager,
                                OpenOfferManager openOfferManager,
                                Balances balances,
                                WalletAppSetup walletAppSetup,
                                ArbitratorManager arbitratorManager,
                                MediatorManager mediatorManager,
                                RefundAgentManager refundAgentManager,
                                PrivateNotificationManager privateNotificationManager,
                                P2PService p2PService,
                                TradeStatisticsManager tradeStatisticsManager,
                                AccountAgeWitnessService accountAgeWitnessService,
                                SignedWitnessService signedWitnessService,
                                PriceFeedService priceFeedService,
                                FilterManager filterManager,
                                MobileNotificationService mobileNotificationService,
                                MyOfferTakenEvents myOfferTakenEvents,
                                TradeEvents tradeEvents,
                                DisputeMsgEvents disputeMsgEvents,
                                PriceAlert priceAlert,
                                MarketAlerts marketAlerts,
                                User user,
                                TriggerPriceService triggerPriceService,
                                MailboxMessageService mailboxMessageService) {
        this.clockWatcher = clockWatcher;
        this.arbitrationManager = arbitrationManager;
        this.mediationManager = mediationManager;
        this.refundManager = refundManager;
        this.traderChatManager = traderChatManager;
        this.tradeManager = tradeManager;
        this.closedTradableManager = closedTradableManager;
        this.failedTradesManager = failedTradesManager;
        this.openOfferManager = openOfferManager;
        this.balances = balances;
        this.walletAppSetup = walletAppSetup;
        this.arbitratorManager = arbitratorManager;
        this.mediatorManager = mediatorManager;
        this.refundAgentManager = refundAgentManager;
        this.privateNotificationManager = privateNotificationManager;
        this.p2PService = p2PService;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.signedWitnessService = signedWitnessService;
        this.priceFeedService = priceFeedService;
        this.filterManager = filterManager;
        this.mobileNotificationService = mobileNotificationService;
        this.myOfferTakenEvents = myOfferTakenEvents;
        this.tradeEvents = tradeEvents;
        this.disputeMsgEvents = disputeMsgEvents;
        this.priceAlert = priceAlert;
        this.marketAlerts = marketAlerts;
        this.user = user;
        this.triggerPriceService = triggerPriceService;
        this.mailboxMessageService = mailboxMessageService;
    }

    public void initDomainServices(Consumer<String> rejectedTxErrorMessageHandler,
                                   Consumer<PrivateNotificationPayload> displayPrivateNotificationHandler,
                                   Consumer<String> filterWarningHandler,
                                   Consumer<List<RevolutAccount>> revolutAccountsUpdateHandler,
                                   Consumer<List<AmazonGiftCardAccount>> amazonGiftCardAccountsUpdateHandler) {
        clockWatcher.start();

        PersistenceManager.onAllServicesInitialized();

        arbitratorManager.onAllServicesInitialized();
        mediatorManager.onAllServicesInitialized();
        refundAgentManager.onAllServicesInitialized();

        tradeManager.onAllServicesInitialized();
        arbitrationManager.onAllServicesInitialized();
        mediationManager.onAllServicesInitialized();
        refundManager.onAllServicesInitialized();
        traderChatManager.onAllServicesInitialized();

        closedTradableManager.onAllServicesInitialized();
        failedTradesManager.onAllServicesInitialized();

        openOfferManager.onAllServicesInitialized();

        balances.onAllServicesInitialized();

        walletAppSetup.setRejectedTxErrorMessageHandler(rejectedTxErrorMessageHandler, openOfferManager, tradeManager);

        privateNotificationManager.privateNotificationProperty().addListener((observable, oldValue, newValue) -> {
            if (displayPrivateNotificationHandler != null)
                displayPrivateNotificationHandler.accept(newValue);
        });

        p2PService.onAllServicesInitialized();

        tradeStatisticsManager.onAllServicesInitialized();

        accountAgeWitnessService.onAllServicesInitialized();
        signedWitnessService.onAllServicesInitialized();

        priceFeedService.setCurrencyCodeOnInit();
        priceFeedService.startRequestingPrices();

        filterManager.setFilterWarningHandler(filterWarningHandler);
        filterManager.onAllServicesInitialized();


        mobileNotificationService.onAllServicesInitialized();
        myOfferTakenEvents.onAllServicesInitialized();
        tradeEvents.onAllServicesInitialized();
        disputeMsgEvents.onAllServicesInitialized();
        priceAlert.onAllServicesInitialized();
        marketAlerts.onAllServicesInitialized();
        triggerPriceService.onAllServicesInitialized();

        mailboxMessageService.onAllServicesInitialized();

        if (revolutAccountsUpdateHandler != null && user.getPaymentAccountsAsObservable() != null) {
            revolutAccountsUpdateHandler.accept(user.getPaymentAccountsAsObservable().stream()
                    .filter(paymentAccount -> paymentAccount instanceof RevolutAccount)
                    .map(paymentAccount -> (RevolutAccount) paymentAccount)
                    .filter(RevolutAccount::userNameNotSet)
                    .collect(Collectors.toList()));
        }
        if (amazonGiftCardAccountsUpdateHandler != null && user.getPaymentAccountsAsObservable() != null) {
            amazonGiftCardAccountsUpdateHandler.accept(user.getPaymentAccountsAsObservable().stream()
                    .filter(paymentAccount -> paymentAccount instanceof AmazonGiftCardAccount)
                    .map(paymentAccount -> (AmazonGiftCardAccount) paymentAccount)
                    .filter(AmazonGiftCardAccount::countryNotSet)
                    .collect(Collectors.toList()));
        }
    }
}
