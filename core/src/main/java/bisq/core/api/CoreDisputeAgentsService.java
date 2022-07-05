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

package bisq.core.api;

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.support.SupportType;
import bisq.core.support.dispute.arbitration.arbitrator.Arbitrator;
import bisq.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.core.support.dispute.mediation.mediator.MediatorManager;
import bisq.core.support.dispute.refund.refundagent.RefundAgent;
import bisq.core.support.dispute.refund.refundagent.RefundAgentManager;
import bisq.core.user.User;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;

import bisq.common.config.Config;
import bisq.common.crypto.KeyRing;

import org.bitcoinj.core.ECKey;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import static bisq.core.support.SupportType.ARBITRATION;
import static bisq.core.support.SupportType.MEDIATION;
import static bisq.core.support.SupportType.REFUND;
import static bisq.core.support.SupportType.TRADE;
import static java.lang.String.format;
import static java.net.InetAddress.getLoopbackAddress;
import static java.util.Arrays.asList;

@Singleton
@Slf4j
class CoreDisputeAgentsService {

    private final User user;
    private final Config config;
    private final KeyRing keyRing;
    private final XmrWalletService xmrWalletService;
    private final ArbitratorManager arbitratorManager;
    private final MediatorManager mediatorManager;
    private final RefundAgentManager refundAgentManager;
    private final P2PService p2PService;
    private final NodeAddress nodeAddress;
    private final List<String> languageCodes;

    @Inject
    public CoreDisputeAgentsService(User user,
                                    Config config,
                                    KeyRing keyRing,
                                    XmrWalletService xmrWalletService,
                                    ArbitratorManager arbitratorManager,
                                    MediatorManager mediatorManager,
                                    RefundAgentManager refundAgentManager,
                                    P2PService p2PService) {
        this.user = user;
        this.config = config;
        this.keyRing = keyRing;
        this.xmrWalletService = xmrWalletService;
        this.arbitratorManager = arbitratorManager;
        this.mediatorManager = mediatorManager;
        this.refundAgentManager = refundAgentManager;
        this.p2PService = p2PService;
        this.nodeAddress = new NodeAddress(getLoopbackAddress().getHostName(), config.nodePort);
        this.languageCodes = asList("de", "en", "es", "fr");
    }

    void registerDisputeAgent(String disputeAgentType, String registrationKey) {
        if (!p2PService.isBootstrapped())
            throw new IllegalStateException("p2p service is not bootstrapped yet");

        if (config.baseCurrencyNetwork.isMainnet()
                || !config.useLocalhostForP2P)
            throw new IllegalStateException("dispute agents must be registered in a Bisq UI");

        Optional<SupportType> supportType = getSupportType(disputeAgentType);
        if (supportType.isPresent()) {
            ECKey ecKey;
            String signature;
            switch (supportType.get()) {
                case ARBITRATION:
                    if (user.getRegisteredArbitrator() != null) {
                        log.warn("ignoring request to re-register as arbitrator");
                        return;
                    }
                    ecKey = arbitratorManager.getRegistrationKey(registrationKey);
                    if (ecKey == null) throw new IllegalStateException("invalid registration key");
                    signature = arbitratorManager.signStorageSignaturePubKey(Objects.requireNonNull(ecKey));
                    registerArbitrator(nodeAddress, languageCodes, ecKey, signature);
                    return;
                case MEDIATION:
                    if (user.getRegisteredMediator() != null) {
                        log.warn("ignoring request to re-register as mediator");
                        return;
                    }
                    ecKey = mediatorManager.getRegistrationKey(registrationKey);
                    if (ecKey == null) throw new IllegalStateException("invalid registration key");
                    signature = mediatorManager.signStorageSignaturePubKey(Objects.requireNonNull(ecKey));
                    registerMediator(nodeAddress, languageCodes, ecKey, signature);
                    return;
                case REFUND:
                    if (user.getRegisteredRefundAgent() != null) {
                        log.warn("ignoring request to re-register as refund agent");
                        return;
                    }
                    ecKey = refundAgentManager.getRegistrationKey(registrationKey);
                    if (ecKey == null) throw new IllegalStateException("invalid registration key");
                    signature = refundAgentManager.signStorageSignaturePubKey(Objects.requireNonNull(ecKey));
                    registerRefundAgent(nodeAddress, languageCodes, ecKey, signature);
                    return;
                case TRADE:
                    throw new IllegalArgumentException("trade agent registration not supported");
            }
        } else {
            throw new IllegalArgumentException(format("unknown dispute agent type '%s'", disputeAgentType));
        }
    }

    private void registerArbitrator(NodeAddress nodeAddress,
                                    List<String> languageCodes,
                                    ECKey ecKey,
                                    String signature) {
        Arbitrator arbitrator = new Arbitrator(
                p2PService.getAddress(),
                xmrWalletService.getWallet().getPrimaryAddress(), // TODO: how is this used?
                keyRing.getPubKeyRing(),
                new ArrayList<>(languageCodes),
                new Date().getTime(),
                ecKey.getPubKey(),
                signature,
                "",
                null,
                null);
        arbitratorManager.addDisputeAgent(arbitrator, () -> {
        }, errorMessage -> {
        });
        arbitratorManager.getDisputeAgentByNodeAddress(nodeAddress).orElseThrow(() ->
                new IllegalStateException("could not register arbitrator"));
    }

    private void registerMediator(NodeAddress nodeAddress,
                                  List<String> languageCodes,
                                  ECKey ecKey,
                                  String signature) {
        Mediator mediator = new Mediator(nodeAddress,
                keyRing.getPubKeyRing(),
                languageCodes,
                new Date().getTime(),
                ecKey.getPubKey(),
                signature,
                null,
                null,
                null
        );
        mediatorManager.addDisputeAgent(mediator, () -> {
        }, errorMessage -> {
        });
        mediatorManager.getDisputeAgentByNodeAddress(nodeAddress).orElseThrow(() ->
                new IllegalStateException("could not register mediator"));
    }

    private void registerRefundAgent(NodeAddress nodeAddress,
                                     List<String> languageCodes,
                                     ECKey ecKey,
                                     String signature) {
        RefundAgent refundAgent = new RefundAgent(nodeAddress,
                keyRing.getPubKeyRing(),
                languageCodes,
                new Date().getTime(),
                ecKey.getPubKey(),
                signature,
                null,
                null,
                null
        );
        refundAgentManager.addDisputeAgent(refundAgent, () -> {
        }, errorMessage -> {
        });
        refundAgentManager.getDisputeAgentByNodeAddress(nodeAddress).orElseThrow(() ->
                new IllegalStateException("could not register refund agent"));
    }

    private Optional<SupportType> getSupportType(String disputeAgentType) {
        switch (disputeAgentType.toLowerCase()) {
            case "arbitrator":
                return Optional.of(ARBITRATION);
            case "mediator":
                return Optional.of(MEDIATION);
            case "refundagent":
            case "refund_agent":
                return Optional.of(REFUND);
            case "tradeagent":
            case "trade_agent":
                return Optional.of(TRADE);
            default:
                return Optional.empty();
        }
    }
}
