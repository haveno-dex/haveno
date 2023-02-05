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

package bisq.core.api.model;

import bisq.core.api.model.builder.TradeInfoV1Builder;
import bisq.core.trade.Trade;
import bisq.core.trade.Contract;

import bisq.common.Payload;

import java.util.function.Function;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import static bisq.core.api.model.OfferInfo.toOfferInfo;
import static bisq.core.util.PriceUtil.reformatMarketPrice;
import static bisq.core.util.VolumeUtil.formatVolume;
import static java.util.Objects.requireNonNull;

@EqualsAndHashCode
@Getter
public class TradeInfo implements Payload {

    // The client cannot see bisq.core.trade.Trade or its fromProto method.  We use the
    // lighter weight TradeInfo proto wrapper instead, containing just enough fields to
    // view and interact with trades.

    private static final Function<Trade, String> toPeerNodeAddress = (trade) ->
            trade.getTradePeerNodeAddress() == null
                    ? ""
                    : trade.getTradePeerNodeAddress().getFullAddress();

    private static final Function<Trade, String> toArbitratorNodeAddress = (trade) ->
            trade.getArbitratorNodeAddress() == null
                    ? ""
                    : trade.getArbitratorNodeAddress().getFullAddress();

    private static final Function<Trade, String> toRoundedVolume = (trade) ->
            trade.getVolume() == null
                    ? ""
                    : formatVolume(requireNonNull(trade.getVolume()));

    private static final Function<Trade, String> toPreciseTradePrice = (trade) ->
            reformatMarketPrice(requireNonNull(trade.getPrice()).toPlainString(),
                    trade.getOffer().getCurrencyCode());

    // Bisq v1 trade protocol fields (some are in common with the BSQ Swap protocol).
    private final OfferInfo offer;
    private final String tradeId;
    private final String shortId;
    private final long date;
    private final String role;
    private final long txFeeAsLong;
    private final long takerFeeAsLong;
    private final String makerDepositTxId;
    private final String takerDepositTxId;
    private final String payoutTxId;
    private final long amountAsLong;
    private final long buyerSecurityDeposit;
    private final long sellerSecurityDeposit;
    private final String price;
    private final String volume;
    private final String arbitratorNodeAddress;
    private final String tradePeerNodeAddress;
    private final String state;
    private final String phase;
    private final String periodState;
    private final String payoutState;
    private final String disputeState;
    private final boolean isDepositsPublished;
    private final boolean isDepositsConfirmed;
    private final boolean isDepositsUnlocked;
    private final boolean isPaymentSent;
    private final boolean isPaymentReceived;
    private final boolean isPayoutPublished;
    private final boolean isPayoutConfirmed;
    private final boolean isPayoutUnlocked;
    private final boolean isCompleted;
    private final String contractAsJson;
    private final ContractInfo contract;

    public TradeInfo(TradeInfoV1Builder builder) {
        this.offer = builder.getOffer();
        this.tradeId = builder.getTradeId();
        this.shortId = builder.getShortId();
        this.date = builder.getDate();
        this.role = builder.getRole();
        this.txFeeAsLong = builder.getTxFeeAsLong();
        this.takerFeeAsLong = builder.getTakerFeeAsLong();
        this.makerDepositTxId = builder.getMakerDepositTxId();
        this.takerDepositTxId = builder.getTakerDepositTxId();
        this.payoutTxId = builder.getPayoutTxId();
        this.amountAsLong = builder.getAmountAsLong();
        this.buyerSecurityDeposit = builder.getBuyerSecurityDeposit();
        this.sellerSecurityDeposit = builder.getSellerSecurityDeposit();
        this.price = builder.getPrice();
        this.volume = builder.getVolume();
        this.arbitratorNodeAddress = builder.getArbitratorNodeAddress();
        this.tradePeerNodeAddress = builder.getTradePeerNodeAddress();
        this.state = builder.getState();
        this.phase = builder.getPhase();
        this.periodState = builder.getPeriodState();
        this.payoutState = builder.getPayoutState();
        this.disputeState = builder.getDisputeState();
        this.isDepositsPublished = builder.isDepositsPublished();
        this.isDepositsConfirmed = builder.isDepositsConfirmed();
        this.isDepositsUnlocked = builder.isDepositsUnlocked();
        this.isPaymentSent = builder.isPaymentSent();
        this.isPaymentReceived = builder.isPaymentReceived();
        this.isPayoutPublished = builder.isPayoutPublished();
        this.isPayoutConfirmed = builder.isPayoutConfirmed();
        this.isPayoutUnlocked = builder.isPayoutUnlocked();
        this.isCompleted = builder.isCompleted();
        this.contractAsJson = builder.getContractAsJson();
        this.contract = builder.getContract();
    }

    public static TradeInfo toTradeInfo(Trade trade) {
        return toTradeInfo(trade, null);
    }

    public static TradeInfo toTradeInfo(Trade trade, String role) {
        ContractInfo contractInfo;
        if (trade.getContract() != null) {
            Contract contract = trade.getContract();
            contractInfo = new ContractInfo(contract.getBuyerPayoutAddressString(),
                    contract.getSellerPayoutAddressString(),
                    contract.getArbitratorNodeAddress().getFullAddress(),
                    contract.isBuyerMakerAndSellerTaker(),
                    contract.getMakerAccountId(),
                    contract.getTakerAccountId(),
                    trade.getMaker().getPaymentAccountPayload(),
                    trade.getTaker().getPaymentAccountPayload(),
                    contract.getMakerPayoutAddressString(),
                    contract.getTakerPayoutAddressString(),
                    contract.getLockTime());
        } else {
            contractInfo = ContractInfo.emptyContract.get();
        }

        return new TradeInfoV1Builder()
                .withTradeId(trade.getId())
                .withShortId(trade.getShortId())
                .withDate(trade.getDate().getTime())
                .withRole(role == null ? "" : role)
                .withTxFeeAsLong(trade.getTxFeeAsLong())
                .withTakerFeeAsLong(trade.getTakerFeeAsLong())
                .withMakerDepositTxId(trade.getMaker().getDepositTxHash())
                .withTakerDepositTxId(trade.getTaker().getDepositTxHash())
                .withPayoutTxId(trade.getPayoutTxId())
                .withAmountAsLong(trade.getAmountAsLong())
                .withBuyerSecurityDeposit(trade.getBuyerSecurityDeposit() == null ? -1 : trade.getBuyerSecurityDeposit().value)
                .withSellerSecurityDeposit(trade.getSellerSecurityDeposit() == null ? -1 : trade.getSellerSecurityDeposit().value)
                .withPrice(toPreciseTradePrice.apply(trade))
                .withVolume(toRoundedVolume.apply(trade))
                .withArbitratorNodeAddress(toArbitratorNodeAddress.apply(trade))
                .withTradePeerNodeAddress(toPeerNodeAddress.apply(trade))
                .withState(trade.getState().name())
                .withPhase(trade.getPhase().name())
                .withPeriodState(trade.getPeriodState().name())
                .withPayoutState(trade.getPayoutState().name())
                .withDisputeState(trade.getDisputeState().name())
                .withIsDepositsPublished(trade.isDepositsPublished())
                .withIsDepositsConfirmed(trade.isDepositsConfirmed())
                .withIsDepositsUnlocked(trade.isDepositsUnlocked())
                .withIsPaymentSent(trade.isPaymentSent())
                .withIsPaymentReceived(trade.isPaymentReceived())
                .withIsPayoutPublished(trade.isPayoutPublished())
                .withIsPayoutConfirmed(trade.isPayoutConfirmed())
                .withIsPayoutUnlocked(trade.isPayoutUnlocked())
                .withIsCompleted(trade.isCompleted())
                .withContractAsJson(trade.getContractAsJson())
                .withContract(contractInfo)
                .withOffer(toOfferInfo(trade.getOffer()))
                .build();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public bisq.proto.grpc.TradeInfo toProtoMessage() {
        return bisq.proto.grpc.TradeInfo.newBuilder()
                .setOffer(offer.toProtoMessage())
                .setTradeId(tradeId)
                .setShortId(shortId)
                .setDate(date)
                .setRole(role)
                .setTxFeeAsLong(txFeeAsLong)
                .setTakerFeeAsLong(takerFeeAsLong)
                .setMakerDepositTxId(makerDepositTxId == null ? "" : makerDepositTxId)
                .setTakerDepositTxId(takerDepositTxId == null ? "" : takerDepositTxId)
                .setPayoutTxId(payoutTxId == null ? "" : payoutTxId)
                .setAmountAsLong(amountAsLong)
                .setBuyerSecurityDeposit(buyerSecurityDeposit)
                .setSellerSecurityDeposit(sellerSecurityDeposit)
                .setPrice(price)
                .setTradeVolume(volume)
                .setArbitratorNodeAddress(arbitratorNodeAddress)
                .setTradePeerNodeAddress(tradePeerNodeAddress)
                .setState(state)
                .setPhase(phase)
                .setPeriodState(periodState)
                .setPayoutState(payoutState)
                .setDisputeState(disputeState)
                .setIsDepositsPublished(isDepositsPublished)
                .setIsDepositsConfirmed(isDepositsConfirmed)
                .setIsDepositsUnlocked(isDepositsUnlocked)
                .setIsPaymentSent(isPaymentSent)
                .setIsPaymentReceived(isPaymentReceived)
                .setIsCompleted(isCompleted)
                .setIsPayoutPublished(isPayoutPublished)
                .setIsPayoutConfirmed(isPayoutConfirmed)
                .setIsPayoutUnlocked(isPayoutUnlocked)
                .setContractAsJson(contractAsJson == null ? "" : contractAsJson)
                .setContract(contract.toProtoMessage())
                .build();
    }

    public static TradeInfo fromProto(bisq.proto.grpc.TradeInfo proto) {
        return new TradeInfoV1Builder()
                .withOffer(OfferInfo.fromProto(proto.getOffer()))
                .withTradeId(proto.getTradeId())
                .withShortId(proto.getShortId())
                .withDate(proto.getDate())
                .withRole(proto.getRole())
                .withTxFeeAsLong(proto.getTxFeeAsLong())
                .withTakerFeeAsLong(proto.getTakerFeeAsLong())
                .withMakerDepositTxId(proto.getMakerDepositTxId())
                .withTakerDepositTxId(proto.getTakerDepositTxId())
                .withPayoutTxId(proto.getPayoutTxId())
                .withAmountAsLong(proto.getAmountAsLong())
                .withBuyerSecurityDeposit(proto.getBuyerSecurityDeposit())
                .withSellerSecurityDeposit(proto.getSellerSecurityDeposit())
                .withPrice(proto.getPrice())
                .withVolume(proto.getTradeVolume())
                .withPeriodState(proto.getPeriodState())
                .withPayoutState(proto.getPayoutState())
                .withDisputeState(proto.getDisputeState())
                .withState(proto.getState())
                .withPhase(proto.getPhase())
                .withArbitratorNodeAddress(proto.getArbitratorNodeAddress())
                .withTradePeerNodeAddress(proto.getTradePeerNodeAddress())
                .withIsDepositsPublished(proto.getIsDepositsPublished())
                .withIsDepositsConfirmed(proto.getIsDepositsConfirmed())
                .withIsDepositsUnlocked(proto.getIsDepositsUnlocked())
                .withIsPaymentSent(proto.getIsPaymentSent())
                .withIsPaymentReceived(proto.getIsPaymentReceived())
                .withIsCompleted(proto.getIsCompleted())
                .withIsPayoutPublished(proto.getIsPayoutPublished())
                .withIsPayoutConfirmed(proto.getIsPayoutConfirmed())
                .withIsPayoutUnlocked(proto.getIsPayoutUnlocked())
                .withContractAsJson(proto.getContractAsJson())
                .withContract((ContractInfo.fromProto(proto.getContract())))
                .build();
    }

    @Override
    public String toString() {
        return "TradeInfo{" +
                "  tradeId='" + tradeId + '\'' + "\n" +
                ", shortId='" + shortId + '\'' + "\n" +
                ", date='" + date + '\'' + "\n" +
                ", role='" + role + '\'' + "\n" +
                ", txFeeAsLong='" + txFeeAsLong + '\'' + "\n" +
                ", takerFeeAsLong='" + takerFeeAsLong + '\'' + "\n" +
                ", makerDepositTxId='" + makerDepositTxId + '\'' + "\n" +
                ", takerDepositTxId='" + takerDepositTxId + '\'' + "\n" +
                ", payoutTxId='" + payoutTxId + '\'' + "\n" +
                ", amountAsLong='" + amountAsLong + '\'' + "\n" +
                ", buyerSecurityDeposit='" + buyerSecurityDeposit + '\'' + "\n" +
                ", sellerSecurityDeposit='" + sellerSecurityDeposit + '\'' + "\n" +
                ", price='" + price + '\'' + "\n" +
                ", arbitratorNodeAddress='" + arbitratorNodeAddress + '\'' + "\n" +
                ", tradePeerNodeAddress='" + tradePeerNodeAddress + '\'' + "\n" +
                ", state='" + state + '\'' + "\n" +
                ", phase='" + phase + '\'' + "\n" +
                ", periodState='" + periodState + '\'' + "\n" +
                ", payoutState='" + payoutState + '\'' + "\n" +
                ", disputeState='" + disputeState + '\'' + "\n" +
                ", isDepositsPublished=" + isDepositsPublished + "\n" +
                ", isDepositsConfirmed=" + isDepositsConfirmed + "\n" +
                ", isDepositsUnlocked=" + isDepositsUnlocked + "\n" +
                ", isPaymentSent=" + isPaymentSent + "\n" +
                ", isPaymentReceived=" + isPaymentReceived + "\n" +
                ", isPayoutPublished=" + isPayoutPublished + "\n" +
                ", isPayoutConfirmed=" + isPayoutConfirmed + "\n" +
                ", isPayoutUnlocked=" + isPayoutUnlocked + "\n" +
                ", isCompleted=" + isCompleted + "\n" +
                ", offer=" + offer + "\n" +
                ", contractAsJson=" + contractAsJson + "\n" +
                ", contract=" + contract + "\n" +
                '}';
    }
}
