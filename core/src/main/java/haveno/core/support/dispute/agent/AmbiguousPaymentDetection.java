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
import javafx.collections.ListChangeListener;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects disputes of different trades which share the same buyer and seller payment accounts and the same
 * trade amount with overlapping trade periods, so a single payment could be claimed for multiple trades.
 * Detection is limited to disputes visible to this dispute agent; a duplicate trade which completed without
 * a dispute, or whose dispute is assigned to a different agent, cannot be correlated.
 * Strings are not translated here as it is only visible to dispute agents.
 */
@Slf4j
public class AmbiguousPaymentDetection {

    private final DisputeManager<? extends DisputeList<Dispute>> disputeManager;
    private final Map<String, List<Dispute>> ambiguousDisputesByKey = new HashMap<>();

    public AmbiguousPaymentDetection(DisputeManager<? extends DisputeList<Dispute>> disputeManager) {
        this.disputeManager = disputeManager;
        disputeManager.getDisputesAsObservableList().addListener((ListChangeListener<Dispute>) c -> {
            boolean changed = false;
            while (c.next()) changed |= c.wasAdded() || c.wasRemoved();
            if (changed) detectAmbiguousPayments();
        });
    }

    public void detectAmbiguousPayments() {
        Map<String, List<Dispute>> disputesByKey = new HashMap<>();
        for (Dispute dispute : disputeManager.getDisputesAsObservableList()) {
            String key = getKey(dispute);
            if (key != null) disputesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(dispute);
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
        List<Dispute> disputes = ambiguousDisputesByKey.get(getKey(dispute));
        return disputes != null && disputes.contains(dispute) ? disputes : new ArrayList<>();
    }

    public static String getReport(List<Dispute> disputes) {
        Set<String> tradeIds = new HashSet<>();
        return disputes.stream()
                .filter(dispute -> tradeIds.add(dispute.getTradeId()))
                .map(dispute -> {
                    Volume volume = dispute.getContract().getTradeVolume();
                    return "Trade ID: '" + dispute.getShortTradeId() +
                            "'\n        Amount: '" + volume + " " + volume.getCurrencyCode() +
                            "'\n        Payment method: '" + Res.get(dispute.getContract().getPaymentMethodId()) +
                            "'\n        Trade period: '" + dispute.getTradeDate() + "' to '" + dispute.getTradePeriodEnd() +
                            "'\n        Opened by: '" + (dispute.isDisputeOpenerIsBuyer() ? "Buyer" : "Seller") + "'";
                })
                .collect(Collectors.joining("\n"));
    }

    public static String getAckKey(Dispute dispute) {
        return "AmbiguousPaymentAck-" + dispute.getShortTradeId() + "-" + dispute.getTraderId();
    }

    // key by the external payment endpoints of both accounts and the trade amount, since payment account ids,
    // payload salts, and mutable display fields are not stable identities; null if unavailable
    private static String getKey(Dispute dispute) {
        try {
            PaymentAccountPayload buyerPayload = dispute.getBuyerPaymentAccountPayload();
            PaymentAccountPayload sellerPayload = dispute.getSellerPaymentAccountPayload();
            Volume volume = dispute.getContract().getTradeVolume();
            if (buyerPayload == null || sellerPayload == null || volume == null) return null;
            return getEndpointKeyPart(buyerPayload, dispute.getContract().getBuyerPubKeyRing()) + "|" +
                    getEndpointKeyPart(sellerPayload, dispute.getContract().getSellerPubKeyRing()) + "|" +
                    volume.getValue() + "|" + volume.getCurrencyCode();
        } catch (Exception e) {
            return null;
        }
    }

    // hash of the account's external endpoint, or the payment method id scoped to the trader's key to
    // conservatively treat that trader's accounts of a method without a stable endpoint as the same account
    private static String getEndpointKeyPart(PaymentAccountPayload payload, PubKeyRing traderPubKeyRing) {
        byte[] endpointData = payload.getPaymentEndpointData();
        if (endpointData != null) return Utilities.encodeToHex(Hash.getSha256Hash(endpointData));
        return payload.getPaymentMethodId() + ":" + Utilities.encodeToHex(Hash.getSha256Hash(traderPubKeyRing.toProtoMessage().toByteArray()));
    }

    // payments are only ambiguous across different trades with overlapping trade periods
    private static boolean isOverlappingOtherTrade(Dispute dispute, Dispute other) {
        if (dispute.getTradeId().equals(other.getTradeId())) return false;
        return !dispute.getTradeDate().after(other.getTradePeriodEnd()) && !other.getTradeDate().after(dispute.getTradePeriodEnd());
    }
}
