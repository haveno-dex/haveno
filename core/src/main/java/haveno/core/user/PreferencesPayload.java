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

package haveno.core.user;

import com.google.common.collect.Maps;
import com.google.protobuf.Message;

import haveno.common.proto.ProtoUtil;
import haveno.common.proto.persistable.PersistableEnvelope;
import haveno.core.locale.Country;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.PaymentAccount;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.xmr.MoneroNodeSettings;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static haveno.core.xmr.wallet.Restrictions.getDefaultBuyerSecurityDepositAsPercent;

@Slf4j
@Data
@AllArgsConstructor
public final class PreferencesPayload implements PersistableEnvelope {
    private String userLanguage;
    private Country userCountry;
    private List<TraditionalCurrency> traditionalCurrencies = new ArrayList<>();
    private List<CryptoCurrency> cryptoCurrencies = new ArrayList<>();
    private BlockChainExplorer blockChainExplorerMainNet;
    private BlockChainExplorer blockChainExplorerTestNet;
    @Nullable
    private String backupDirectory;
    private boolean autoSelectArbitrators = true;
    private Map<String, Boolean> dontShowAgainMap = new HashMap<>();
    private boolean tacAccepted;
    private boolean useTorForMonero = true;
    private boolean splitOfferOutput = false;
    private boolean showOwnOffersInOfferBook = true;
    @Nullable
    private TradeCurrency preferredTradeCurrency;
    private long withdrawalTxFeeInVbytes = 100;
    private boolean useCustomWithdrawalTxFee = false;
    private double maxPriceDistanceInPercent = 0.3;
    @Nullable
    private String offerBookChartScreenCurrencyCode;
    @Nullable
    private String tradeChartsScreenCurrencyCode;
    @Nullable
    private String buyScreenCurrencyCode;
    @Nullable
    private String sellScreenCurrencyCode;
    @Nullable
    private String buyScreenCryptoCurrencyCode;
    @Nullable
    private String sellScreenCryptoCurrencyCode;
    private int tradeStatisticsTickUnitIndex = 3;
    private boolean resyncSpvRequested;
    private boolean sortMarketCurrenciesNumerically = true;
    private boolean usePercentageBasedPrice = true;
    private Map<String, String> peerTagMap = new HashMap<>();
    // custom xmr nodes
    private String moneroNodes = "";
    private List<String> ignoreTradersList = new ArrayList<>();
    private String directoryChooserPath;

    private boolean useAnimations;
    private int cssTheme;
    @Nullable
    private PaymentAccount selectedPaymentAccountForCreateOffer;
    @Nullable
    private List<String> bridgeAddresses;
    private int bridgeOptionOrdinal;
    private int torTransportOrdinal;
    @Nullable
    private String customBridges;
    private int moneroNodesOptionOrdinal;
    @Nullable
    private String referralId;
    @Nullable
    private String phoneKeyAndToken;
    private boolean useSoundForMobileNotifications = true;
    private boolean useTradeNotifications = true;
    private boolean useMarketNotifications = true;
    private boolean usePriceNotifications = true;
    private boolean useStandbyMode = false;
    @Nullable
    private String rpcUser;
    @Nullable
    private String rpcPw;
    @Nullable
    private String takeOfferSelectedPaymentAccountId;
    private double buyerSecurityDepositAsPercent = getDefaultBuyerSecurityDepositAsPercent();
    private int ignoreDustThreshold = 600;
    private int clearDataAfterDays = Preferences.CLEAR_DATA_AFTER_DAYS_INITIAL;
    private double buyerSecurityDepositAsPercentForCrypto = getDefaultBuyerSecurityDepositAsPercent();
    private int blockNotifyPort;
    private boolean tacAcceptedV120;
    private double bsqAverageTrimThreshold = 0.05;

    // Added at 1.3.8
    private List<AutoConfirmSettings> autoConfirmSettingsList = new ArrayList<>();

    // Added in 1.5.5
    private boolean hideNonAccountPaymentMethods;
    private boolean showOffersMatchingMyAccounts;
    private boolean denyApiTaker;
    private boolean notifyOnPreRelease;

    private MoneroNodeSettings moneroNodeSettings;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    PreferencesPayload() {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public Message toProtoMessage() {
        protobuf.PreferencesPayload.Builder builder = protobuf.PreferencesPayload.newBuilder()
                .setUserLanguage(userLanguage)
                .setUserCountry((protobuf.Country) userCountry.toProtoMessage())
                .addAllTraditionalCurrencies(traditionalCurrencies.stream()
                        .map(traditionalCurrency -> ((protobuf.TradeCurrency) traditionalCurrency.toProtoMessage()))
                        .collect(Collectors.toList()))
                .addAllCryptoCurrencies(cryptoCurrencies.stream()
                        .map(cryptoCurrency -> ((protobuf.TradeCurrency) cryptoCurrency.toProtoMessage()))
                        .collect(Collectors.toList()))
                .setBlockChainExplorerMainNet((protobuf.BlockChainExplorer) blockChainExplorerMainNet.toProtoMessage())
                .setBlockChainExplorerTestNet((protobuf.BlockChainExplorer) blockChainExplorerTestNet.toProtoMessage())
                .setAutoSelectArbitrators(autoSelectArbitrators)
                .putAllDontShowAgainMap(dontShowAgainMap)
                .setTacAccepted(tacAccepted)
                .setUseTorForMonero(useTorForMonero)
                .setSplitOfferOutput(splitOfferOutput)
                .setShowOwnOffersInOfferBook(showOwnOffersInOfferBook)
                .setWithdrawalTxFeeInVbytes(withdrawalTxFeeInVbytes)
                .setUseCustomWithdrawalTxFee(useCustomWithdrawalTxFee)
                .setMaxPriceDistanceInPercent(maxPriceDistanceInPercent)
                .setTradeStatisticsTickUnitIndex(tradeStatisticsTickUnitIndex)
                .setResyncSpvRequested(resyncSpvRequested)
                .setSortMarketCurrenciesNumerically(sortMarketCurrenciesNumerically)
                .setUsePercentageBasedPrice(usePercentageBasedPrice)
                .putAllPeerTagMap(peerTagMap)
                .setMoneroNodes(moneroNodes)
                .addAllIgnoreTradersList(ignoreTradersList)
                .setDirectoryChooserPath(directoryChooserPath)
                .setUseAnimations(useAnimations)
                .setCssTheme(cssTheme)
                .setBridgeOptionOrdinal(bridgeOptionOrdinal)
                .setTorTransportOrdinal(torTransportOrdinal)
                .setMoneroNodesOptionOrdinal(moneroNodesOptionOrdinal)
                .setUseSoundForMobileNotifications(useSoundForMobileNotifications)
                .setUseTradeNotifications(useTradeNotifications)
                .setUseMarketNotifications(useMarketNotifications)
                .setUsePriceNotifications(usePriceNotifications)
                .setUseStandbyMode(useStandbyMode)
                .setBuyerSecurityDepositAsPercent(buyerSecurityDepositAsPercent)
                .setIgnoreDustThreshold(ignoreDustThreshold)
                .setClearDataAfterDays(clearDataAfterDays)
                .setBuyerSecurityDepositAsPercentForCrypto(buyerSecurityDepositAsPercentForCrypto)
                .setBlockNotifyPort(blockNotifyPort)
                .setTacAcceptedV120(tacAcceptedV120)
                .setBsqAverageTrimThreshold(bsqAverageTrimThreshold)
                .addAllAutoConfirmSettings(autoConfirmSettingsList.stream()
                        .map(autoConfirmSettings -> ((protobuf.AutoConfirmSettings) autoConfirmSettings.toProtoMessage()))
                        .collect(Collectors.toList()))
                .setHideNonAccountPaymentMethods(hideNonAccountPaymentMethods)
                .setShowOffersMatchingMyAccounts(showOffersMatchingMyAccounts)
                .setDenyApiTaker(denyApiTaker)
                .setNotifyOnPreRelease(notifyOnPreRelease);

        Optional.ofNullable(backupDirectory).ifPresent(builder::setBackupDirectory);
        Optional.ofNullable(preferredTradeCurrency).ifPresent(e -> builder.setPreferredTradeCurrency((protobuf.TradeCurrency) e.toProtoMessage()));
        Optional.ofNullable(offerBookChartScreenCurrencyCode).ifPresent(builder::setOfferBookChartScreenCurrencyCode);
        Optional.ofNullable(tradeChartsScreenCurrencyCode).ifPresent(builder::setTradeChartsScreenCurrencyCode);
        Optional.ofNullable(buyScreenCurrencyCode).ifPresent(builder::setBuyScreenCurrencyCode);
        Optional.ofNullable(sellScreenCurrencyCode).ifPresent(builder::setSellScreenCurrencyCode);
        Optional.ofNullable(buyScreenCryptoCurrencyCode).ifPresent(builder::setBuyScreenCryptoCurrencyCode);
        Optional.ofNullable(sellScreenCryptoCurrencyCode).ifPresent(builder::setSellScreenCryptoCurrencyCode);
        Optional.ofNullable(selectedPaymentAccountForCreateOffer).ifPresent(
                account -> builder.setSelectedPaymentAccountForCreateOffer(selectedPaymentAccountForCreateOffer.toProtoMessage()));
        Optional.ofNullable(bridgeAddresses).ifPresent(builder::addAllBridgeAddresses);
        Optional.ofNullable(customBridges).ifPresent(builder::setCustomBridges);
        Optional.ofNullable(referralId).ifPresent(builder::setReferralId);
        Optional.ofNullable(phoneKeyAndToken).ifPresent(builder::setPhoneKeyAndToken);
        Optional.ofNullable(rpcUser).ifPresent(builder::setRpcUser);
        Optional.ofNullable(rpcPw).ifPresent(builder::setRpcPw);
        Optional.ofNullable(takeOfferSelectedPaymentAccountId).ifPresent(builder::setTakeOfferSelectedPaymentAccountId);
        Optional.ofNullable(moneroNodeSettings).ifPresent(settings -> builder.setMoneroNodeSettings(settings.toProtoMessage()));
        return protobuf.PersistableEnvelope.newBuilder().setPreferencesPayload(builder).build();
    }

    public static PreferencesPayload fromProto(protobuf.PreferencesPayload proto, CoreProtoResolver coreProtoResolver) {
        final protobuf.Country userCountry = proto.getUserCountry();
        PaymentAccount paymentAccount = null;
        if (proto.hasSelectedPaymentAccountForCreateOffer() && proto.getSelectedPaymentAccountForCreateOffer().hasPaymentMethod())
            paymentAccount = PaymentAccount.fromProto(proto.getSelectedPaymentAccountForCreateOffer(), coreProtoResolver);

        return new PreferencesPayload(
                proto.getUserLanguage(),
                Country.fromProto(userCountry),
                proto.getTraditionalCurrenciesList().isEmpty() ? new ArrayList<>() :
                        new ArrayList<>(proto.getTraditionalCurrenciesList().stream()
                                .map(TraditionalCurrency::fromProto)
                                .collect(Collectors.toList())),
                proto.getCryptoCurrenciesList().isEmpty() ? new ArrayList<>() :
                        new ArrayList<>(proto.getCryptoCurrenciesList().stream()
                                .map(CryptoCurrency::fromProto)
                                .collect(Collectors.toList())),
                BlockChainExplorer.fromProto(proto.getBlockChainExplorerMainNet()),
                BlockChainExplorer.fromProto(proto.getBlockChainExplorerTestNet()),
                ProtoUtil.stringOrNullFromProto(proto.getBackupDirectory()),
                proto.getAutoSelectArbitrators(),
                Maps.newHashMap(proto.getDontShowAgainMapMap()),
                proto.getTacAccepted(),
                proto.getUseTorForMonero(),
                proto.getSplitOfferOutput(),
                proto.getShowOwnOffersInOfferBook(),
                proto.hasPreferredTradeCurrency() ? TradeCurrency.fromProto(proto.getPreferredTradeCurrency()) : null,
                proto.getWithdrawalTxFeeInVbytes(),
                proto.getUseCustomWithdrawalTxFee(),
                proto.getMaxPriceDistanceInPercent(),
                ProtoUtil.stringOrNullFromProto(proto.getOfferBookChartScreenCurrencyCode()),
                ProtoUtil.stringOrNullFromProto(proto.getTradeChartsScreenCurrencyCode()),
                ProtoUtil.stringOrNullFromProto(proto.getBuyScreenCurrencyCode()),
                ProtoUtil.stringOrNullFromProto(proto.getSellScreenCurrencyCode()),
                ProtoUtil.stringOrNullFromProto(proto.getBuyScreenCryptoCurrencyCode()),
                ProtoUtil.stringOrNullFromProto(proto.getSellScreenCryptoCurrencyCode()),
                proto.getTradeStatisticsTickUnitIndex(),
                proto.getResyncSpvRequested(),
                proto.getSortMarketCurrenciesNumerically(),
                proto.getUsePercentageBasedPrice(),
                Maps.newHashMap(proto.getPeerTagMapMap()),
                proto.getMoneroNodes(),
                proto.getIgnoreTradersListList(),
                proto.getDirectoryChooserPath(),
                proto.getUseAnimations(),
                proto.getCssTheme(),
                paymentAccount,
                proto.getBridgeAddressesList().isEmpty() ? null : new ArrayList<>(proto.getBridgeAddressesList()),
                proto.getBridgeOptionOrdinal(),
                proto.getTorTransportOrdinal(),
                ProtoUtil.stringOrNullFromProto(proto.getCustomBridges()),
                proto.getMoneroNodesOptionOrdinal(),
                proto.getReferralId().isEmpty() ? null : proto.getReferralId(),
                proto.getPhoneKeyAndToken().isEmpty() ? null : proto.getPhoneKeyAndToken(),
                proto.getUseSoundForMobileNotifications(),
                proto.getUseTradeNotifications(),
                proto.getUseMarketNotifications(),
                proto.getUsePriceNotifications(),
                proto.getUseStandbyMode(),
                proto.getRpcUser().isEmpty() ? null : proto.getRpcUser(),
                proto.getRpcPw().isEmpty() ? null : proto.getRpcPw(),
                proto.getTakeOfferSelectedPaymentAccountId().isEmpty() ? null : proto.getTakeOfferSelectedPaymentAccountId(),
                proto.getBuyerSecurityDepositAsPercent(),
                proto.getIgnoreDustThreshold(),
                proto.getClearDataAfterDays(),
                proto.getBuyerSecurityDepositAsPercentForCrypto(),
                proto.getBlockNotifyPort(),
                proto.getTacAcceptedV120(),
                proto.getBsqAverageTrimThreshold(),
                proto.getAutoConfirmSettingsList().isEmpty() ? new ArrayList<>() :
                        new ArrayList<>(proto.getAutoConfirmSettingsList().stream()
                                .map(AutoConfirmSettings::fromProto)
                                .collect(Collectors.toList())),
                proto.getHideNonAccountPaymentMethods(),
                proto.getShowOffersMatchingMyAccounts(),
                proto.getDenyApiTaker(),
                proto.getNotifyOnPreRelease(),
                MoneroNodeSettings.fromProto(proto.getMoneroNodeSettings())
        );
    }
}
