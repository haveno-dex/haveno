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

package haveno.core.offer;

import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.PubKeyRingProvider;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.api.CoreOffersService;
import haveno.core.filter.FilterManager;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.VenmoAccount;
import haveno.core.payment.ZelleAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.BuyerAsMakerTrade;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeUtil;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.statistics.ReferralIdService;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static haveno.core.offer.OfferPayload.ZELLE_ONE_DAY_TRADE_PERIOD;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ZelleTradePeriodTest {
    private static final long LEGACY_PERIOD = PaymentMethod.ZELLE_LEGACY_MAX_TRADE_PERIOD;
    private static final long ONE_DAY = LEGACY_PERIOD / 4;
    private static final Price PRICE = Price.valueOf("USD", 1500000L);

    @TempDir
    Path keyDirectory;

    private KeyRing keyRing;
    private XmrWalletService walletService;
    private CreateOfferService createOfferService;
    private CoreOffersService coreOffersService;
    private ZelleAccount zelleAccount;

    @BeforeEach
    public void setUp() {
        Res.setup();
        keyRing = new KeyRing(new KeyStorage(keyDirectory.toFile()), null, true);
        walletService = mock(XmrWalletService.class);
        AccountAgeWitnessService witnessService = mock(AccountAgeWitnessService.class);
        when(witnessService.getMyTradeLimit(any(), anyString(), any(), anyBoolean()))
                .thenReturn(HavenoUtils.xmrToAtomicUnits(10).longValueExact());
        when(witnessService.getMyWitnessHashAsHex(any())).thenReturn("witness");
        P2PService p2PService = mock(P2PService.class);
        when(p2PService.getAddress()).thenReturn(new NodeAddress("maker.onion", 9999));
        OfferUtil offerUtil = new OfferUtil(witnessService, mock(FilterManager.class), mock(Preferences.class),
                mock(PriceFeedService.class), p2PService, mock(ReferralIdService.class));
        PubKeyRingProvider pubKeyRingProvider = mock(PubKeyRingProvider.class);
        when(pubKeyRingProvider.get()).thenReturn(keyRing.getPubKeyRing());
        createOfferService = new CreateOfferService(offerUtil, mock(PriceFeedService.class), p2PService,
                pubKeyRingProvider, null, walletService, null, null);
        coreOffersService = new CoreOffersService(null, keyRing, null, createOfferService,
                null, null, null, offerUtil, null, mock(PriceFeedService.class), null);
        zelleAccount = new ZelleAccount();
        zelleAccount.init();
        zelleAccount.setAccountName("Zelle");
    }

    @Test
    public void testNewAndRestoredZelleAccountsCreateOneDayOffers() {
        protobuf.PaymentAccount persisted = zelleAccount.toProtoMessage().toBuilder()
                .setPaymentMethod(PaymentMethod.ZELLE.toProtoMessage().toBuilder().setMaxTradePeriod(LEGACY_PERIOD))
                .build();
        PaymentAccount restored = PaymentAccount.fromProto(persisted, new CoreProtoResolver());
        assertNotNull(restored);
        for (PaymentAccount account : new PaymentAccount[]{zelleAccount, restored}) {
            assertEquals(ONE_DAY, account.getMaxTradePeriod());
            Offer offer = createOffer(account);
            assertEquals(ONE_DAY, offer.getOfferPayload().getMaxTradePeriod());
            assertEquals(ONE_DAY, offer.getMaxTradePeriod());
            assertEquals("1", offer.getExtraDataMap().get(ZELLE_ONE_DAY_TRADE_PERIOD));
        }
    }

    @Test
    public void testLegacyZelleKeepsFourDaysRegardlessOfStalePayloadPeriod() {
        // Legacy edits from Venmo or postal money orders retained their old raw period, including one day.
        for (long rawPeriod : new long[]{ONE_DAY, LEGACY_PERIOD, 8 * ONE_DAY, 0, -1, Long.MAX_VALUE}) {
            Offer offer = offerWithPeriod(PaymentMethod.ZELLE_ID, rawPeriod, Map.of());
            byte[] serialized = offer.toProtoMessage().toByteArray();
            assertEquals(LEGACY_PERIOD, offer.getMaxTradePeriod());
            assertEquals(rawPeriod, offer.getOfferPayload().getMaxTradePeriod());
            assertArrayEquals(serialized, offer.toProtoMessage().toByteArray());
        }
    }

    @Test
    public void testOnlyExactMarkerEnablesOneDayAndCannotSetArbitraryDuration() {
        for (String marker : new String[]{"", "0", "true", "2", "86400000"}) {
            assertEquals(LEGACY_PERIOD, offerWithPeriod(PaymentMethod.ZELLE_ID, ONE_DAY,
                    Map.of(ZELLE_ONE_DAY_TRADE_PERIOD, marker)).getMaxTradePeriod());
        }
        assertEquals(ONE_DAY, offerWithPeriod(PaymentMethod.ZELLE_ID, Long.MAX_VALUE,
                Map.of(ZELLE_ONE_DAY_TRADE_PERIOD, "1")).getMaxTradePeriod());
    }

    @Test
    public void testOtherPaymentMethodsKeepTheirEffectivePeriod() {
        for (String methodId : new String[]{PaymentMethod.VENMO_ID, PaymentMethod.SEPA_ID}) {
            assertEquals(PaymentMethod.getPaymentMethod(methodId).getMaxTradePeriod(),
                    offerWithPeriod(methodId, Long.MAX_VALUE, Map.of(ZELLE_ONE_DAY_TRADE_PERIOD, "1"))
                            .getMaxTradePeriod());
        }
    }

    @Test
    public void testEditingLegacyZelleRefreshesPeriodWithoutMutatingOriginalTerms() {
        Offer original = offerWithPeriod(PaymentMethod.ZELLE_ID, ONE_DAY, Map.of("original", "metadata"));
        byte[] serialized = original.toProtoMessage().toByteArray();
        byte[] signatureHash = original.getOfferPayload().getSignatureHash();
        Offer edited = coreOffersService.getEditedOffer(new OpenOffer(original), createOffer(zelleAccount).getOfferPayload());

        assertEquals(ONE_DAY, edited.getMaxTradePeriod());
        assertEquals(ONE_DAY, edited.getOfferPayload().getMaxTradePeriod());
        assertEquals("metadata", edited.getExtraDataMap().get("original"));
        assertEquals("1", edited.getExtraDataMap().get(ZELLE_ONE_DAY_TRADE_PERIOD));
        assertEquals(LEGACY_PERIOD, original.getMaxTradePeriod());
        assertArrayEquals(serialized, original.toProtoMessage().toByteArray());
        assertArrayEquals(signatureHash, original.getOfferPayload().getSignatureHash());
    }

    @Test
    public void testEditingPaymentMethodAddsAndRemovesMarker() {
        Offer original = offerWithPeriod(PaymentMethod.VENMO_ID, ONE_DAY, Map.of("original", "metadata"));
        Offer zelle = coreOffersService.getEditedOffer(new OpenOffer(original), createOffer(zelleAccount).getOfferPayload());
        assertEquals(ONE_DAY, zelle.getMaxTradePeriod());
        assertEquals("1", zelle.getExtraDataMap().get(ZELLE_ONE_DAY_TRADE_PERIOD));

        VenmoAccount venmoAccount = new VenmoAccount();
        venmoAccount.init();
        Offer venmo = coreOffersService.getEditedOffer(new OpenOffer(zelle), createOffer(venmoAccount).getOfferPayload());
        assertEquals(PaymentMethod.VENMO_ID, venmo.getPaymentMethodId());
        assertNull(venmo.getExtraDataMap().get(ZELLE_ONE_DAY_TRADE_PERIOD));
        assertEquals("metadata", venmo.getExtraDataMap().get("original"));
        assertEquals("1", zelle.getExtraDataMap().get(ZELLE_ONE_DAY_TRADE_PERIOD));
    }

    @Test
    public void testCloneOfLegacyOfferUsesOneDay() {
        Offer original = offerWithPeriod(PaymentMethod.ZELLE_ID, LEGACY_PERIOD, Map.of());
        Offer clone = createOfferService.createClonedOffer(original, "USD", PRICE, false, 0, zelleAccount, "");
        assertEquals(ONE_DAY, clone.getMaxTradePeriod());
        assertEquals(ONE_DAY, clone.getOfferPayload().getMaxTradePeriod());
        assertEquals(LEGACY_PERIOD, original.getMaxTradePeriod());
    }

    @Test
    public void testMarkerIsCoveredByArbitratorSignatureAndSurvivesSerialization() {
        Offer original = offerWithPeriod(PaymentMethod.ZELLE_ID, ONE_DAY, Map.of());
        protobuf.OfferPayload markedPayload = original.getOfferPayload().toProtoMessage().getOfferPayload().toBuilder()
                .putExtraData(ZELLE_ONE_DAY_TRADE_PERIOD, "1").build();
        Offer marked = new Offer(OfferPayload.fromProto(markedPayload));
        assertFalse(Arrays.equals(original.getOfferPayload().getSignatureHash(), marked.getOfferPayload().getSignatureHash()));
        assertEquals(ONE_DAY, Offer.fromProto(marked.toProtoMessage()).getMaxTradePeriod());
        assertEquals(LEGACY_PERIOD, Offer.fromProto(original.toProtoMessage()).getMaxTradePeriod());
    }

    @Test
    public void testPersistedTradesKeepDeadlineAndDurationConsistent() {
        Offer legacy = offerWithPeriod(PaymentMethod.ZELLE_ID, ONE_DAY, Map.of());
        Offer current = createOffer(zelleAccount);
        for (Offer offer : new Offer[]{legacy, current}) {
            Trade trade = new BuyerAsMakerTrade(offer, offer.getAmount(), offer.getFixedPrice(), walletService,
                    new ProcessModel(offer.getId(), zelleAccount.getId(), keyRing.getPubKeyRing()), "trade-uid",
                    null, null, null, null);
            long startTime = 1_700_000_000_000L;
            trade.setStartTime(startTime);
            protobuf.BuyerAsMakerTrade persisted = ((protobuf.Tradable) trade.toProtoMessage()).getBuyerAsMakerTrade();
            Trade restored = (Trade) BuyerAsMakerTrade.fromProto(persisted, walletService, new CoreProtoResolver());
            long expectedPeriod = offer == legacy ? LEGACY_PERIOD : ONE_DAY;
            assertEquals(expectedPeriod, restored.getMaxTradePeriod());
            assertEquals(expectedPeriod, new TradeUtil(null, keyRing).getMaxTradePeriod(restored));
            assertEquals(startTime + expectedPeriod / 2, restored.getHalfTradePeriodDate().getTime());
            assertEquals(startTime + expectedPeriod, restored.getMaxTradePeriodDate().getTime());
        }
    }

    private Offer createOffer(PaymentAccount account) {
        return createOfferService.createAndGetOffer("offer-id", OfferDirection.BUY, "USD",
                HavenoUtils.xmrToAtomicUnits(1), HavenoUtils.xmrToAtomicUnits(1), PRICE, false, 0,
                0.15, account, false, false, "");
    }

    private Offer offerWithPeriod(String methodId, long rawPeriod, Map<String, String> metadata) {
        protobuf.OfferPayload payload = createOffer(zelleAccount).getOfferPayload().toProtoMessage().getOfferPayload().toBuilder()
                .setPaymentMethodId(methodId)
                .setMaxTradePeriod(rawPeriod)
                .clearExtraData()
                .putAllExtraData(metadata)
                .build();
        return new Offer(OfferPayload.fromProto(payload));
    }
}
