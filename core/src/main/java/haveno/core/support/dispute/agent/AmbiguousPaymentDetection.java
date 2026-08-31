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

package haveno.core.support.dispute.agent;

import haveno.common.crypto.Hash;
import haveno.common.crypto.PubKeyRing;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.monetary.Volume;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import javafx.collections.ListChangeListener;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Detects disputes of different trades which share the same buyer and seller payment accounts and the same
 * trade amount with overlapping trade periods, so a single payment could be claimed for multiple trades.
 * Detection is limited to disputes visible to this dispute agent; a duplicate trade which completed without
 * a dispute, or whose dispute is assigned to a different agent, cannot be correlated. A trader using
 * multiple identities can only be correlated through its disclosed payment account payloads.
 * Strings are not translated here as it is only visible to dispute agents.
 */
@Slf4j
public class AmbiguousPaymentDetection {

    private final DisputeManager<? extends DisputeList<Dispute>> disputeManager;
    private final TradeManager tradeManager;
    private final Map<String, List<Dispute>> ambiguousDisputesByKey = new HashMap<>();

    public AmbiguousPaymentDetection(DisputeManager<? extends DisputeList<Dispute>> disputeManager, TradeManager tradeManager) {
        this.disputeManager = disputeManager;
        this.tradeManager = tradeManager;
        disputeManager.getDisputesAsObservableList().addListener((ListChangeListener<Dispute>) c -> {
            boolean changed = false;
            while (c.next()) changed |= c.wasAdded() || c.wasRemoved();
            if (changed) detectAmbiguousPayments();
        });
    }

    public void detectAmbiguousPayments() {
        Map<String, List<Dispute>> disputesByKey = new HashMap<>();
        for (Dispute dispute : disputeManager.getDisputesAsObservableList()) {
            for (String key : getKeys(dispute)) disputesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(dispute);
        }
        ambiguousDisputesByKey.clear();
        disputesByKey.forEach((key, disputes) -> {
            List<Dispute> ambiguous = disputes.stream()
                    .filter(dispute -> disputes.stream().anyMatch(other -> isOverlappingOtherTrade(dispute, other)))
                    .sorted(Comparator.comparing(Dispute::getId))
                    .collect(Collectors.toList());
            if (ambiguous.stream().map(Dispute::getTradeId).distinct().count() > 1) ambiguousDisputesByKey.put(key, ambiguous);
        });
    }

    // returns all disputes whose payments are indistinguishable from the given dispute's payment
    public List<Dispute> getDisputesForDispute(Dispute dispute) {
        List<Dispute> result = new ArrayList<>();
        for (String key : getKeys(dispute)) {
            List<Dispute> disputes = ambiguousDisputesByKey.get(key);
            if (disputes == null || !disputes.contains(dispute)) continue;
            for (Dispute other : disputes) if (!result.contains(other)) result.add(other);
        }
        result.sort(Comparator.comparing(Dispute::getId));
        return result;
    }

    public String getReport(List<Dispute> disputes) {
        Set<String> tradeIds = new HashSet<>();
        return disputes.stream()
                .filter(dispute -> tradeIds.add(dispute.getTradeId()))
                .map(dispute -> {
                    Volume volume = dispute.getContract().getTradeVolume();
                    return "Trade ID: '" + dispute.getShortTradeId() +
                            "'\n        Amount: '" + volume + " " + volume.getCurrencyCode() +
                            "'\n        Payment method: '" + Res.get(dispute.getContract().getPaymentMethodId()) +
                            "'\n        Trade period: '" + getTradeDate(dispute) + "' to '" + getTradePeriodEnd(dispute) +
                            "'\n        Opened by: '" + (dispute.isDisputeOpenerIsBuyer() ? "Buyer" : "Seller") + "'";
                })
                .collect(Collectors.joining("\n"));
    }

    public static String getAckKey(Dispute dispute) {
        return "AmbiguousPaymentAck-" + dispute.getTradeId() + "-" + dispute.getTraderId();
    }

    // key by the external payment endpoints of both accounts and the trade amount, since payment account ids,
    // payload salts, and mutable display fields are not stable identities; empty if unavailable
    private static Set<String> getKeys(Dispute dispute) {
        try {
            Volume volume = dispute.getContract().getTradeVolume();
            if (volume == null) return new HashSet<>();
            Set<String> keys = new HashSet<>();
            for (String buyerPart : getEndpointKeyParts(dispute.getBuyerPaymentAccountPayload(), dispute.getContract().getBuyerPaymentMethodId(), dispute.getContract().getBuyerPubKeyRing())) {
                for (String sellerPart : getEndpointKeyParts(dispute.getSellerPaymentAccountPayload(), dispute.getContract().getSellerPaymentMethodId(), dispute.getContract().getSellerPubKeyRing())) {
                    keys.add(buyerPart + "|" + sellerPart + "|" + volume.getValue() + "|" + volume.getCurrencyCode());
                }
            }
            return keys;
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    // Hash of the account's external endpoint, plus the endpoint namespace scoped to the trader's key. The
    // namespace part conservatively matches the trader's other accounts of the same transfer rail, since two
    // accounts can identify one underlying account and a payload can be missing entirely.
    private static List<String> getEndpointKeyParts(PaymentAccountPayload payload, String paymentMethodId, PubKeyRing traderPubKeyRing) {
        List<String> parts = new ArrayList<>();
        byte[] endpointData = payload == null ? null : payload.getPaymentEndpointData();
        if (endpointData != null) parts.add(Utilities.encodeToHex(Hash.getSha256Hash(endpointData)));
        // key on the signature key only, since a modified peer could keep its identity but vary its encryption key
        parts.add(getEndpointNamespace(paymentMethodId) + ":" + Utilities.encodeToHex(Hash.getSha256Hash(traderPubKeyRing.getSignaturePubKeyBytes())));
        return parts;
    }

    // endpoint namespace of a payment method, falling back to the raw method id for unknown methods
    private static String getEndpointNamespace(String paymentMethodId) {
        try {
            return HavenoUtils.getPaymentEndpointNamespace(paymentMethodId);
        } catch (Exception e) {
            return paymentMethodId;
        }
    }

    // Payments are only ambiguous across different trades with overlapping trade periods. Trade dates are
    // peer-supplied within a 1-day tolerance, so periods are padded by the 2-day relative skew.
    private boolean isOverlappingOtherTrade(Dispute dispute, Dispute other) {
        if (dispute.getTradeId().equals(other.getTradeId())) return false;
        long skewMs = TimeUnit.DAYS.toMillis(2);
        return getTradeDate(dispute).getTime() <= getTradePeriodEnd(other).getTime() + skewMs
                && getTradeDate(other).getTime() <= getTradePeriodEnd(dispute).getTime() + skewMs;
    }

    // prefer our own trade's dates, since the dispute opener's dates are not verifiable
    private Date getTradeDate(Dispute dispute) {
        Trade trade = tradeManager.getTrade(dispute.getTradeId());
        return trade == null ? dispute.getTradeDate() : trade.getDate();
    }

    private Date getTradePeriodEnd(Dispute dispute) {
        Trade trade = tradeManager.getTrade(dispute.getTradeId());
        return trade == null ? dispute.getTradePeriodEnd() : trade.getMaxTradePeriodDate();
    }
}
