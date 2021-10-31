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

package haveno.core.proto.network;

import haveno.core.alert.Alert;
import haveno.core.alert.PrivateNotificationMessage;
import haveno.core.filter.Filter;
import haveno.core.network.p2p.inventory.messages.GetInventoryRequest;
import haveno.core.network.p2p.inventory.messages.GetInventoryResponse;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.messages.OfferAvailabilityRequest;
import haveno.core.offer.messages.OfferAvailabilityResponse;
import haveno.core.offer.messages.SignOfferRequest;
import haveno.core.offer.messages.SignOfferResponse;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.support.dispute.arbitration.messages.PeerPublishedDisputePayoutTxMessage;
import haveno.core.support.dispute.mediation.mediator.Mediator;
import haveno.core.support.dispute.messages.ArbitratorPayoutTxRequest;
import haveno.core.support.dispute.messages.ArbitratorPayoutTxResponse;
import haveno.core.support.dispute.messages.DisputeResultMessage;
import haveno.core.support.dispute.messages.OpenNewDisputeMessage;
import haveno.core.support.dispute.messages.PeerOpenedDisputeMessage;
import haveno.core.support.dispute.refund.refundagent.RefundAgent;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.messages.CounterCurrencyTransferStartedMessage;
import haveno.core.trade.messages.DelayedPayoutTxSignatureRequest;
import haveno.core.trade.messages.DelayedPayoutTxSignatureResponse;
import haveno.core.trade.messages.DepositRequest;
import haveno.core.trade.messages.DepositResponse;
import haveno.core.trade.messages.DepositTxAndDelayedPayoutTxMessage;
import haveno.core.trade.messages.DepositTxMessage;
import haveno.core.trade.messages.InitMultisigRequest;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.messages.InputsForDepositTxRequest;
import haveno.core.trade.messages.InputsForDepositTxResponse;
import haveno.core.trade.messages.MediatedPayoutTxPublishedMessage;
import haveno.core.trade.messages.MediatedPayoutTxSignatureMessage;
import haveno.core.trade.messages.PaymentAccountPayloadRequest;
import haveno.core.trade.messages.PayoutTxPublishedMessage;
import haveno.core.trade.messages.PeerPublishedDelayedPayoutTxMessage;
import haveno.core.trade.messages.RefreshTradeStateRequest;
import haveno.core.trade.messages.SignContractRequest;
import haveno.core.trade.messages.SignContractResponse;
import haveno.core.trade.messages.TraderSignedWitnessMessage;
import haveno.core.trade.messages.UpdateMultisigRequest;
import haveno.core.trade.messages.UpdateMultisigResponse;

import haveno.network.p2p.AckMessage;
import haveno.network.p2p.BundleOfEnvelopes;
import haveno.network.p2p.CloseConnectionMessage;
import haveno.network.p2p.PrefixedSealedAndSignedMessage;
import haveno.network.p2p.peers.getdata.messages.GetDataResponse;
import haveno.network.p2p.peers.getdata.messages.GetUpdatedDataRequest;
import haveno.network.p2p.peers.getdata.messages.PreliminaryGetDataRequest;
import haveno.network.p2p.peers.keepalive.messages.Ping;
import haveno.network.p2p.peers.keepalive.messages.Pong;
import haveno.network.p2p.peers.peerexchange.messages.GetPeersRequest;
import haveno.network.p2p.peers.peerexchange.messages.GetPeersResponse;
import haveno.network.p2p.storage.messages.AddDataMessage;
import haveno.network.p2p.storage.messages.AddPersistableNetworkPayloadMessage;
import haveno.network.p2p.storage.messages.RefreshOfferMessage;
import haveno.network.p2p.storage.messages.RemoveDataMessage;
import haveno.network.p2p.storage.messages.RemoveMailboxDataMessage;
import haveno.network.p2p.storage.payload.MailboxStoragePayload;
import haveno.network.p2p.storage.payload.ProtectedMailboxStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;

import haveno.common.proto.ProtobufferException;
import haveno.common.proto.ProtobufferRuntimeException;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.proto.network.NetworkPayload;
import haveno.common.proto.network.NetworkProtoResolver;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.time.Clock;

import lombok.extern.slf4j.Slf4j;

// TODO Use ProtobufferException instead of ProtobufferRuntimeException
@Slf4j
@Singleton
public class CoreNetworkProtoResolver extends CoreProtoResolver implements NetworkProtoResolver {
    @Inject
    public CoreNetworkProtoResolver(Clock clock) {
        this.clock = clock;
    }

    @Override
    public NetworkEnvelope fromProto(protobuf.NetworkEnvelope proto) throws ProtobufferException {
        if (proto != null) {
            final int messageVersion = proto.getMessageVersion();
            switch (proto.getMessageCase()) {
                case PRELIMINARY_GET_DATA_REQUEST:
                    return PreliminaryGetDataRequest.fromProto(proto.getPreliminaryGetDataRequest(), messageVersion);
                case GET_DATA_RESPONSE:
                    return GetDataResponse.fromProto(proto.getGetDataResponse(), this, messageVersion);
                case GET_UPDATED_DATA_REQUEST:
                    return GetUpdatedDataRequest.fromProto(proto.getGetUpdatedDataRequest(), messageVersion);

                case GET_PEERS_REQUEST:
                    return GetPeersRequest.fromProto(proto.getGetPeersRequest(), messageVersion);
                case GET_PEERS_RESPONSE:
                    return GetPeersResponse.fromProto(proto.getGetPeersResponse(), messageVersion);
                case PING:
                    return Ping.fromProto(proto.getPing(), messageVersion);
                case PONG:
                    return Pong.fromProto(proto.getPong(), messageVersion);

                case SIGN_OFFER_REQUEST:
                    return SignOfferRequest.fromProto(proto.getSignOfferRequest(), messageVersion);
                case SIGN_OFFER_RESPONSE:
                    return SignOfferResponse.fromProto(proto.getSignOfferResponse(), messageVersion);

                case OFFER_AVAILABILITY_REQUEST:
                    return OfferAvailabilityRequest.fromProto(proto.getOfferAvailabilityRequest(), this, messageVersion);
                case OFFER_AVAILABILITY_RESPONSE:
                    return OfferAvailabilityResponse.fromProto(proto.getOfferAvailabilityResponse(), messageVersion);
                case REFRESH_OFFER_MESSAGE:
                    return RefreshOfferMessage.fromProto(proto.getRefreshOfferMessage(), messageVersion);

                case ADD_DATA_MESSAGE:
                    return AddDataMessage.fromProto(proto.getAddDataMessage(), this, messageVersion);
                case REMOVE_DATA_MESSAGE:
                    return RemoveDataMessage.fromProto(proto.getRemoveDataMessage(), this, messageVersion);
                case REMOVE_MAILBOX_DATA_MESSAGE:
                    return RemoveMailboxDataMessage.fromProto(proto.getRemoveMailboxDataMessage(), this, messageVersion);

                case CLOSE_CONNECTION_MESSAGE:
                    return CloseConnectionMessage.fromProto(proto.getCloseConnectionMessage(), messageVersion);
                case PREFIXED_SEALED_AND_SIGNED_MESSAGE:
                    return PrefixedSealedAndSignedMessage.fromProto(proto.getPrefixedSealedAndSignedMessage(), messageVersion);

                // trade protocol messages
                case REFRESH_TRADE_STATE_REQUEST:
                    return RefreshTradeStateRequest.fromProto(proto.getRefreshTradeStateRequest(), messageVersion);
                case INIT_TRADE_REQUEST:
                  return InitTradeRequest.fromProto(proto.getInitTradeRequest(), this, messageVersion);
                case INIT_MULTISIG_REQUEST:
                  return InitMultisigRequest.fromProto(proto.getInitMultisigRequest(), this, messageVersion);
                case SIGN_CONTRACT_REQUEST:
                    return SignContractRequest.fromProto(proto.getSignContractRequest(), this, messageVersion);
                case SIGN_CONTRACT_RESPONSE:
                    return SignContractResponse.fromProto(proto.getSignContractResponse(), this, messageVersion);
                case DEPOSIT_REQUEST:
                    return DepositRequest.fromProto(proto.getDepositRequest(), this, messageVersion);
                case DEPOSIT_RESPONSE:
                    return DepositResponse.fromProto(proto.getDepositResponse(), this, messageVersion);
                case PAYMENT_ACCOUNT_PAYLOAD_REQUEST:
                    return PaymentAccountPayloadRequest.fromProto(proto.getPaymentAccountPayloadRequest(), this, messageVersion);
                case UPDATE_MULTISIG_REQUEST:
                  return UpdateMultisigRequest.fromProto(proto.getUpdateMultisigRequest(), this, messageVersion);
                case UPDATE_MULTISIG_RESPONSE:
                  return UpdateMultisigResponse.fromProto(proto.getUpdateMultisigResponse(), this, messageVersion);
                case INPUTS_FOR_DEPOSIT_TX_REQUEST:
                    return InputsForDepositTxRequest.fromProto(proto.getInputsForDepositTxRequest(), this, messageVersion);
                case INPUTS_FOR_DEPOSIT_TX_RESPONSE:
                    return InputsForDepositTxResponse.fromProto(proto.getInputsForDepositTxResponse(), this, messageVersion);
                case DEPOSIT_TX_MESSAGE:
                    return DepositTxMessage.fromProto(proto.getDepositTxMessage(), messageVersion);
                case DELAYED_PAYOUT_TX_SIGNATURE_REQUEST:
                    return DelayedPayoutTxSignatureRequest.fromProto(proto.getDelayedPayoutTxSignatureRequest(), messageVersion);
                case DELAYED_PAYOUT_TX_SIGNATURE_RESPONSE:
                    return DelayedPayoutTxSignatureResponse.fromProto(proto.getDelayedPayoutTxSignatureResponse(), messageVersion);
                case DEPOSIT_TX_AND_DELAYED_PAYOUT_TX_MESSAGE:
                    return DepositTxAndDelayedPayoutTxMessage.fromProto(proto.getDepositTxAndDelayedPayoutTxMessage(), messageVersion);

                case COUNTER_CURRENCY_TRANSFER_STARTED_MESSAGE:
                    return CounterCurrencyTransferStartedMessage.fromProto(proto.getCounterCurrencyTransferStartedMessage(), messageVersion);

                case PAYOUT_TX_PUBLISHED_MESSAGE:
                    return PayoutTxPublishedMessage.fromProto(proto.getPayoutTxPublishedMessage(), messageVersion);
                case PEER_PUBLISHED_DELAYED_PAYOUT_TX_MESSAGE:
                    return PeerPublishedDelayedPayoutTxMessage.fromProto(proto.getPeerPublishedDelayedPayoutTxMessage(), messageVersion);
                case TRADER_SIGNED_WITNESS_MESSAGE:
                    return TraderSignedWitnessMessage.fromProto(proto.getTraderSignedWitnessMessage(), messageVersion);

                case MEDIATED_PAYOUT_TX_SIGNATURE_MESSAGE:
                    return MediatedPayoutTxSignatureMessage.fromProto(proto.getMediatedPayoutTxSignatureMessage(), messageVersion);
                case MEDIATED_PAYOUT_TX_PUBLISHED_MESSAGE:
                    return MediatedPayoutTxPublishedMessage.fromProto(proto.getMediatedPayoutTxPublishedMessage(), messageVersion);

                case OPEN_NEW_DISPUTE_MESSAGE:
                    return OpenNewDisputeMessage.fromProto(proto.getOpenNewDisputeMessage(), this, messageVersion);
                case PEER_OPENED_DISPUTE_MESSAGE:
                    return PeerOpenedDisputeMessage.fromProto(proto.getPeerOpenedDisputeMessage(), this, messageVersion);
                case CHAT_MESSAGE:
                    return ChatMessage.fromProto(proto.getChatMessage(), messageVersion);
                case DISPUTE_RESULT_MESSAGE:
                    return DisputeResultMessage.fromProto(proto.getDisputeResultMessage(), messageVersion);
                case PEER_PUBLISHED_DISPUTE_PAYOUT_TX_MESSAGE:
                    return PeerPublishedDisputePayoutTxMessage.fromProto(proto.getPeerPublishedDisputePayoutTxMessage(), messageVersion);
                case ARBITRATOR_PAYOUT_TX_REQUEST:
                    return ArbitratorPayoutTxRequest.fromProto(proto.getArbitratorPayoutTxRequest(), this, messageVersion);
                case ARBITRATOR_PAYOUT_TX_RESPONSE:
                  return ArbitratorPayoutTxResponse.fromProto(proto.getArbitratorPayoutTxResponse(), this, messageVersion);

                case PRIVATE_NOTIFICATION_MESSAGE:
                    return PrivateNotificationMessage.fromProto(proto.getPrivateNotificationMessage(), messageVersion);

                case ADD_PERSISTABLE_NETWORK_PAYLOAD_MESSAGE:
                    return AddPersistableNetworkPayloadMessage.fromProto(proto.getAddPersistableNetworkPayloadMessage(), this, messageVersion);
                case ACK_MESSAGE:
                    return AckMessage.fromProto(proto.getAckMessage(), messageVersion);



                case BUNDLE_OF_ENVELOPES:
                    return BundleOfEnvelopes.fromProto(proto.getBundleOfEnvelopes(), this, messageVersion);

                case GET_INVENTORY_REQUEST:
                    return GetInventoryRequest.fromProto(proto.getGetInventoryRequest(), messageVersion);
                case GET_INVENTORY_RESPONSE:
                    return GetInventoryResponse.fromProto(proto.getGetInventoryResponse(), messageVersion);

                default:
                    throw new ProtobufferException("Unknown proto message case (PB.NetworkEnvelope). messageCase=" +
                            proto.getMessageCase() + "; proto raw data=" + proto.toString());
            }
        } else {
            log.error("PersistableEnvelope.fromProto: PB.NetworkEnvelope is null");
            throw new ProtobufferException("PB.NetworkEnvelope is null");
        }
    }

    @Override
    public NetworkPayload fromProto(protobuf.StorageEntryWrapper proto) {
        if (proto != null) {
            switch (proto.getMessageCase()) {
                case PROTECTED_MAILBOX_STORAGE_ENTRY:
                    return ProtectedMailboxStorageEntry.fromProto(proto.getProtectedMailboxStorageEntry(), this);
                case PROTECTED_STORAGE_ENTRY:
                    return ProtectedStorageEntry.fromProto(proto.getProtectedStorageEntry(), this);
                default:
                    throw new ProtobufferRuntimeException("Unknown proto message case(PB.StorageEntryWrapper). " +
                            "messageCase=" + proto.getMessageCase() + "; proto raw data=" + proto.toString());
            }
        } else {
            log.error("PersistableEnvelope.fromProto: PB.StorageEntryWrapper is null");
            throw new ProtobufferRuntimeException("PB.StorageEntryWrapper is null");
        }
    }

    @Override
    public NetworkPayload fromProto(protobuf.StoragePayload proto) {
        if (proto != null) {
            switch (proto.getMessageCase()) {
                case ALERT:
                    return Alert.fromProto(proto.getAlert());
                case ARBITRATOR:
                    return Arbitrator.fromProto(proto.getArbitrator());
                case MEDIATOR:
                    return Mediator.fromProto(proto.getMediator());
                case REFUND_AGENT:
                    return RefundAgent.fromProto(proto.getRefundAgent());
                case FILTER:
                    return Filter.fromProto(proto.getFilter());
                case MAILBOX_STORAGE_PAYLOAD:
                    return MailboxStoragePayload.fromProto(proto.getMailboxStoragePayload());
                case OFFER_PAYLOAD:
                    return OfferPayload.fromProto(proto.getOfferPayload());
                default:
                    throw new ProtobufferRuntimeException("Unknown proto message case (PB.StoragePayload). messageCase="
                            + proto.getMessageCase() + "; proto raw data=" + proto.toString());
            }
        } else {
            log.error("PersistableEnvelope.fromProto: PB.StoragePayload is null");
            throw new ProtobufferRuntimeException("PB.StoragePayload is null");
        }
    }
}
