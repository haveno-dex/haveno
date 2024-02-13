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

package haveno.core.api.model;

import haveno.common.Payload;
import haveno.core.api.model.builder.TradeInfoV1Builder;
import haveno.core.trade.Contract;
import haveno.core.trade.Trade;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.function.Function;

import static haveno.core.api.model.OfferInfo.toOfferInfo;
import static haveno.core.util.PriceUtil.reformatMarketPrice;
import static haveno.core.util.VolumeUtil.formatVolume;
import static java.util.Objects.requireNonNull;

@EqualsAndHashCode
@Getter
public class TradeInfo implements Payload {

    // The client cannot see haveno.core.trade.Trade or its fromProto method.  We use the
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

    // Haveno v1 trade protocol fields (some are in common with the BSQ Swap protocol).
    private final OfferInfo offer;
    private final String tradeId;
    private final String shortId;
    private final long date;
    private final String role;
    private final long takerFee;
    private final String makerDepositTxId;
    private final String takerDepositTxId;
    private final String payoutTxId;
    private final long amount;
    private final long buyerSecurityDeposit;
    private final long sellerSecurityDeposit;
    private final long buyerDepositTxFee;
    private final long sellerDepositTxFee;
    private final long buyerPayoutTxFee;
    private final long sellerPayoutTxFee;
    private final long buyerPayoutAmount;
    private final long sellerPayoutAmount;
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
        this.takerFee = builder.getTakerFee();
        this.makerDepositTxId = builder.getMakerDepositTxId();
        this.takerDepositTxId = builder.getTakerDepositTxId();
        this.payoutTxId = builder.getPayoutTxId();
        this.amount = builder.getAmount();
        this.buyerSecurityDeposit = builder.getBuyerSecurityDeposit();
        this.sellerSecurityDeposit = builder.getSellerSecurityDeposit();
        this.buyerDepositTxFee = builder.getBuyerDepositTxFee();
        this.sellerDepositTxFee = builder.getSellerDepositTxFee();
        this.buyerPayoutTxFee = builder.getBuyerPayoutTxFee();
        this.sellerPayoutTxFee = builder.getSellerPayoutTxFee();
        this.buyerPayoutAmount = builder.getBuyerPayoutAmount();
        this.sellerPayoutAmount = builder.getSellerPayoutAmount();
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
                    contract.getTakerPayoutAddressString());
        } else {
            contractInfo = ContractInfo.emptyContract.get();
        }

        return new TradeInfoV1Builder()
                .withTradeId(trade.getId())
                .withShortId(trade.getShortId())
                .withDate(trade.getDate().getTime())
                .withRole(role == null ? "" : role)
                .withTakerFee(trade.getTakerFee().longValueExact())
                .withMakerDepositTxId(trade.getMaker().getDepositTxHash())
                .withTakerDepositTxId(trade.getTaker().getDepositTxHash())
                .withPayoutTxId(trade.getPayoutTxId())
                .withAmount(trade.getAmount().longValueExact())
                .withBuyerSecurityDeposit(trade.getBuyer().getSecurityDeposit() == null ? -1 : trade.getBuyer().getSecurityDeposit().longValueExact())
                .withSellerSecurityDeposit(trade.getSeller().getSecurityDeposit() == null ? -1 : trade.getSeller().getSecurityDeposit().longValueExact())
                .withBuyerDepositTxFee(trade.getBuyer().getDepositTxFee() == null ? -1 : trade.getBuyer().getDepositTxFee().longValueExact())
                .withSellerDepositTxFee(trade.getSeller().getDepositTxFee() == null ? -1 : trade.getSeller().getDepositTxFee().longValueExact())
                .withBuyerPayoutTxFee(trade.getBuyer().getPayoutTxFee() == null ? -1 : trade.getBuyer().getPayoutTxFee().longValueExact())
                .withSellerPayoutTxFee(trade.getSeller().getPayoutTxFee() == null ? -1 : trade.getSeller().getPayoutTxFee().longValueExact())
                .withBuyerPayoutAmount(trade.getBuyer().getPayoutAmount() == null ? -1 : trade.getBuyer().getPayoutAmount().longValueExact())
                .withSellerPayoutAmount(trade.getSeller().getPayoutAmount() == null ? -1 : trade.getSeller().getPayoutAmount().longValueExact())
                .withTotalTxFee(trade.getTotalTxFee().longValueExact())
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
    public haveno.proto.grpc.TradeInfo toProtoMessage() {
        return haveno.proto.grpc.TradeInfo.newBuilder()
                .setOffer(offer.toProtoMessage())
                .setTradeId(tradeId)
                .setShortId(shortId)
                .setDate(date)
                .setRole(role)
                .setTakerFee(takerFee)
                .setMakerDepositTxId(makerDepositTxId == null ? "" : makerDepositTxId)
                .setTakerDepositTxId(takerDepositTxId == null ? "" : takerDepositTxId)
                .setPayoutTxId(payoutTxId == null ? "" : payoutTxId)
                .setAmount(amount)
                .setBuyerSecurityDeposit(buyerSecurityDeposit)
                .setSellerSecurityDeposit(sellerSecurityDeposit)
                .setBuyerDepositTxFee(buyerDepositTxFee)
                .setSellerDepositTxFee(sellerDepositTxFee)
                .setBuyerPayoutTxFee(buyerPayoutTxFee)
                .setSellerPayoutTxFee(sellerPayoutTxFee)
                .setBuyerPayoutAmount(buyerPayoutAmount)
                .setSellerPayoutAmount(sellerPayoutAmount)
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

    public static TradeInfo fromProto(haveno.proto.grpc.TradeInfo proto) {
        return new TradeInfoV1Builder()
                .withOffer(OfferInfo.fromProto(proto.getOffer()))
                .withTradeId(proto.getTradeId())
                .withShortId(proto.getShortId())
                .withDate(proto.getDate())
                .withRole(proto.getRole())
                .withTakerFee(proto.getTakerFee())
                .withMakerDepositTxId(proto.getMakerDepositTxId())
                .withTakerDepositTxId(proto.getTakerDepositTxId())
                .withPayoutTxId(proto.getPayoutTxId())
                .withAmount(proto.getAmount())
                .withBuyerSecurityDeposit(proto.getBuyerSecurityDeposit())
                .withSellerSecurityDeposit(proto.getSellerSecurityDeposit())
                .withBuyerDepositTxFee(proto.getBuyerDepositTxFee())
                .withSellerDepositTxFee(proto.getSellerDepositTxFee())
                .withBuyerPayoutTxFee(proto.getBuyerPayoutTxFee())
                .withSellerPayoutTxFee(proto.getSellerPayoutTxFee())
                .withBuyerPayoutAmount(proto.getBuyerPayoutAmount())
                .withSellerPayoutAmount(proto.getSellerPayoutAmount())
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
                ", takerFee='" + takerFee + '\'' + "\n" +
                ", makerDepositTxId='" + makerDepositTxId + '\'' + "\n" +
                ", takerDepositTxId='" + takerDepositTxId + '\'' + "\n" +
                ", payoutTxId='" + payoutTxId + '\'' + "\n" +
                ", amount='" + amount + '\'' + "\n" +
                ", buyerSecurityDeposit='" + buyerSecurityDeposit + '\'' + "\n" +
                ", sellerSecurityDeposit='" + sellerSecurityDeposit + '\'' + "\n" +
                ", buyerDepositTxFee='" + buyerDepositTxFee + '\'' + "\n" +
                ", sellerDepositTxFee='" + sellerDepositTxFee + '\'' + "\n" +
                ", buyerPayoutTxFee='" + buyerPayoutTxFee + '\'' + "\n" +
                ", sellerPayoutTxFee='" + sellerPayoutTxFee + '\'' + "\n" +
                ", buyerPayoutAmount='" + buyerPayoutAmount + '\'' + "\n" +
                ", sellerPayoutAmount='" + sellerPayoutAmount + '\'' + "\n" +
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
