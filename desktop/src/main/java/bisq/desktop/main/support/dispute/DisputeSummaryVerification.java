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

package haveno.desktop.main.support.dispute;

import haveno.core.locale.Res;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.agent.DisputeAgent;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.support.dispute.refund.refundagent.RefundAgentManager;

import haveno.network.p2p.NodeAddress;

import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Hash;
import haveno.common.crypto.Sig;
import haveno.common.util.Utilities;

import java.security.PublicKey;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class DisputeSummaryVerification {
    // Must not change as it is used for splitting the text for verifying the signature of the summary message
    private static final String SEPARATOR1 = "\n-----BEGIN SIGNATURE-----\n";
    private static final String SEPARATOR2 = "\n-----END SIGNATURE-----\n";

    public static String signAndApply(DisputeManager<? extends DisputeList<Dispute>> disputeManager,
                                      DisputeResult disputeResult,
                                      String textToSign) {
        throw new RuntimeException("DisputeSummaryVerification.signAndApply() not implemented");

//        byte[] hash = Hash.getSha256Hash(textToSign);
//        KeyPair signatureKeyPair = disputeManager.getSignatureKeyPair();
//        String sigAsHex;
//        try {
//            byte[] signature = Sig.sign(signatureKeyPair.getPrivate(), hash);
//            sigAsHex = Utilities.encodeToHex(signature);
//            disputeResult.setArbitratorSignature(signature);
//        } catch (CryptoException e) {
//            sigAsHex = "Signing failed";
//        }
//
//        return Res.get("disputeSummaryWindow.close.msgWithSig",
//                textToSign,
//                SEPARATOR1,
//                sigAsHex,
//                SEPARATOR2);
    }

    public static String verifySignature(String input,
                                         MediatorManager mediatorManager,
                                         RefundAgentManager refundAgentManager) {
        try {
            String[] parts = input.split(SEPARATOR1);
            String textToSign = parts[0];
            String fullAddress = textToSign.split("\n")[1].split(": ")[1];
            NodeAddress nodeAddress = new NodeAddress(fullAddress);
            DisputeAgent disputeAgent = mediatorManager.getDisputeAgentByNodeAddress(nodeAddress).orElse(null);
            if (disputeAgent == null) {
                disputeAgent = refundAgentManager.getDisputeAgentByNodeAddress(nodeAddress).orElse(null);
            }
            checkNotNull(disputeAgent);
            PublicKey pubKey = disputeAgent.getPubKeyRing().getSignaturePubKey();

            String sigString = parts[1].split(SEPARATOR2)[0];
            byte[] sig = Utilities.decodeFromHex(sigString);
            byte[] hash = Hash.getSha256Hash(textToSign);
            try {
                boolean result = Sig.verify(pubKey, hash, sig);
                if (result) {
                    return Res.get("support.sigCheck.popup.success");
                } else {
                    return Res.get("support.sigCheck.popup.failed");
                }
            } catch (CryptoException e) {
                return Res.get("support.sigCheck.popup.failed");
            }
        } catch (Throwable e) {
            return Res.get("support.sigCheck.popup.invalidFormat");
        }
    }
}
