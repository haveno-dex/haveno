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

import haveno.core.account.witness.AccountAgeWitness;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.proto.CoreProtoResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PaymentAccountsTest {
    @BeforeEach
    public void setUp() {
        Res.setup();
    }

    @Test
    public void testErgoAccountRequiresMatchingOfferCurrencyAndMethod() {
        CryptoCurrencyAccount account = new CryptoCurrencyAccount();
        account.init();
        account.setSingleTradeCurrency(CurrencyUtil.getTradeCurrency("ERG").orElseThrow());
        Offer offer = mock(Offer.class);
        when(offer.getCounterCurrencyCode()).thenReturn("ERG");
        when(offer.getPaymentMethod()).thenReturn(PaymentMethod.BLOCK_CHAINS);

        assertTrue(PaymentAccountUtil.isPaymentAccountValidForOffer(offer, account));
        when(offer.getCounterCurrencyCode()).thenReturn("BTC");
        assertFalse(PaymentAccountUtil.isPaymentAccountValidForOffer(offer, account));
        when(offer.getCounterCurrencyCode()).thenReturn("ERG");
        when(offer.getPaymentMethod()).thenReturn(PaymentMethod.BLOCK_CHAINS_INSTANT);
        assertFalse(PaymentAccountUtil.isPaymentAccountValidForOffer(offer, account));
    }

    @Test
    public void testErgoAccountRoundTripUsesRegisteredCurrency() throws Exception {
        TradeCurrency ergo = CurrencyUtil.getTradeCurrency("ERG").orElseThrow();
        CryptoCurrencyAccount account = new CryptoCurrencyAccount();
        assertTrue(account.getSupportedCurrencies().contains(ergo));
        account.init();
        account.setAccountName("Ergo wallet");
        account.setSingleTradeCurrency(ergo);
        account.setAddress("9iJd9drp1KR3R7HLi7YmQbB5sJ5HFKZoPb5MxGepamggJs5vDHm");

        protobuf.PaymentAccount encoded = protobuf.PaymentAccount.parseFrom(account.toProtoMessage().toByteArray());
        CryptoCurrencyAccount restored = assertInstanceOf(CryptoCurrencyAccount.class,
                PaymentAccount.fromProto(encoded, new CoreProtoResolver()));

        assertEquals(account.toProtoMessage(), restored.toProtoMessage());
        assertEquals(account.getId(), restored.getId());
        assertEquals(account.getAddress(), restored.getAddress());
        assertEquals(PaymentMethod.BLOCK_CHAINS_ID, restored.getPaymentMethod().getId());
        assertEquals("ERG", restored.getSingleTradeCurrency().getCode());
        assertEquals("Ergo", restored.getSingleTradeCurrency().getName());
        assertTrue(restored.isCryptoCurrency());
    }

    @Test
    public void testGetOldestPaymentAccountForOfferWhenNoValidAccounts() {
        PaymentAccounts accounts = new PaymentAccounts(Collections.emptySet(), mock(AccountAgeWitnessService.class));
        PaymentAccount actual = accounts.getOldestPaymentAccountForOffer(mock(Offer.class));

        assertNull(actual);
    }

//    @Test
//    public void testGetOldestPaymentAccountForOffer() {
//        AccountAgeWitnessService service = mock(AccountAgeWitnessService.class);
//
//        PaymentAccount oldest = createAccountWithAge(service, 3);
//        Set<PaymentAccount> accounts = Sets.newHashSet(
//                oldest,
//                createAccountWithAge(service, 2),
//                createAccountWithAge(service, 1));
//
//        BiFunction<Offer, PaymentAccount, Boolean> dummyValidator = (offer, account) -> true;
//        PaymentAccounts testedEntity = new PaymentAccounts(accounts, service, dummyValidator);
//
//        PaymentAccount actual = testedEntity.getOldestPaymentAccountForOffer(mock(Offer.class));
//        assertEquals(oldest, actual);
//    }

    private static PaymentAccount createAccountWithAge(AccountAgeWitnessService service, long age) {
        PaymentAccountPayload payload = mock(PaymentAccountPayload.class);

        PaymentAccount account = mock(PaymentAccount.class);
        when(account.getPaymentAccountPayload()).thenReturn(payload);

        AccountAgeWitness witness = mock(AccountAgeWitness.class);
        when(service.getAccountAge(eq(witness), any())).thenReturn(age);

        when(service.getMyWitness(payload)).thenReturn(witness);

        return account;
    }
}
