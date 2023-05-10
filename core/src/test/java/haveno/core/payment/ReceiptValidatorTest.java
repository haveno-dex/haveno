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

package haveno.core.payment;

import haveno.core.offer.Offer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class ReceiptValidatorTest {
    private ReceiptValidator validator;
    private PaymentAccount account;
    private Offer offer;
    private ReceiptPredicates predicates;

    @BeforeEach
    public void setUp() {
        this.predicates = mock(ReceiptPredicates.class);
        this.account = mock(CountryBasedPaymentAccount.class);
        this.offer = mock(Offer.class);
        this.validator = new ReceiptValidator(offer, account, predicates);
    }

    @AfterEach
    public void tearDown() {
        verifyNoMoreInteractions(offer);
    }

    @Test
    public void testIsValidWhenCurrencyDoesNotMatch() {
        when(predicates.isMatchingCurrency(offer, account)).thenReturn(false);

        assertFalse(validator.isValid());
        verify(predicates).isMatchingCurrency(offer, account);
    }

    @Test
    public void testIsValidWhenNotCountryBasedAccount() {
        account = mock(PaymentAccount.class);
        assertFalse(account instanceof CountryBasedPaymentAccount);

        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(true);

        assertTrue(new ReceiptValidator(offer, account, predicates).isValid());
    }

    @Test
    public void testIsValidWhenNotMatchingCodes() {
        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(false);

        assertFalse(validator.isValid());
        verify(predicates).isMatchingCountryCodes(offer, account);
    }

    @Test
    public void testIsValidWhenSepaOffer() {
        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaOffer(offer, account)).thenReturn(true);

        assertTrue(validator.isValid());
        verify(predicates).isMatchingSepaOffer(offer, account);
    }

    @Test
    public void testIsValidWhenSepaInstant() {
        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaInstant(offer, account)).thenReturn(true);

        assertTrue(validator.isValid());
        verify(predicates).isMatchingSepaOffer(offer, account);
    }

    @Test
    public void testIsValidWhenSpecificBankAccountAndOfferRequireSpecificBank() {
        account = mock(SpecificBanksAccount.class);

        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(true);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isMatchingSepaOffer(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaInstant(offer, account)).thenReturn(false);
        when(predicates.isOfferRequireSameOrSpecificBank(offer, account)).thenReturn(true);
        when(predicates.isMatchingBankId(offer, account)).thenReturn(false);

        assertFalse(new ReceiptValidator(offer, account, predicates).isValid());
    }

    @Test
    public void testIsValidWhenSameBankAccountAndOfferRequireSpecificBank() {
        account = mock(SameBankAccount.class);

        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(true);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isMatchingSepaOffer(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaInstant(offer, account)).thenReturn(false);
        when(predicates.isOfferRequireSameOrSpecificBank(offer, account)).thenReturn(true);
        when(predicates.isMatchingBankId(offer, account)).thenReturn(false);

        assertFalse(new ReceiptValidator(offer, account, predicates).isValid());
    }

    @Test
    public void testIsValidWhenSpecificBankAccount() {
        account = mock(SpecificBanksAccount.class);

        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(true);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isMatchingSepaOffer(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaInstant(offer, account)).thenReturn(false);
        when(predicates.isOfferRequireSameOrSpecificBank(offer, account)).thenReturn(true);
        when(predicates.isMatchingBankId(offer, account)).thenReturn(true);

        assertTrue(new ReceiptValidator(offer, account, predicates).isValid());
    }

    @Test
    public void testIsValidWhenSameBankAccount() {
        account = mock(SameBankAccount.class);

        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(true);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isMatchingSepaOffer(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaInstant(offer, account)).thenReturn(false);
        when(predicates.isOfferRequireSameOrSpecificBank(offer, account)).thenReturn(true);
        when(predicates.isMatchingBankId(offer, account)).thenReturn(true);

        assertTrue(new ReceiptValidator(offer, account, predicates).isValid());
    }

    @Test
    public void testIsValidWhenNationalBankAccount() {
        account = mock(NationalBankAccount.class);

        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(true);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isMatchingSepaOffer(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaInstant(offer, account)).thenReturn(false);
        when(predicates.isOfferRequireSameOrSpecificBank(offer, account)).thenReturn(false);

        assertTrue(new ReceiptValidator(offer, account, predicates).isValid());
    }

    @Test
    // Same or Specific Bank offers can't be taken by National Bank accounts. TODO: Consider partially relaxing to allow Specific Banks.
    public void testIsValidWhenNationalBankAccountAndOfferIsNot() {
        account = mock(NationalBankAccount.class);

        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(false);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isMatchingSepaOffer(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaInstant(offer, account)).thenReturn(false);

        assertFalse(new ReceiptValidator(offer, account, predicates).isValid());

        verify(predicates, never()).isOfferRequireSameOrSpecificBank(offer, account);
        verify(predicates, never()).isMatchingBankId(offer, account);
    }

    @Test
    // National or Same Bank offers can't be taken by Specific Banks accounts. TODO: Consider partially relaxing to allow National Bank.
    public void testIsValidWhenSpecificBanksAccountAndOfferIsNot() {
        account = mock(SpecificBanksAccount.class);

        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(false);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isMatchingSepaOffer(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaInstant(offer, account)).thenReturn(false);

        assertFalse(new ReceiptValidator(offer, account, predicates).isValid());

        verify(predicates, never()).isOfferRequireSameOrSpecificBank(offer, account);
        verify(predicates, never()).isMatchingBankId(offer, account);
    }

    @Test
    // National or Specific Bank offers can't be taken by Same Bank accounts.
    public void testIsValidWhenSameBankAccountAndOfferIsNot() {
        account = mock(SameBankAccount.class);

        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(false);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isMatchingSepaOffer(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaInstant(offer, account)).thenReturn(false);

        assertFalse(new ReceiptValidator(offer, account, predicates).isValid());

        verify(predicates, never()).isOfferRequireSameOrSpecificBank(offer, account);
        verify(predicates, never()).isMatchingBankId(offer, account);
    }

    @Test
    public void testIsValidWhenWesternUnionAccount() {
        account = mock(WesternUnionAccount.class);

        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(true);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isMatchingSepaOffer(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaInstant(offer, account)).thenReturn(false);
        when(predicates.isOfferRequireSameOrSpecificBank(offer, account)).thenReturn(false);

        assertTrue(new ReceiptValidator(offer, account, predicates).isValid());
    }

    @Test
    public void testIsValidWhenWesternIrregularAccount() {
        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(true);
        when(predicates.isMatchingCountryCodes(offer, account)).thenReturn(true);
        when(predicates.isMatchingSepaOffer(offer, account)).thenReturn(false);
        when(predicates.isMatchingSepaInstant(offer, account)).thenReturn(false);
        when(predicates.isOfferRequireSameOrSpecificBank(offer, account)).thenReturn(false);

        assertTrue(validator.isValid());
    }

    @Test
    public void testIsValidWhenMoneyGramAccount() {
        account = mock(MoneyGramAccount.class);

        when(predicates.isMatchingCurrency(offer, account)).thenReturn(true);
        when(predicates.isEqualPaymentMethods(offer, account)).thenReturn(true);

        assertTrue(new ReceiptValidator(offer, account, predicates).isValid());

        verify(predicates, never()).isMatchingCountryCodes(offer, account);
        verify(predicates, never()).isMatchingSepaOffer(offer, account);
        verify(predicates, never()).isMatchingSepaInstant(offer, account);
        verify(predicates, never()).isOfferRequireSameOrSpecificBank(offer, account);
    }
}
