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

package haveno.core.payment;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.api.model.PaymentAccountForm;
import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.filter.FilterManager;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OfferRestrictions;
import haveno.core.offer.OfferUtil;
import haveno.core.payment.payload.CustomPaymentMethodAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.statistics.ReferralIdService;
import haveno.core.trade.statistics.TradeStatistics3;
import haveno.core.xmr.wallet.Restrictions;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CustomPaymentMethodTest {
    private static final String ACCOUNT_DATA = "Jane Doe\n+254 700 000000";

    // other tests replace static fields of PaymentMethod with mocks, so the payment method is taken from the list
    private static final PaymentMethod CUSTOM = PaymentMethod.getPaymentMethod(PaymentMethod.CUSTOM_ID);

    @BeforeAll
    public static void setUp() {
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Payment method
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Test
    public void customPaymentMethodIsATraditionalPaymentMethodForEveryFiatCurrency() {
        assertTrue(CUSTOM.isTraditional());
        assertTrue(PaymentMethod.getPaymentMethods().contains(CUSTOM));
        assertEquals(CurrencyUtil.getAllFiatCurrencies(), PaymentAccountUtil.getTradeCurrencies(CUSTOM));
        assertEquals("Custom payment method", CUSTOM.getDisplayString());
        assertEquals("Custom", CUSTOM.getShortName());
    }

    // signing would not start for a payment method which few traders use, so the limit is fixed instead
    @Test
    public void tradeLimitIsThatOfUnsignedBuyersWithChargebackRisk() {
        assertFalse(PaymentMethod.hasChargebackRisk(CUSTOM, "USD"));
        assertEquals(HavenoUtils.xmrToAtomicUnits(3), CUSTOM.getMaxTradeLimit("USD"));
        assertEquals(CUSTOM.getMaxTradeLimit("USD"), CUSTOM.getMaxTradeLimit("KES"));
        assertEquals(CUSTOM.getMaxTradeLimit("USD"), CUSTOM.getMaxTradeLimit("SF"));
        assertTrue(CUSTOM.getMaxTradeLimit("USD").compareTo(OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT) <= 0);
    }

    // a position in the payment method list of the trade statistics must not change
    @Test
    public void tradeStatisticKeepsThePaymentMethod() {
        TradeStatistics3 statistic = new TradeStatistics3("KES", 1, 1, PaymentMethod.CUSTOM_ID, 0, null, null, null);
        assertEquals(PaymentMethod.CUSTOM_ID, statistic.getPaymentMethodId());
        assertEquals("76", statistic.toProtoMessage().getTradeStatistics3().getPaymentMethod());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Account
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Test
    public void accountSurvivesProtobufRoundTrip() {
        CustomPaymentMethodAccount account = newAccount("Orange Money", "KES");

        PaymentAccount restored = PaymentAccount.fromProto(account.toProtoMessage(), new CoreProtoResolver());

        assertNotNull(restored);
        assertTrue(restored instanceof CustomPaymentMethodAccount);
        assertEquals(account, restored);
        assertEquals("Orange Money", ((CustomPaymentMethodAccount) restored).getPaymentMethodName());
        assertEquals(ACCOUNT_DATA, ((CustomPaymentMethodAccount) restored).getAccountData());
        assertEquals(new TraditionalCurrency("KES"), restored.getSingleTradeCurrency());
    }

    @Test
    public void accountStoresTextsWithoutCharactersWhichAreNotAllowed() {
        CustomPaymentMethodAccount account = newAccount(" Orange​ Money\n ", "KES");
        account.setAccountData("‮Jane Doe\n+254 700 000000\u0000 ");

        assertEquals("Orange Money", account.getPaymentMethodName());
        assertEquals(ACCOUNT_DATA, account.getAccountData());
    }

    @Test
    public void accountAgeWitnessInputIsThePaymentMethodIdAndTheAccountData() {
        assertEquals("CUSTOM" + ACCOUNT_DATA,
                new String(newAccount("Orange Money", "KES").getPaymentAccountPayload().getAgeWitnessInputData(), StandardCharsets.UTF_8));
    }

    @Test
    public void paymentDetailsShowTheNameAndTheAccountDataInOneLine() {
        assertEquals("Custom payment method - Name of the payment method: Orange Money, Account data: Jane Doe, +254 700 000000",
                newAccount("Orange Money", "KES").getPaymentAccountPayload().getPaymentDetails());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Account form
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Test
    public void formCreatesAnAccount() {
        PaymentAccountForm form = newForm("Orange Money ", ACCOUNT_DATA);

        PaymentAccount account = form.toPaymentAccount();
        account.validateForm(form);

        assertTrue(account instanceof CustomPaymentMethodAccount);
        assertEquals("Orange Money", ((CustomPaymentMethodAccount) account).getPaymentMethodName());
        assertEquals(ACCOUNT_DATA, ((CustomPaymentMethodAccount) account).getAccountData());
        assertEquals(new TraditionalCurrency("KES"), account.getSingleTradeCurrency());
        assertEquals("Orange Money", account.toForm().getValue(PaymentAccountFormField.FieldId.PAYMENT_METHOD_NAME));
        assertEquals(ACCOUNT_DATA, account.toForm().getValue(PaymentAccountFormField.FieldId.ACCOUNT_DATA));
    }

    @Test
    public void formRefusesTextsWhichAreNotValid() {
        for (String name : List.of("", "--", "a".repeat(CustomPaymentMethodUtil.MAX_NAME_LENGTH + 1), "SEPA", "zelle")) {
            PaymentAccountForm form = newForm(name, ACCOUNT_DATA);
            assertThrows(IllegalArgumentException.class, () -> form.toPaymentAccount().validateForm(form), name);
        }
        for (String accountData : List.of("", "---", "a".repeat(CustomPaymentMethodUtil.MAX_ACCOUNT_DATA_LENGTH + 1))) {
            PaymentAccountForm form = newForm("Orange Money", accountData);
            assertThrows(IllegalArgumentException.class, () -> form.toPaymentAccount().validateForm(form), accountData);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Test
    public void offerOfAnAccountCarriesTheName() {
        AccountAgeWitnessService accountAgeWitnessService = mock(AccountAgeWitnessService.class);
        when(accountAgeWitnessService.getMyWitnessHashAsHex(any())).thenReturn("00");
        ReferralIdService referralIdService = mock(ReferralIdService.class);
        when(referralIdService.getOptionalReferralId()).thenReturn(Optional.empty());
        OfferUtil offerUtil = new OfferUtil(accountAgeWitnessService, null, null, null, null, referralIdService);
        PaymentAccount cashAtAtmAccount = PaymentAccountFactory.getPaymentAccount(PaymentMethod.getPaymentMethod(PaymentMethod.CASH_AT_ATM_ID));
        cashAtAtmAccount.init();

        assertEquals("Orange Money", offerUtil.getExtraDataMap(newAccount("Orange Money", "KES"), "KES", OfferDirection.SELL, false).get(OfferPayload.CUSTOM_PAYMENT_METHOD_NAME));
        assertFalse(offerUtil.getExtraDataMap(cashAtAtmAccount, "KES", OfferDirection.SELL, false).containsKey(OfferPayload.CUSTOM_PAYMENT_METHOD_NAME));
    }

    // an imported account has not passed the account form
    @Test
    public void noOfferIsCreatedForAnAccountWithoutValidTexts() {
        P2PService p2PService = mock(P2PService.class);
        when(p2PService.getAddress()).thenReturn(new NodeAddress("maker.onion", 9999));
        OfferUtil offerUtil = new OfferUtil(null, mock(FilterManager.class), null, null, p2PService, null);
        double securityDeposit = Restrictions.getMinSecurityDepositPct();

        offerUtil.validateOfferData(securityDeposit, newAccount("Orange Money", "KES"), "KES");

        for (String name : List.of("", "--", "a".repeat(CustomPaymentMethodUtil.MAX_NAME_LENGTH + 1))) {
            CustomPaymentMethodAccount account = newAccount("Orange Money", "KES");
            ((CustomPaymentMethodAccountPayload) account.getPaymentAccountPayload()).setPaymentMethodName(name);
            assertThrows(IllegalArgumentException.class, () -> offerUtil.validateOfferData(securityDeposit, account, "KES"), name);
        }
        for (String accountData : List.of("", "---", "a".repeat(CustomPaymentMethodUtil.MAX_ACCOUNT_DATA_LENGTH + 1))) {
            CustomPaymentMethodAccount account = newAccount("Orange Money", "KES");
            account.setAccountData(accountData);
            assertThrows(IllegalArgumentException.class, () -> offerUtil.validateOfferData(securityDeposit, account, "KES"), accountData);
        }
    }

    // the name in an offer is a text of the maker, so a name which breaks the rules is not shown and matches no account
    @Test
    public void offerWithoutAValidNameHasNoName() {
        assertEquals("Orange Money", offer("KES", PaymentMethod.CUSTOM_ID, "Orange Money").getCustomPaymentMethodName());
        assertEquals("", offer("KES", PaymentMethod.CUSTOM_ID, null).getCustomPaymentMethodName());
        assertEquals("", offer("KES", PaymentMethod.CUSTOM_ID, "").getCustomPaymentMethodName());
        assertEquals("", offer("KES", PaymentMethod.CUSTOM_ID, "--").getCustomPaymentMethodName());
        assertEquals("", offer("KES", PaymentMethod.CUSTOM_ID, "Orange Money\nfree XMR").getCustomPaymentMethodName());
        assertEquals("", offer("KES", PaymentMethod.CUSTOM_ID, "abc‮def").getCustomPaymentMethodName());
        assertEquals("", offer("KES", PaymentMethod.CASH_AT_ATM_ID, "Orange Money").getCustomPaymentMethodName());
    }

    // the prefix tells that the name is a text of the maker and no supported payment method
    @Test
    public void offerShowsTheNameAfterTheCustomPaymentMethod() {
        assertEquals("Custom: Orange Money", offer("KES", PaymentMethod.CUSTOM_ID, "Orange Money").getPaymentMethodNameWithCountryCode());
        assertEquals("Custom payment method: Orange Money", offer("KES", PaymentMethod.CUSTOM_ID, "Orange Money").getPaymentMethodDisplayString());
        assertEquals("Custom: N/A", offer("KES", PaymentMethod.CUSTOM_ID, "--").getPaymentMethodNameWithCountryCode());
        assertEquals("Cardless Cash", offer("KES", PaymentMethod.CASH_AT_ATM_ID, "Orange Money").getPaymentMethodNameWithCountryCode());
        assertEquals("Cardless Cash", offer("KES", PaymentMethod.CASH_AT_ATM_ID, null).getPaymentMethodDisplayString());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Account and offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Test
    public void accountMatchesOnlyAnOfferWithItsNameAndCurrency() {
        CustomPaymentMethodAccount account = newAccount("Orange Money", "KES");

        assertTrue(PaymentAccountUtil.isPaymentAccountValidForOffer(offer("KES", PaymentMethod.CUSTOM_ID, "Orange Money"), account));
        assertTrue(PaymentAccountUtil.isPaymentAccountValidForOffer(offer("KES", PaymentMethod.CUSTOM_ID, "orange-money"), account));
        assertFalse(PaymentAccountUtil.isPaymentAccountValidForOffer(offer("KES", PaymentMethod.CUSTOM_ID, "Airtel Money"), account));
        assertFalse(PaymentAccountUtil.isPaymentAccountValidForOffer(offer("TZS", PaymentMethod.CUSTOM_ID, "Orange Money"), account));
        assertFalse(PaymentAccountUtil.isPaymentAccountValidForOffer(offer("KES", PaymentMethod.CUSTOM_ID, null), account));
        assertFalse(PaymentAccountUtil.isPaymentAccountValidForOffer(offer("KES", PaymentMethod.CASH_AT_ATM_ID, "Orange Money"), account));
    }

    @Test
    public void accountWithoutValidTextsMatchesNoOffer() {
        Offer offer = offer("KES", PaymentMethod.CUSTOM_ID, "Orange Money");
        CustomPaymentMethodAccount account = newAccount("Orange Money", "KES");
        account.setAccountData("---");
        PaymentAccount cashAtAtmAccount = PaymentAccountFactory.getPaymentAccount(PaymentMethod.getPaymentMethod(PaymentMethod.CASH_AT_ATM_ID));
        cashAtAtmAccount.init();
        cashAtAtmAccount.setSingleTradeCurrency(new TraditionalCurrency("KES"));

        assertFalse(PaymentAccountUtil.isPaymentAccountValidForOffer(offer, account));
        assertFalse(PaymentAccountUtil.isPaymentAccountValidForOffer(offer, cashAtAtmAccount));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Peer's account in a trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Test
    public void peerAccountForTheCustomPaymentMethodOfTheOfferIsAccepted() {
        Offer offer = offer("KES", PaymentMethod.CUSTOM_ID, "Orange Money");

        CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(payload("Orange Money", ACCOUNT_DATA), offer);
        CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(payload("orange-money", ACCOUNT_DATA), offer);
    }

    @Test
    public void peerAccountWithAnotherNameOrTextsWhichAreNotValidIsRefused() {
        Offer offer = offer("KES", PaymentMethod.CUSTOM_ID, "Orange Money");

        assertThrows(IllegalArgumentException.class, () -> CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(payload("Airtel Money", ACCOUNT_DATA), offer));
        assertThrows(IllegalArgumentException.class, () -> CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(payload("Orange Money\n", ACCOUNT_DATA), offer));
        assertThrows(IllegalArgumentException.class, () -> CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(payload("Orange Money", ""), offer));
        assertThrows(IllegalArgumentException.class, () -> CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(payload("Orange Money", "abc‮def"), offer));
        assertThrows(IllegalArgumentException.class, () -> CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(payload("Orange Money", "a".repeat(CustomPaymentMethodUtil.MAX_ACCOUNT_DATA_LENGTH + 1)), offer));
        assertThrows(IllegalArgumentException.class, () -> CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(payload("Orange Money", ACCOUNT_DATA), offer("KES", PaymentMethod.CUSTOM_ID, "--")));
    }

    // the peer sets the kind of the account and the payment method id in it independently
    @Test
    public void peerAccountOfAnotherKindThanTheOfferIsRefused() {
        PaymentAccount cashAtAtmAccount = PaymentAccountFactory.getPaymentAccount(PaymentMethod.getPaymentMethod(PaymentMethod.CASH_AT_ATM_ID));
        cashAtAtmAccount.init();
        PaymentAccountPayload cashAtAtmPayload = cashAtAtmAccount.getPaymentAccountPayload();
        CustomPaymentMethodAccountPayload customPayloadWithOtherId = new CustomPaymentMethodAccountPayload(PaymentMethod.CASH_AT_ATM_ID, "id");
        customPayloadWithOtherId.setPaymentMethodName("Orange Money");
        customPayloadWithOtherId.setAccountData(ACCOUNT_DATA);

        CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(cashAtAtmPayload, offer("KES", PaymentMethod.CASH_AT_ATM_ID, null));
        assertThrows(IllegalArgumentException.class, () -> CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(cashAtAtmPayload, offer("KES", PaymentMethod.CUSTOM_ID, "Orange Money")));
        assertThrows(IllegalArgumentException.class, () -> CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(payload("Orange Money", ACCOUNT_DATA), offer("KES", PaymentMethod.CASH_AT_ATM_ID, null)));
        assertThrows(IllegalArgumentException.class, () -> CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(customPayloadWithOtherId, offer("KES", PaymentMethod.CUSTOM_ID, "Orange Money")));
        assertThrows(IllegalArgumentException.class, () -> CustomPaymentMethodUtil.checkPeerPaymentAccountPayload(customPayloadWithOtherId, offer("KES", PaymentMethod.CASH_AT_ATM_ID, null)));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private static CustomPaymentMethodAccount newAccount(String paymentMethodName, String currencyCode) {
        CustomPaymentMethodAccount account = (CustomPaymentMethodAccount) PaymentAccountFactory.getPaymentAccount(CUSTOM);
        account.init();
        account.setAccountName("Custom account");
        account.setPaymentMethodName(paymentMethodName);
        account.setAccountData(ACCOUNT_DATA);
        account.setSingleTradeCurrency(new TraditionalCurrency(currencyCode));
        return account;
    }

    private static CustomPaymentMethodAccountPayload payload(String paymentMethodName, String accountData) {
        CustomPaymentMethodAccountPayload payload = new CustomPaymentMethodAccountPayload(PaymentMethod.CUSTOM_ID, "id");
        payload.setPaymentMethodName(paymentMethodName);
        payload.setAccountData(accountData);
        return payload;
    }

    private static PaymentAccountForm newForm(String paymentMethodName, String accountData) {
        PaymentAccountForm form = PaymentAccountForm.getForm(PaymentMethod.CUSTOM_ID);
        for (PaymentAccountFormField field : form.getFields()) {
            switch (field.getId()) {
                case TRADE_CURRENCIES -> field.setValue("KES");
                case PAYMENT_METHOD_NAME -> field.setValue(paymentMethodName);
                case ACCOUNT_DATA -> field.setValue(accountData);
                case ACCOUNT_NAME -> field.setValue("Custom account");
                default -> field.setValue("");
            }
        }
        return form;
    }

    private static Offer offer(String currencyCode, String paymentMethodId, @Nullable String customPaymentMethodName) {
        Map<String, String> extraDataMap = new HashMap<>();
        if (customPaymentMethodName != null) extraDataMap.put(OfferPayload.CUSTOM_PAYMENT_METHOD_NAME, customPaymentMethodName);
        return new Offer(new OfferPayload("1234",
                0L,
                null,
                null,
                OfferDirection.BUY,
                100000L,
                0.0,
                false,
                100000L,
                100000L,
                0L,
                0L,
                0L,
                0L,
                0L,
                "XMR",
                currencyCode,
                paymentMethodId,
                "",
                null,
                null,
                null,
                null,
                "",
                0L,
                0L,
                0L,
                false,
                false,
                0L,
                0L,
                false,
                null,
                extraDataMap,
                0,
                null,
                null,
                null,
                null));
    }
}
