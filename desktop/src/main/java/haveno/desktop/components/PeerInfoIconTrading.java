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

package haveno.desktop.components;

import haveno.common.util.Tuple5;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.alert.PrivateNotificationManager;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.Trade;
import haveno.core.user.Preferences;
import haveno.network.p2p.NodeAddress;
import javafx.scene.paint.Color;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.util.Date;

import static com.google.common.base.Preconditions.checkNotNull;
import static haveno.desktop.util.Colors.AVATAR_BLUE;
import static haveno.desktop.util.Colors.AVATAR_GREEN;
import static haveno.desktop.util.Colors.AVATAR_ORANGE;
import static haveno.desktop.util.Colors.AVATAR_RED;

@Slf4j
public class PeerInfoIconTrading extends PeerInfoIcon {
    private final AccountAgeWitnessService accountAgeWitnessService;
    private boolean isTraditionalCurrency;

    public PeerInfoIconTrading(NodeAddress nodeAddress,
                               String role,
                               int numTrades,
                               PrivateNotificationManager privateNotificationManager,
                               Offer offer,
                               Preferences preferences,
                               AccountAgeWitnessService accountAgeWitnessService,
                               boolean useDevPrivilegeKeys) {
        this(nodeAddress,
                role,
                numTrades,
                privateNotificationManager,
                offer,
                null,
                preferences,
                accountAgeWitnessService,
                useDevPrivilegeKeys);
    }

    public PeerInfoIconTrading(NodeAddress nodeAddress,
                               String role,
                               int numTrades,
                               PrivateNotificationManager privateNotificationManager,
                               Trade Trade,
                               Preferences preferences,
                               AccountAgeWitnessService accountAgeWitnessService,
                               boolean useDevPrivilegeKeys) {
        this(nodeAddress,
                role,
                numTrades,
                privateNotificationManager,
                Trade.getOffer(),
                Trade,
                preferences,
                accountAgeWitnessService,
                useDevPrivilegeKeys);
    }

    private PeerInfoIconTrading(NodeAddress nodeAddress,
                                String role,
                                int numTrades,
                                PrivateNotificationManager privateNotificationManager,
                                @Nullable Offer offer,
                                @Nullable Trade trade,
                                Preferences preferences,
                                AccountAgeWitnessService accountAgeWitnessService,
                                boolean useDevPrivilegeKeys) {
        super(nodeAddress, preferences);
        this.numTrades = numTrades;
        this.accountAgeWitnessService = accountAgeWitnessService;
        if (offer == null) {
            checkNotNull(trade, "Trade must not be null if offer is null.");
            offer = trade.getOffer();
        }
        checkNotNull(offer, "Offer must not be null");
        isTraditionalCurrency = offer.isTraditionalOffer();
        initialize(role, offer, trade, privateNotificationManager, useDevPrivilegeKeys);
    }

    protected void initialize(String role,
                              Offer offer,
                              Trade trade,
                              PrivateNotificationManager privateNotificationManager,
                              boolean useDevPrivilegeKeys) {
        boolean hasTraded = numTrades > 0;
        Tuple5<Long, Long, String, String, String> peersAccount = getPeersAccountAge(trade, offer);

        Long accountAge = peersAccount.first;
        Long signAge = peersAccount.second;

        tooltipText = hasTraded ?
                Res.get("peerInfoIcon.tooltip.trade.traded", role, fullAddress, numTrades, getAccountAgeTooltip(accountAge)) :
                Res.get("peerInfoIcon.tooltip.trade.notTraded", role, fullAddress, getAccountAgeTooltip(accountAge));

        createAvatar(getRingColor(offer, trade, accountAge, signAge));
        addMouseListener(numTrades, privateNotificationManager, trade, offer, preferences, useDevPrivilegeKeys,
                isTraditionalCurrency, accountAge, signAge, peersAccount.third, peersAccount.fourth, peersAccount.fifth);
    }

    @Override
    protected String getAccountAgeTooltip(Long accountAge) {
        return isTraditionalCurrency ? super.getAccountAgeTooltip(accountAge) : "";
    }

    protected Color getRingColor(Offer offer, Trade Trade, Long accountAge, Long signAge) {
        // outer circle
        // for cryptos we always display green
        Color ringColor = AVATAR_GREEN;
        if (isTraditionalCurrency) {
            switch (accountAgeWitnessService.getPeersAccountAgeCategory(hasChargebackRisk(Trade, offer) ? signAge : accountAge)) {
                case TWO_MONTHS_OR_MORE:
                    ringColor = AVATAR_GREEN;
                    break;
                case ONE_TO_TWO_MONTHS:
                    ringColor = AVATAR_BLUE;
                    break;
                case LESS_ONE_MONTH:
                    ringColor = AVATAR_ORANGE;
                    break;
                case UNVERIFIED:
                default:
                    ringColor = AVATAR_RED;
                    break;
            }
        }
        return ringColor;
    }

    /**
     * @param Trade Open trade for trading peer info to be shown
     * @param offer Open offer for trading peer info to be shown
     * @return account age, sign age, account info, sign info, sign state
     */
    private Tuple5<Long, Long, String, String, String> getPeersAccountAge(@Nullable Trade Trade,
                                                                          @Nullable Offer offer) {
        AccountAgeWitnessService.SignState signState = null;
        long signAge = -1L;
        long accountAge = -1L;

        if (Trade != null) {
            offer = Trade.getOffer();
            if (offer == null) {
                // unexpected
                return new Tuple5<>(signAge, accountAge, Res.get("peerInfo.age.noRisk"), null, null);
            }
            if (Trade instanceof Trade) {
                Trade trade = Trade;
                signState = accountAgeWitnessService.getSignState(trade);
                signAge = accountAgeWitnessService.getWitnessSignAge(trade, new Date());
                accountAge = accountAgeWitnessService.getAccountAge(trade);
            }
        } else {
            checkNotNull(offer, "Offer must not be null if trade is null.");
            signState = accountAgeWitnessService.getSignState(offer);
            signAge = accountAgeWitnessService.getWitnessSignAge(offer, new Date());
            accountAge = accountAgeWitnessService.getAccountAge(offer);
        }

        if (signState != null && hasChargebackRisk(Trade, offer)) {
            String signAgeInfo = Res.get("peerInfo.age.chargeBackRisk");
            String accountSigningState = StringUtils.capitalize(signState.getDisplayString());
            if (signState.equals(AccountAgeWitnessService.SignState.UNSIGNED)) {
                signAgeInfo = null;
            }

            return new Tuple5<>(accountAge, signAge, Res.get("peerInfo.age.noRisk"), signAgeInfo, accountSigningState);
        }
        return new Tuple5<>(accountAge, signAge, Res.get("peerInfo.age.noRisk"), null, null);
    }

    private static boolean hasChargebackRisk(@Nullable Trade Trade, @Nullable Offer offer) {
        Offer offerToCheck = Trade != null ? Trade.getOffer() : offer;

        return offerToCheck != null &&
                PaymentMethod.hasChargebackRisk(offerToCheck.getPaymentMethod(), offerToCheck.getCurrencyCode());
    }
}
