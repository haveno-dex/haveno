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

import haveno.core.payment.payload.AdvancedCashAccountPayload;
import haveno.core.payment.payload.AustraliaPayidAccountPayload;
import haveno.core.payment.payload.BizumAccountPayload;
import haveno.core.payment.payload.BlikAccountPayload;
import haveno.core.payment.payload.CashAppAccountPayload;
import haveno.core.payment.payload.CashAtAtmAccountPayload;
import haveno.core.payment.payload.ChaseQuickPayAccountPayload;
import haveno.core.payment.payload.CryptoCurrencyAccountPayload;
import haveno.core.payment.payload.DuitNowAccountPayload;
import haveno.core.payment.payload.F2FAccountPayload;
import haveno.core.payment.payload.FasterPaymentsAccountPayload;
import haveno.core.payment.payload.FpsAccountPayload;
import haveno.core.payment.payload.ImpsAccountPayload;
import haveno.core.payment.payload.InstantCryptoCurrencyPayload;
import haveno.core.payment.payload.InteracETransferAccountPayload;
import haveno.core.payment.payload.JapanBankAccountPayload;
import haveno.core.payment.payload.KaspiAccountPayload;
import haveno.core.payment.payload.MbWayAccountPayload;
import haveno.core.payment.payload.MercadoPagoAccountPayload;
import haveno.core.payment.payload.MomoAccountPayload;
import haveno.core.payment.payload.MoneyGramAccountPayload;
import haveno.core.payment.payload.MpesaAccountPayload;
import haveno.core.payment.payload.NationalBankAccountPayload;
import haveno.core.payment.payload.NeftAccountPayload;
import haveno.core.payment.payload.OKPayAccountPayload;
import haveno.core.payment.payload.PagoMovilAccountPayload;
import haveno.core.payment.payload.PayNowAccountPayload;
import haveno.core.payment.payload.PayPalAccountPayload;
import haveno.core.payment.payload.PayPayAccountPayload;
import haveno.core.payment.payload.PaytmAccountPayload;
import haveno.core.payment.payload.PixAccountPayload;
import haveno.core.payment.payload.PopmoneyAccountPayload;
import haveno.core.payment.payload.PromptPayAccountPayload;
import haveno.core.payment.payload.SameBankAccountPayload;
import haveno.core.payment.payload.SatispayAccountPayload;
import haveno.core.payment.payload.SbpAccountPayload;
import haveno.core.payment.payload.SepaAccountPayload;
import haveno.core.payment.payload.SepaInstantAccountPayload;
import haveno.core.payment.payload.SwishAccountPayload;
import haveno.core.payment.payload.TikkieAccountPayload;
import haveno.core.payment.payload.TransferwiseAccountPayload;
import haveno.core.payment.payload.TwintAccountPayload;
import haveno.core.payment.payload.VenmoAccountPayload;
import haveno.core.payment.payload.VippsMobilePayAccountPayload;
import haveno.core.payment.payload.WesternUnionAccountPayload;
import haveno.core.payment.payload.ZelleAccountPayload;
import haveno.core.trade.HavenoUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Locale;

// Payment endpoint data must identify the external transfer endpoint, unchanged by
// mutable fields like holder names or contact emails, for detecting trades with
// indistinguishable payments.
public class PaymentEndpointDataTest {

    @Test
    public void testPixEndpointExcludesHolderName() {
        PixAccountPayload payload1 = new PixAccountPayload("PIX", "id1");
        payload1.setPixKey("pix-key");
        payload1.setHolderName("Holder One");
        PixAccountPayload payload2 = new PixAccountPayload("PIX", "id2");
        payload2.setPixKey("pix-key");
        payload2.setHolderName("Holder Two");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
        assertFalse(Arrays.equals(payload1.getAgeWitnessInputData(), payload2.getAgeWitnessInputData()));
    }

    @Test
    public void testTransferwiseEndpointExcludesHolderName() {
        TransferwiseAccountPayload payload1 = new TransferwiseAccountPayload("TRANSFERWISE", "id1");
        payload1.setEmail("payee@example.com");
        payload1.setHolderName("Holder One");
        TransferwiseAccountPayload payload2 = new TransferwiseAccountPayload("TRANSFERWISE", "id2");
        payload2.setEmail("payee@example.com");
        payload2.setHolderName("Holder Two");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testJapanBankEndpointUsesStableCodes() {
        JapanBankAccountPayload payload1 = japanBankPayload("id1", "みずほ銀行", "本店");
        JapanBankAccountPayload payload2 = japanBankPayload("id2", "Mizuho Bank", "Head Office");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());

        JapanBankAccountPayload payload3 = japanBankPayload("id3", "みずほ銀行", "本店");
        payload3.setBankCode("0009");
        assertFalse(Arrays.equals(payload1.getPaymentEndpointData(), payload3.getPaymentEndpointData()));
    }

    private static JapanBankAccountPayload japanBankPayload(String id, String bankName, String branchName) {
        JapanBankAccountPayload payload = new JapanBankAccountPayload("JAPAN_BANK", id);
        payload.setBankCode("0001");
        payload.setBankName(bankName);
        payload.setBankBranchCode("001");
        payload.setBankBranchName(branchName);
        payload.setBankAccountType("FUTSU");
        payload.setBankAccountNumber("1234567");
        return payload;
    }

    @Test
    public void testMoneyGramEndpointExcludesContactEmail() {
        MoneyGramAccountPayload payload1 = moneyGramPayload("id1", "contact1@example.com");
        MoneyGramAccountPayload payload2 = moneyGramPayload("id2", "contact2@example.com");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testMoneyGramEndpointExcludesStateWhereNotRequired() {
        MoneyGramAccountPayload payload1 = moneyGramPayload("id1", "contact@example.com");
        payload1.setCountryCode("FR");
        payload1.setState("");
        MoneyGramAccountPayload payload2 = moneyGramPayload("id2", "contact@example.com");
        payload2.setCountryCode("FR");
        payload2.setState("hidden state");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testMoneyGramEndpointIncludesStateWhereRequired() {
        MoneyGramAccountPayload payload1 = moneyGramPayload("id1", "contact@example.com");
        payload1.setState("CA");
        MoneyGramAccountPayload payload2 = moneyGramPayload("id2", "contact@example.com");
        payload2.setState("NY");
        assertFalse(Arrays.equals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData()));
    }

    private static MoneyGramAccountPayload moneyGramPayload(String id, String email) {
        MoneyGramAccountPayload payload = new MoneyGramAccountPayload("MONEY_GRAM", id);
        payload.setCountryCode("US");
        payload.setState("CA");
        payload.setHolderName("Holder Name");
        payload.setEmail(email);
        return payload;
    }

    @Test
    public void testWesternUnionEndpointExcludesContactEmailAndIncludesCity() {
        WesternUnionAccountPayload payload1 = westernUnionPayload("id1", "Lyon", "contact1@example.com");
        WesternUnionAccountPayload payload2 = westernUnionPayload("id2", "Lyon", "contact2@example.com");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());

        WesternUnionAccountPayload payload3 = westernUnionPayload("id3", "Paris", "contact1@example.com");
        assertFalse(Arrays.equals(payload1.getPaymentEndpointData(), payload3.getPaymentEndpointData()));

        WesternUnionAccountPayload payload4 = westernUnionPayload("id4", "Lyon", "contact1@example.com");
        payload4.setCountryCode("CH");
        assertFalse(Arrays.equals(payload1.getPaymentEndpointData(), payload4.getPaymentEndpointData()));
    }

    private static WesternUnionAccountPayload westernUnionPayload(String id, String city, String email) {
        WesternUnionAccountPayload payload = new WesternUnionAccountPayload("WESTERN_UNION", id);
        payload.setCountryCode("FR");
        payload.setHolderName("Holder Name");
        payload.setCity(city);
        payload.setEmail(email);
        return payload;
    }

    @Test
    public void testSepaEndpointIsStableAcrossAccountRecords() {
        SepaAccountPayload payload1 = sepaPayload("id1", "Holder One", "DE89370400440532013000", "COBADEFFXXX");
        SepaAccountPayload payload2 = sepaPayload("id2", "Holder Two", "DE89370400440532013000", "COBADEFFXXX");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testSepaEndpointExcludesEquivalentBicForms() {
        SepaAccountPayload payload1 = sepaPayload("id1", "Holder Name", "DE89370400440532013000", "COBADEFF");
        SepaAccountPayload payload2 = sepaPayload("id2", "Holder Name", "DE89370400440532013000", "COBADEFFXXX");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testSepaEndpointNormalizesIbanFormatting() {
        SepaAccountPayload payload1 = sepaPayload("id1", "Holder Name", "DE89370400440532013000", "COBADEFF");
        SepaAccountPayload payload2 = sepaPayload("id2", "Holder Name", "de89 3704 0044 0532 0130 00", "COBADEFF");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testSepaEndpointUppercasesIndependentOfLocale() {
        Locale defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            SepaAccountPayload payload1 = sepaPayload("id1", "Holder Name", "fi2112345600000785", "COBADEFF");
            SepaAccountPayload payload2 = sepaPayload("id2", "Holder Name", "FI2112345600000785", "COBADEFF");
            assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    public void testSepaAndSepaInstantShareEndpointForSameIban() {
        SepaAccountPayload payload1 = sepaPayload("id1", "Holder Name", "DE89370400440532013000", "COBADEFF");
        SepaInstantAccountPayload payload2 = new SepaInstantAccountPayload("SEPA_INSTANT", "id2", java.util.Collections.emptyList());
        payload2.setCountryCode("DE");
        payload2.setHolderName("Holder Name");
        payload2.setIban("DE89370400440532013000");
        payload2.setBic("COBADEFF");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testTikkieSharesSepaEndpointForSameIban() {
        SepaAccountPayload payload1 = sepaPayload("id1", "Holder Name", "NL91ABNA0417164300", "ABNANL2A");
        TikkieAccountPayload payload2 = new TikkieAccountPayload("TIKKIE", "id2");
        payload2.setCountryCode("NL");
        payload2.setIban("NL91ABNA0417164300");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testChaseQuickPaySharesZelleEndpointForSameEmail() {
        ChaseQuickPayAccountPayload payload1 = new ChaseQuickPayAccountPayload("CHASE_QUICK_PAY", "id1");
        payload1.setEmail("User@Example.com");
        ZelleAccountPayload payload2 = new ZelleAccountPayload("ZELLE", "id2");
        payload2.setEmailOrMobileNr("user@example.com");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testSepaEndpointIgnoresPayloadCountry() {
        SepaAccountPayload payload1 = sepaPayload("id1", "Holder Name", "DE89370400440532013000", "COBADEFF");
        SepaAccountPayload payload2 = sepaPayload("id2", "Holder Name", "DE89370400440532013000", "COBADEFF");
        payload2.setCountryCode("FR");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    private static SepaAccountPayload sepaPayload(String id, String holderName, String iban, String bic) {
        SepaAccountPayload payload = new SepaAccountPayload("SEPA", id, java.util.Collections.emptyList());
        payload.setCountryCode("DE");
        payload.setHolderName(holderName);
        payload.setIban(iban);
        payload.setBic(bic);
        return payload;
    }

    @Test
    public void testInteracEndpointExcludesSecurityQuestionAndAnswer() {
        InteracETransferAccountPayload payload1 = interacPayload("id1", "First question?", "first answer");
        InteracETransferAccountPayload payload2 = interacPayload("id2", "Second question?", "second answer");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
        assertFalse(Arrays.equals(payload1.getAgeWitnessInputData(), payload2.getAgeWitnessInputData()));
    }

    @Test
    public void testInteracEndpointNormalizesEquivalentPhoneForms() {
        InteracETransferAccountPayload payload1 = interacPayload("id1", "Question?", "answer");
        payload1.setEmailOrMobileNr("+1 416 555 1234");
        InteracETransferAccountPayload payload2 = interacPayload("id2", "Question?", "answer");
        payload2.setEmailOrMobileNr("1-416-555-1234");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testInteracEndpointNormalizesEmailCase() {
        InteracETransferAccountPayload payload1 = interacPayload("id1", "Question?", "answer");
        payload1.setEmailOrMobileNr("Payee@Example.com");
        InteracETransferAccountPayload payload2 = interacPayload("id2", "Question?", "answer");
        payload2.setEmailOrMobileNr("payee@example.com");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    private static InteracETransferAccountPayload interacPayload(String id, String question, String answer) {
        InteracETransferAccountPayload payload = new InteracETransferAccountPayload("INTERAC_E_TRANSFER", id);
        payload.setEmailOrMobileNr("payee@example.com");
        payload.setHolderName("Holder Name");
        payload.setQuestion(question);
        payload.setAnswer(answer);
        return payload;
    }

    @Test
    public void testInteracEndpointNormalizesNationalPhoneForm() {
        InteracETransferAccountPayload payload1 = interacPayload("id1", "Question?", "answer");
        payload1.setEmailOrMobileNr("416-555-1234");
        InteracETransferAccountPayload payload2 = interacPayload("id2", "Question?", "answer");
        payload2.setEmailOrMobileNr("+1 416 555 1234");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testMethodsWithoutStableEndpointReturnNull() {
        assertNull(new CashAtAtmAccountPayload("CASH_AT_ATM", "id1").getPaymentEndpointData());
        assertNull(new BlikAccountPayload("BLIK", "id1").getPaymentEndpointData());
        assertNull(new F2FAccountPayload("F2F", "id1").getPaymentEndpointData());
    }

    @Test
    public void testCryptoEndpointSharedAcrossInstantMethods() {
        CryptoCurrencyAccountPayload payload1 = new CryptoCurrencyAccountPayload("BLOCK_CHAINS", "id1");
        payload1.setAddress("4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2684Rge");
        InstantCryptoCurrencyPayload payload2 = new InstantCryptoCurrencyPayload("BLOCK_CHAINS_INSTANT", "id2");
        payload2.setAddress("4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2684Rge");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testDifferentMethodsUseDistinctNamespaces() {
        ZelleAccountPayload payload1 = new ZelleAccountPayload("ZELLE", "id1");
        payload1.setEmailOrMobileNr("payee@example.com");
        InteracETransferAccountPayload payload2 = interacPayload("id2", "Question?", "answer");
        assertFalse(Arrays.equals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData()));
    }

    @Test
    public void testEndpointNamespaceResolvesByMethodId() {
        assertEquals("SEPA", HavenoUtils.getPaymentEndpointNamespace("SEPA_INSTANT"));
        assertEquals("NEFT", HavenoUtils.getPaymentEndpointNamespace("RTGS"));
        assertEquals("ZELLE", HavenoUtils.getPaymentEndpointNamespace("ZELLE"));
    }

    @Test
    public void testNationalBankEndpointNormalizesAccountNrFormatting() {
        NationalBankAccountPayload payload1 = nationalBankPayload("id1", "03-1587-0050000-00");
        NationalBankAccountPayload payload2 = nationalBankPayload("id2", "031587005000000");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());

        NationalBankAccountPayload payload3 = nationalBankPayload("id3", "03-1587-0050000-01");
        assertFalse(Arrays.equals(payload1.getPaymentEndpointData(), payload3.getPaymentEndpointData()));
    }

    @Test
    public void testBankEndpointExcludesFreeFormBankName() {
        NationalBankAccountPayload payload1 = nationalBankPayload("id1", "031587005000000");
        payload1.setBankName("Kiwibank");
        NationalBankAccountPayload payload2 = nationalBankPayload("id2", "031587005000000");
        payload2.setBankName("Kiwibank New Zealand Ltd");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testBankEndpointSharedAcrossBankMethodVariants() {
        SameBankAccountPayload sameBank = new SameBankAccountPayload("SAME_BANK", "id2");
        sameBank.setCountryCode("NZ");
        sameBank.setAccountNr("031587005000000");
        assertArrayEquals(nationalBankPayload("id1", "031587005000000").getPaymentEndpointData(), sameBank.getPaymentEndpointData());
    }

    @Test
    public void testBankEndpointIncludesCountry() {
        NationalBankAccountPayload payload1 = nationalBankPayload("id1", "031587005000000");
        NationalBankAccountPayload payload2 = nationalBankPayload("id2", "031587005000000");
        payload2.setCountryCode("AU");
        assertFalse(Arrays.equals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData()));
    }

    @Test
    public void testBankEndpointExcludesLocalizedAccountType() {
        NationalBankAccountPayload payload1 = usBankPayload("id1", "Checking");
        NationalBankAccountPayload payload2 = usBankPayload("id2", "Conta Corrente");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    private static NationalBankAccountPayload usBankPayload(String id, String accountType) {
        NationalBankAccountPayload payload = new NationalBankAccountPayload("NATIONAL_BANK", id);
        payload.setCountryCode("US");
        payload.setBranchId("021000021");
        payload.setAccountNr("123456789");
        payload.setAccountType(accountType);
        return payload;
    }

    @Test
    public void testIfscMethodsShareEndpointForSameAccount() {
        NeftAccountPayload neft = new NeftAccountPayload("NEFT", "id1");
        neft.setAccountNr("12345678901");
        neft.setIfsc("SBIN0001234");
        ImpsAccountPayload imps = new ImpsAccountPayload("IMPS", "id2");
        imps.setAccountNr("12345678901");
        imps.setIfsc("SBIN0001234");
        assertArrayEquals(neft.getPaymentEndpointData(), imps.getPaymentEndpointData());
    }

    private static NationalBankAccountPayload nationalBankPayload(String id, String accountNr) {
        NationalBankAccountPayload payload = new NationalBankAccountPayload("NATIONAL_BANK", id);
        payload.setCountryCode("NZ");
        payload.setAccountNr(accountNr);
        return payload;
    }

    @Test
    public void testFasterPaymentsSharesEndpointWithGbBankAccount() {
        FasterPaymentsAccountPayload fasterPayments = new FasterPaymentsAccountPayload("FASTER_PAYMENTS", "id1");
        fasterPayments.setSortCode("123456");
        fasterPayments.setAccountNr("12345678");
        NationalBankAccountPayload bank = new NationalBankAccountPayload("NATIONAL_BANK", "id2");
        bank.setCountryCode("GB");
        bank.setBankName("Barclays");
        bank.setBranchId("12-34-56");
        bank.setAccountNr("12345678");
        assertArrayEquals(fasterPayments.getPaymentEndpointData(), bank.getPaymentEndpointData());
    }

    @Test
    public void testZelleEndpointNormalizesPhoneFormatting() {
        ZelleAccountPayload payload1 = new ZelleAccountPayload("ZELLE", "id1");
        payload1.setEmailOrMobileNr("+1 (416) 555-1234");
        ZelleAccountPayload payload2 = new ZelleAccountPayload("ZELLE", "id2");
        payload2.setEmailOrMobileNr("14165551234");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testZelleEndpointNormalizesNationalPhoneForm() {
        ZelleAccountPayload payload1 = new ZelleAccountPayload("ZELLE", "id1");
        payload1.setEmailOrMobileNr("+1 (202) 555-0123");
        ZelleAccountPayload payload2 = new ZelleAccountPayload("ZELLE", "id2");
        payload2.setEmailOrMobileNr("202-555-0123");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
    }

    @Test
    public void testZelleEndpointNormalizesInternationalDialingPrefix() {
        ZelleAccountPayload payload1 = new ZelleAccountPayload("ZELLE", "id1");
        payload1.setEmailOrMobileNr("001 202 555 0123");
        ZelleAccountPayload payload2 = new ZelleAccountPayload("ZELLE", "id2");
        payload2.setEmailOrMobileNr("+1 (202) 555-0123");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());
        ZelleAccountPayload payload3 = new ZelleAccountPayload("ZELLE", "id3");
        payload3.setEmailOrMobileNr("00 1 202 555 0123");
        assertArrayEquals(payload2.getPaymentEndpointData(), payload3.getPaymentEndpointData());
    }

    @Test
    public void testMobileNrEndpointsNormalizeNationalAndInternationalForms() {
        assertArrayEquals(mbWayPayload("id1", "+351 912 345 678").getPaymentEndpointData(),
                mbWayPayload("id2", "912345678").getPaymentEndpointData());
        assertArrayEquals(swishPayload("id1", "070-123 45 67").getPaymentEndpointData(),
                swishPayload("id2", "+46 70 123 45 67").getPaymentEndpointData());
        assertArrayEquals(swishPayload("id1", "+46 (0)70 123 45 67").getPaymentEndpointData(),
                swishPayload("id2", "0046701234567").getPaymentEndpointData());
        assertArrayEquals(twintPayload("id1", "079 123 45 67").getPaymentEndpointData(),
                twintPayload("id2", "+41 79 123 45 67").getPaymentEndpointData());
        assertArrayEquals(pagoMovilPayload("id1", "0414-1234567").getPaymentEndpointData(),
                pagoMovilPayload("id2", "+58 414 123 4567").getPaymentEndpointData());
        assertFalse(Arrays.equals(swishPayload("id1", "070-123 45 67").getPaymentEndpointData(),
                swishPayload("id2", "070-123 45 68").getPaymentEndpointData()));
    }

    @Test
    public void testFixedRegionMobileEndpointsNormalizeNationalForms() {
        BizumAccountPayload payload1 = new BizumAccountPayload("BIZUM", "id1");
        payload1.setMobileNr("612 345 678");
        BizumAccountPayload payload2 = new BizumAccountPayload("BIZUM", "id2");
        payload2.setMobileNr("+34 612345678");
        assertArrayEquals(payload1.getPaymentEndpointData(), payload2.getPaymentEndpointData());

        SbpAccountPayload payload3 = new SbpAccountPayload("SBP", "id3");
        payload3.setMobileNr("8 912 345-67-89");
        SbpAccountPayload payload4 = new SbpAccountPayload("SBP", "id4");
        payload4.setMobileNr("+7 912 345 67 89");
        assertArrayEquals(payload3.getPaymentEndpointData(), payload4.getPaymentEndpointData());
    }

    @Test
    public void testPaytmEndpointNormalizesNationalPhoneForms() {
        assertArrayEquals(paytmPayload("id1", "+91 98765 43210").getPaymentEndpointData(),
                paytmPayload("id2", "9876543210").getPaymentEndpointData());
        // a national number starting with the country calling code is still country-qualified
        assertArrayEquals(paytmPayload("id1", "+91 9198765432").getPaymentEndpointData(),
                paytmPayload("id2", "9198765432").getPaymentEndpointData());
        assertArrayEquals(paytmPayload("id1", "Payee@Example.com").getPaymentEndpointData(),
                paytmPayload("id2", "payee@example.com").getPaymentEndpointData());
    }

    private static PaytmAccountPayload paytmPayload(String id, String emailOrMobileNr) {
        PaytmAccountPayload payload = new PaytmAccountPayload("PAYTM", id);
        payload.setEmailOrMobileNr(emailOrMobileNr);
        return payload;
    }

    @Test
    public void testMobileNrEndpointsNormalizeTrunkZeroAfterCallingCode() {
        assertArrayEquals(paytmPayload("id1", "+91 (0) 98765 43210").getPaymentEndpointData(),
                paytmPayload("id2", "+91 98765 43210").getPaymentEndpointData());
        AustraliaPayidAccountPayload payid1 = new AustraliaPayidAccountPayload("AUSTRALIA_PAYID", "id1");
        payid1.setPayid("+61 (0) 412 345 678");
        AustraliaPayidAccountPayload payid2 = new AustraliaPayidAccountPayload("AUSTRALIA_PAYID", "id2");
        payid2.setPayid("0412 345 678");
        assertArrayEquals(payid1.getPaymentEndpointData(), payid2.getPaymentEndpointData());
    }

    @Test
    public void testMobileNrEndpointsNormalizeNationalNumbersStartingWithCallingCode() {
        PromptPayAccountPayload promptPay1 = new PromptPayAccountPayload("PROMPT_PAY", "id1");
        promptPay1.setPromptPayId("066 123 4567");
        PromptPayAccountPayload promptPay2 = new PromptPayAccountPayload("PROMPT_PAY", "id2");
        promptPay2.setPromptPayId("+66 66 123 4567");
        assertArrayEquals(promptPay1.getPaymentEndpointData(), promptPay2.getPaymentEndpointData());

        MomoAccountPayload momo1 = new MomoAccountPayload("MOMO", "id1");
        momo1.setMobileNr("084 123 4567");
        MomoAccountPayload momo2 = new MomoAccountPayload("MOMO", "id2");
        momo2.setMobileNr("+84 84 123 4567");
        assertArrayEquals(momo1.getPaymentEndpointData(), momo2.getPaymentEndpointData());
    }

    @Test
    public void testAccountNrProxyEndpointsNormalizeNationalAndInternationalForms() {
        FpsAccountPayload fps1 = new FpsAccountPayload("FPS", "id1");
        fps1.setAccountNr("9123 4567");
        FpsAccountPayload fps2 = new FpsAccountPayload("FPS", "id2");
        fps2.setAccountNr("+852 9123 4567");
        assertArrayEquals(fps1.getPaymentEndpointData(), fps2.getPaymentEndpointData());

        PayNowAccountPayload payNow1 = new PayNowAccountPayload("PAYNOW", "id1");
        payNow1.setAccountNr("91234567");
        PayNowAccountPayload payNow2 = new PayNowAccountPayload("PAYNOW", "id2");
        payNow2.setAccountNr("+65 9123 4567");
        assertArrayEquals(payNow1.getPaymentEndpointData(), payNow2.getPaymentEndpointData());
        PayNowAccountPayload payNow3 = new PayNowAccountPayload("PAYNOW", "id3");
        payNow3.setAccountNr("S1234567A");
        PayNowAccountPayload payNow4 = new PayNowAccountPayload("PAYNOW", "id4");
        payNow4.setAccountNr("s1234567a");
        assertArrayEquals(payNow3.getPaymentEndpointData(), payNow4.getPaymentEndpointData());

        DuitNowAccountPayload duitNow1 = new DuitNowAccountPayload("DUITNOW", "id1");
        duitNow1.setAccountNr("0123456789");
        DuitNowAccountPayload duitNow2 = new DuitNowAccountPayload("DUITNOW", "id2");
        duitNow2.setAccountNr("+60 12 345 6789");
        assertArrayEquals(duitNow1.getPaymentEndpointData(), duitNow2.getPaymentEndpointData());

        PayPayAccountPayload payPay1 = new PayPayAccountPayload("PAYPAY", "id1");
        payPay1.setAccountNr("09012345678");
        PayPayAccountPayload payPay2 = new PayPayAccountPayload("PAYPAY", "id2");
        payPay2.setAccountNr("+81 90 1234 5678");
        assertArrayEquals(payPay1.getPaymentEndpointData(), payPay2.getPaymentEndpointData());
        PayPayAccountPayload payPay3 = new PayPayAccountPayload("PAYPAY", "id3");
        payPay3.setAccountNr("9012345678"); // an all-digit PayPay ID is not a dialing form
        assertFalse(Arrays.equals(payPay1.getPaymentEndpointData(), payPay3.getPaymentEndpointData()));

        KaspiAccountPayload kaspi1 = new KaspiAccountPayload("KASPI", "id1");
        kaspi1.setAccountNr("8 702 123 4567");
        KaspiAccountPayload kaspi2 = new KaspiAccountPayload("KASPI", "id2");
        kaspi2.setAccountNr("+7 702 123 4567");
        assertArrayEquals(kaspi1.getPaymentEndpointData(), kaspi2.getPaymentEndpointData());
        KaspiAccountPayload kaspi3 = new KaspiAccountPayload("KASPI", "id3");
        kaspi3.setAccountNr("4400 4301 2345 6789");
        KaspiAccountPayload kaspi4 = new KaspiAccountPayload("KASPI", "id4");
        kaspi4.setAccountNr("4400430123456789");
        assertArrayEquals(kaspi3.getPaymentEndpointData(), kaspi4.getPaymentEndpointData());
    }

    @Test
    public void testEndpointNormalizesUnicodeForms() {
        // NFC vs decomposed combining mark
        assertArrayEquals(westernUnionPayload("id1", "Montr\u00e9al", "a@b.com").getPaymentEndpointData(),
                westernUnionPayload("id2", "Montre\u0301al", "a@b.com").getPaymentEndpointData());
        // full-width digits fold to ASCII digits
        assertArrayEquals(sepaPayload("id1", "Name", "DE89３７０400440532013000", "BIC1").getPaymentEndpointData(),
                sepaPayload("id2", "Name", "DE89370400440532013000", "BIC2").getPaymentEndpointData());
        // full-width digits fold before phone normalization
        PayNowAccountPayload payNow1 = new PayNowAccountPayload("PAYNOW", "id1");
        payNow1.setAccountNr("９１２３４５６７");
        PayNowAccountPayload payNow2 = new PayNowAccountPayload("PAYNOW", "id2");
        payNow2.setAccountNr("+65 9123 4567");
        assertArrayEquals(payNow1.getPaymentEndpointData(), payNow2.getPaymentEndpointData());
        // digits of scripts NFKC does not map, like Arabic-Indic, fold to ASCII digits
        BizumAccountPayload bizum1 = new BizumAccountPayload("BIZUM", "id1");
        bizum1.setMobileNr("٦١٢٣٤٥٦٧٨");
        BizumAccountPayload bizum2 = new BizumAccountPayload("BIZUM", "id2");
        bizum2.setMobileNr("612 345 678");
        assertArrayEquals(bizum1.getPaymentEndpointData(), bizum2.getPaymentEndpointData());
        // digit scripts also fold in generic identifier fields
        assertArrayEquals(sepaPayload("id1", "Name", "DE٨٩370400440532013000", "BIC1").getPaymentEndpointData(),
                sepaPayload("id2", "Name", "DE89370400440532013000", "BIC2").getPaymentEndpointData());
    }

    @Test
    public void testMultiRegionMixedIdentifierPhonesHaveNoStableEndpoint() {
        MercadoPagoAccountPayload phone = new MercadoPagoAccountPayload("MERCADO_PAGO", "id1");
        phone.setEmailOrMobileNr("+54 9 11 1234-5678");
        assertNull(phone.getPaymentEndpointData());
        MercadoPagoAccountPayload email = new MercadoPagoAccountPayload("MERCADO_PAGO", "id2");
        email.setEmailOrMobileNr("payee@example.com");
        assertNotNull(email.getPaymentEndpointData());
    }

    @Test
    public void testMultiRegionMobileMethodsHaveNoStableEndpoint() {
        MpesaAccountPayload mpesa = new MpesaAccountPayload("MPESA", "id1");
        mpesa.setMobileNr("+254 712 345678");
        assertNull(mpesa.getPaymentEndpointData());
        VippsMobilePayAccountPayload vipps = new VippsMobilePayAccountPayload("VIPPS_MOBILEPAY", "id2");
        vipps.setMobileNr("+47 12345678");
        assertNull(vipps.getPaymentEndpointData());
    }

    @Test
    public void testSatispayEndpointNormalizesItalianMobileForms() {
        assertArrayEquals(satispayPayload("id1", "+39 340 123 4567").getPaymentEndpointData(),
                satispayPayload("id2", "340 123 4567").getPaymentEndpointData());
        assertArrayEquals(satispayPayload("id1", "0039 340 123 4567").getPaymentEndpointData(),
                satispayPayload("id2", "3401234567").getPaymentEndpointData());
        assertFalse(Arrays.equals(satispayPayload("id1", "340 123 4567").getPaymentEndpointData(),
                satispayPayload("id2", "340 123 4568").getPaymentEndpointData()));
    }

    private static SatispayAccountPayload satispayPayload(String id, String mobileNr) {
        SatispayAccountPayload payload = new SatispayAccountPayload("SATISPAY", id);
        payload.setMobileNr(mobileNr);
        return payload;
    }

    private static MbWayAccountPayload mbWayPayload(String id, String mobileNr) {
        MbWayAccountPayload payload = new MbWayAccountPayload("MB_WAY", id);
        payload.setMobileNr(mobileNr);
        return payload;
    }

    private static SwishAccountPayload swishPayload(String id, String mobileNr) {
        SwishAccountPayload payload = new SwishAccountPayload("SWISH", id);
        payload.setMobileNr(mobileNr);
        return payload;
    }

    private static TwintAccountPayload twintPayload(String id, String mobileNr) {
        TwintAccountPayload payload = new TwintAccountPayload("TWINT", id);
        payload.setMobileNr(mobileNr);
        return payload;
    }

    private static PagoMovilAccountPayload pagoMovilPayload(String id, String mobileNr) {
        PagoMovilAccountPayload payload = new PagoMovilAccountPayload("PAGO_MOVIL", id);
        payload.setMobileNr(mobileNr);
        return payload;
    }

    @Test
    public void testMixedIdentifierEndpointsNormalizeNanpPhoneForms() {
        assertArrayEquals(cashAppPayload("id1", "+1 202 555 0123").getPaymentEndpointData(),
                cashAppPayload("id2", "202-555-0123").getPaymentEndpointData());
        assertArrayEquals(venmoPayload("id1", "+1 202 555 0123").getPaymentEndpointData(),
                venmoPayload("id2", "202-555-0123").getPaymentEndpointData());
        assertNotNull(payPalPayload("id1", "+1 202 555 0123").getPaymentEndpointData());
        assertArrayEquals(payPalPayload("id1", "+1 202 555 0123").getPaymentEndpointData(),
                payPalPayload("id2", "202-555-0123").getPaymentEndpointData());
        assertArrayEquals(popmoneyPayload("id1", "+1 202 555 0123").getPaymentEndpointData(),
                popmoneyPayload("id2", "202-555-0123").getPaymentEndpointData());
        assertArrayEquals(cashAppPayload("id1", "$CashTag").getPaymentEndpointData(),
                cashAppPayload("id2", "$cashtag").getPaymentEndpointData());
    }

    private static CashAppAccountPayload cashAppPayload(String id, String identifier) {
        CashAppAccountPayload payload = new CashAppAccountPayload("CASH_APP", id);
        payload.setEmailOrMobileNrOrCashtag(identifier);
        return payload;
    }

    private static VenmoAccountPayload venmoPayload(String id, String identifier) {
        VenmoAccountPayload payload = new VenmoAccountPayload("VENMO", id);
        payload.setEmailOrMobileNrOrUsername(identifier);
        return payload;
    }

    private static PayPalAccountPayload payPalPayload(String id, String identifier) {
        PayPalAccountPayload payload = new PayPalAccountPayload("PAYPAL", id);
        payload.setEmailOrMobileNrOrUsername(identifier);
        return payload;
    }

    private static PopmoneyAccountPayload popmoneyPayload(String id, String identifier) {
        PopmoneyAccountPayload payload = new PopmoneyAccountPayload("POPMONEY", id);
        payload.setAccountId(identifier);
        return payload;
    }

    @Test
    public void testVerifyPaymentAccountPayloadBindsMethodIdToContract() {
        ZelleAccountPayload payload = new ZelleAccountPayload("ZELLE", "id1");
        payload.setEmailOrMobileNr("payee@example.com");
        HavenoUtils.verifyPaymentAccountPayload(payload, "ZELLE", payload.getHash(), "buyer");
        assertThrows(IllegalArgumentException.class,
                () -> HavenoUtils.verifyPaymentAccountPayload(payload, "SWISH", payload.getHash(), "buyer"));
    }

    @Test
    public void testVerifyPaymentAccountPayloadBindsPayloadTypeToMethodId() {
        AdvancedCashAccountPayload payload = new AdvancedCashAccountPayload("ZELLE", "id1");
        assertThrows(IllegalArgumentException.class,
                () -> HavenoUtils.verifyPaymentAccountPayload(payload, "ZELLE", payload.getHash(), "buyer"));
    }

    @Test
    public void testVerifyPaymentAccountPayloadAcceptsRetainedLegacyMethods() {
        ChaseQuickPayAccountPayload chase = new ChaseQuickPayAccountPayload("CHASE_QUICK_PAY", "id1");
        HavenoUtils.verifyPaymentAccountPayload(chase, "CHASE_QUICK_PAY", chase.getHash(), "buyer");
        OKPayAccountPayload okPay = new OKPayAccountPayload("OK_PAY", "id1");
        HavenoUtils.verifyPaymentAccountPayload(okPay, "OK_PAY", okPay.getHash(), "buyer");
    }

    @Test
    public void testPayPalAmbiguousPhoneFormsHaveNoStableEndpoint() {
        assertNull(payPalPayload("id1", "+44 7700 900123").getPaymentEndpointData());
        assertNull(payPalPayload("id2", "07700 900123").getPaymentEndpointData());
        assertNull(payPalPayload("id3", "0044 7700 900123").getPaymentEndpointData());
        assertNotNull(payPalPayload("id4", "payee@example.com").getPaymentEndpointData());
        assertNotNull(payPalPayload("id5", "SomeUsername").getPaymentEndpointData());
    }

    @Test
    public void testCashAppAmbiguousPhoneFormsHaveNoStableEndpoint() {
        // Cash App also supports UK accounts, so non-NANP phone forms cannot be canonicalized
        assertNull(cashAppPayload("id1", "+44 7700 900123").getPaymentEndpointData());
        assertNull(cashAppPayload("id2", "07700 900123").getPaymentEndpointData());
        assertNotNull(cashAppPayload("id3", "+1 202 555 0123").getPaymentEndpointData());
        assertNotNull(cashAppPayload("id4", "$CashTag").getPaymentEndpointData());
    }
}
