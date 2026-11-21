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

package haveno.desktop.main.offer.quicktrade;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferFilterService;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountFactory;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.User;
import haveno.core.util.PriceUtil;
import haveno.desktop.main.offer.offerbook.OfferBook;
import haveno.desktop.main.offer.offerbook.OfferBookListItem;
import haveno.desktop.maker.OfferMaker;
import haveno.desktop.maker.PreferenceMakers;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.natpryce.makeiteasy.MakeItEasy.make;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class QuickTradeModelTest {
    private static final long XMR = 1000000000000L;

    private final ObservableList<OfferBookListItem> items = FXCollections.observableArrayList();
    private final Set<PaymentAccount> accounts = new HashSet<>();
    private OfferFilterService offerFilterService;
    private AccountAgeWitnessService accountAgeWitnessService;
    private QuickTradeModel model;

    @BeforeEach
    public void setUp() {
        GlobalSettings.setDefaultTradeCurrency(new TraditionalCurrency("EUR"));
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");

        OfferBook offerBook = mock(OfferBook.class);
        when(offerBook.getOfferBookListItems()).thenReturn(items);
        User user = mock(User.class);
        when(user.getPaymentAccounts()).thenReturn(accounts);
        offerFilterService = mock(OfferFilterService.class);
        when(offerFilterService.canTakeOffer(any(), anyBoolean())).thenReturn(OfferFilterService.Result.HAS_NO_PAYMENT_ACCOUNT_VALID_FOR_OFFER);
        accountAgeWitnessService = mock(AccountAgeWitnessService.class);
        when(accountAgeWitnessService.getUnsignedTradeLimit(any(), any(), any())).thenReturn(3 * XMR);
        TradeStatisticsManager tradeStatisticsManager = mock(TradeStatisticsManager.class);
        when(tradeStatisticsManager.getObservableTradeStatisticsList()).thenReturn(FXCollections.observableArrayList());
        PriceUtil priceUtil = new PriceUtil(mock(PriceFeedService.class), tradeStatisticsManager, PreferenceMakers.empty);

        model = new QuickTradeModel(user, offerBook, offerFilterService, mock(OpenOfferManager.class), priceUtil, accountAgeWitnessService);
        model.direction = OfferDirection.BUY;
        model.currency = new TraditionalCurrency("EUR");
    }

    private Offer addOffer(String id, OfferDirection direction, String currencyCode, String paymentMethodId, double price, double minAmount, double amount) {
        Offer offer = make(OfferMaker.xmrUsdOffer.but(with(OfferMaker.id, id), with(OfferMaker.direction, direction),
                with(OfferMaker.counterCurrencyCode, currencyCode), with(OfferMaker.paymentMethodId, paymentMethodId),
                with(OfferMaker.price, Math.round(price * 100000000L)),
                with(OfferMaker.minAmount, Math.round(minAmount * XMR)), with(OfferMaker.amount, Math.round(amount * XMR))));
        items.add(new OfferBookListItem(offer));
        return offer;
    }

    private List<String> getOfferIds() {
        return model.getOffers().stream().map(Offer::getId).collect(Collectors.toList());
    }

    @Test
    public void testOffersForAnswersWithBestPriceFirst() {
        addOffer("sepa530", OfferDirection.SELL, "EUR", "SEPA", 530, 0.1, 1);
        addOffer("sepa520", OfferDirection.SELL, "EUR", "SEPA", 520, 0.1, 1);
        addOffer("revolut", OfferDirection.SELL, "EUR", "REVOLUT", 510, 0.1, 1);
        addOffer("usd", OfferDirection.SELL, "USD", "ZELLE", 500, 0.1, 1);
        addOffer("buy540", OfferDirection.BUY, "EUR", "SEPA", 540, 0.1, 1);
        addOffer("buy550", OfferDirection.BUY, "EUR", "SEPA", 550, 0.1, 1);

        // a buyer takes sell offers, the lowest price first
        model.paymentMethod = PaymentMethod.SEPA;
        model.refreshOffers();
        assertEquals(List.of("sepa520", "sepa530"), getOfferIds());
        assertEquals(3, model.getNumOffers(model.currency));
        assertEquals(2, model.getNumOffers(PaymentMethod.SEPA));
        assertEquals("EUR", model.getCurrencies().get(0).getCode());
        assertEquals(PaymentMethod.SEPA, model.getPaymentMethods().get(0));

        // a seller takes buy offers, the highest price first
        model.direction = OfferDirection.SELL;
        model.refreshOffers();
        assertEquals(List.of("buy550", "buy540"), getOfferIds());
    }

    @Test
    public void testOffersWhichCannotBeTakenAreLeftOut() {
        addOffer("valid", OfferDirection.SELL, "EUR", "SEPA", 500, 0.1, 1);
        addOffer("newAccount", OfferDirection.SELL, "EUR", "SEPA", 510, 0.1, 1);
        addOffer("aboveNewAccountLimit", OfferDirection.SELL, "EUR", "SEPA", 520, 4, 5);
        addOffer("banned", OfferDirection.SELL, "EUR", "SEPA", 530, 0.1, 1);
        when(offerFilterService.canTakeOffer(any(), anyBoolean())).thenAnswer(invocation -> switch (((Offer) invocation.getArgument(0)).getId()) {
            case "valid" -> OfferFilterService.Result.VALID;
            case "banned" -> OfferFilterService.Result.IS_NODE_ADDRESS_BANNED;
            default -> OfferFilterService.Result.HAS_NO_PAYMENT_ACCOUNT_VALID_FOR_OFFER;
        });

        model.refreshOffers();
        assertEquals(List.of("valid", "newAccount"), getOfferIds());
        assertTrue(model.canTakeOffer(model.getOffers().get(0)));
        assertFalse(model.canTakeOffer(model.getOffers().get(1)));
    }

    @Test
    public void testAmountRangeIsCappedByTradeLimit() {
        Offer offer = addOffer("offer", OfferDirection.SELL, "EUR", "SEPA", 500, 0.5, 5);

        // a new account trades up to 3 XMR of the offer's 0.5 to 5 XMR
        assertEquals(BigInteger.valueOf(3 * XMR), model.getMaxAmount(offer));
        assertTrue(model.isInRange(offer, Volume.parse("250", "EUR")));
        assertTrue(model.isInRange(offer, Volume.parse("1500", "EUR")));
        assertFalse(model.isInRange(offer, Volume.parse("200", "EUR")));
        assertFalse(model.isInRange(offer, Volume.parse("2000", "EUR")));
        assertEquals(BigInteger.valueOf(XMR), model.getAmount(offer, Volume.parse("500", "EUR")));
    }

    @Test
    public void testAccountForNewOffer() {
        model.paymentMethod = PaymentMethod.SEPA;
        assertNull(model.getAccountForNewOffer());
        assertFalse(model.hasAccount(PaymentMethod.SEPA));

        PaymentAccount account = PaymentAccountFactory.getPaymentAccount(PaymentMethod.SEPA);
        account.init();
        accounts.add(account);
        assertNotNull(model.getAccountForNewOffer());
        assertTrue(model.hasAccount(PaymentMethod.SEPA));
        assertFalse(model.hasAccount(PaymentMethod.REVOLUT));

        // a crypto currency has no payment method to choose and needs an account on its blockchain
        model.currency = new CryptoCurrency("BTC", "Bitcoin");
        model.paymentMethod = null;
        assertTrue(model.getPaymentMethods().isEmpty());
        assertNull(model.getAccountForNewOffer());
        assertEquals(PaymentMethod.BLOCK_CHAINS, model.getAccountPaymentMethod(null));
    }
}
