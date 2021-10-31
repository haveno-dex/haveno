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

package haveno.core.proto;

import haveno.core.account.sign.SignedWitness;
import haveno.core.account.witness.AccountAgeWitness;
import haveno.core.payment.payload.AdvancedCashAccountPayload;
import haveno.core.payment.payload.AliPayAccountPayload;
import haveno.core.payment.payload.AmazonGiftCardAccountPayload;
import haveno.core.payment.payload.AustraliaPayidPayload;
import haveno.core.payment.payload.CashAppAccountPayload;
import haveno.core.payment.payload.CashByMailAccountPayload;
import haveno.core.payment.payload.CashDepositAccountPayload;
import haveno.core.payment.payload.ChaseQuickPayAccountPayload;
import haveno.core.payment.payload.ClearXchangeAccountPayload;
import haveno.core.payment.payload.CryptoCurrencyAccountPayload;
import haveno.core.payment.payload.F2FAccountPayload;
import haveno.core.payment.payload.FasterPaymentsAccountPayload;
import haveno.core.payment.payload.HalCashAccountPayload;
import haveno.core.payment.payload.InstantCryptoCurrencyPayload;
import haveno.core.payment.payload.InteracETransferAccountPayload;
import haveno.core.payment.payload.JapanBankAccountPayload;
import haveno.core.payment.payload.MoneyBeamAccountPayload;
import haveno.core.payment.payload.MoneyGramAccountPayload;
import haveno.core.payment.payload.NationalBankAccountPayload;
import haveno.core.payment.payload.OKPayAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PerfectMoneyAccountPayload;
import haveno.core.payment.payload.PopmoneyAccountPayload;
import haveno.core.payment.payload.PromptPayAccountPayload;
import haveno.core.payment.payload.RevolutAccountPayload;
import haveno.core.payment.payload.SameBankAccountPayload;
import haveno.core.payment.payload.SepaAccountPayload;
import haveno.core.payment.payload.SepaInstantAccountPayload;
import haveno.core.payment.payload.SpecificBanksAccountPayload;
import haveno.core.payment.payload.SwishAccountPayload;
import haveno.core.payment.payload.TransferwiseAccountPayload;
import haveno.core.payment.payload.USPostalMoneyOrderAccountPayload;
import haveno.core.payment.payload.UpholdAccountPayload;
import haveno.core.payment.payload.VenmoAccountPayload;
import haveno.core.payment.payload.WeChatPayAccountPayload;
import haveno.core.payment.payload.WesternUnionAccountPayload;
import haveno.core.trade.statistics.TradeStatistics2;
import haveno.core.trade.statistics.TradeStatistics3;

import haveno.common.proto.ProtoResolver;
import haveno.common.proto.ProtobufferRuntimeException;
import haveno.common.proto.persistable.PersistablePayload;

import java.time.Clock;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CoreProtoResolver implements ProtoResolver {
    @Getter
    protected Clock clock;

    @Override
    public PaymentAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        if (proto != null) {
            final protobuf.PaymentAccountPayload.MessageCase messageCase = proto.getMessageCase();
            switch (messageCase) {
                case ALI_PAY_ACCOUNT_PAYLOAD:
                    return AliPayAccountPayload.fromProto(proto);
                case WE_CHAT_PAY_ACCOUNT_PAYLOAD:
                    return WeChatPayAccountPayload.fromProto(proto);
                case CHASE_QUICK_PAY_ACCOUNT_PAYLOAD:
                    return ChaseQuickPayAccountPayload.fromProto(proto);
                case CLEAR_XCHANGE_ACCOUNT_PAYLOAD:
                    return ClearXchangeAccountPayload.fromProto(proto);
                case COUNTRY_BASED_PAYMENT_ACCOUNT_PAYLOAD:
                    final protobuf.CountryBasedPaymentAccountPayload.MessageCase messageCaseCountry = proto.getCountryBasedPaymentAccountPayload().getMessageCase();
                    switch (messageCaseCountry) {
                        case BANK_ACCOUNT_PAYLOAD:
                            final protobuf.BankAccountPayload.MessageCase messageCaseBank = proto.getCountryBasedPaymentAccountPayload().getBankAccountPayload().getMessageCase();
                            switch (messageCaseBank) {
                                case NATIONAL_BANK_ACCOUNT_PAYLOAD:
                                    return NationalBankAccountPayload.fromProto(proto);
                                case SAME_BANK_ACCONT_PAYLOAD:
                                    return SameBankAccountPayload.fromProto(proto);
                                case SPECIFIC_BANKS_ACCOUNT_PAYLOAD:
                                    return SpecificBanksAccountPayload.fromProto(proto);
                                default:
                                    throw new ProtobufferRuntimeException("Unknown proto message case" +
                                            "(PB.PaymentAccountPayload.CountryBasedPaymentAccountPayload.BankAccountPayload). " +
                                            "messageCase=" + messageCaseBank);
                            }
                        case WESTERN_UNION_ACCOUNT_PAYLOAD:
                            return WesternUnionAccountPayload.fromProto(proto);
                        case CASH_DEPOSIT_ACCOUNT_PAYLOAD:
                            return CashDepositAccountPayload.fromProto(proto);
                        case SEPA_ACCOUNT_PAYLOAD:
                            return SepaAccountPayload.fromProto(proto);
                        case SEPA_INSTANT_ACCOUNT_PAYLOAD:
                            return SepaInstantAccountPayload.fromProto(proto);
                        case F2F_ACCOUNT_PAYLOAD:
                            return F2FAccountPayload.fromProto(proto);
                        default:
                            throw new ProtobufferRuntimeException("Unknown proto message case" +
                                    "(PB.PaymentAccountPayload.CountryBasedPaymentAccountPayload)." +
                                    " messageCase=" + messageCaseCountry);
                    }
                case CRYPTO_CURRENCY_ACCOUNT_PAYLOAD:
                    return CryptoCurrencyAccountPayload.fromProto(proto);
                case FASTER_PAYMENTS_ACCOUNT_PAYLOAD:
                    return FasterPaymentsAccountPayload.fromProto(proto);
                case INTERAC_E_TRANSFER_ACCOUNT_PAYLOAD:
                    return InteracETransferAccountPayload.fromProto(proto);
                case JAPAN_BANK_ACCOUNT_PAYLOAD:
                    return JapanBankAccountPayload.fromProto(proto);
                case AUSTRALIA_PAYID_PAYLOAD:
                    return AustraliaPayidPayload.fromProto(proto);
                case UPHOLD_ACCOUNT_PAYLOAD:
                    return UpholdAccountPayload.fromProto(proto);
                case MONEY_BEAM_ACCOUNT_PAYLOAD:
                    return MoneyBeamAccountPayload.fromProto(proto);
                case MONEY_GRAM_ACCOUNT_PAYLOAD:
                    return MoneyGramAccountPayload.fromProto(proto);
                case POPMONEY_ACCOUNT_PAYLOAD:
                    return PopmoneyAccountPayload.fromProto(proto);
                case REVOLUT_ACCOUNT_PAYLOAD:
                    return RevolutAccountPayload.fromProto(proto);
                case PERFECT_MONEY_ACCOUNT_PAYLOAD:
                    return PerfectMoneyAccountPayload.fromProto(proto);
                case SWISH_ACCOUNT_PAYLOAD:
                    return SwishAccountPayload.fromProto(proto);
                case HAL_CASH_ACCOUNT_PAYLOAD:
                    return HalCashAccountPayload.fromProto(proto);
                case U_S_POSTAL_MONEY_ORDER_ACCOUNT_PAYLOAD:
                    return USPostalMoneyOrderAccountPayload.fromProto(proto);
                case CASH_BY_MAIL_ACCOUNT_PAYLOAD:
                    return CashByMailAccountPayload.fromProto(proto);
                case PROMPT_PAY_ACCOUNT_PAYLOAD:
                    return PromptPayAccountPayload.fromProto(proto);
                case ADVANCED_CASH_ACCOUNT_PAYLOAD:
                    return AdvancedCashAccountPayload.fromProto(proto);
                case TRANSFERWISE_ACCOUNT_PAYLOAD:
                    return TransferwiseAccountPayload.fromProto(proto);
                case AMAZON_GIFT_CARD_ACCOUNT_PAYLOAD:
                    return AmazonGiftCardAccountPayload.fromProto(proto);
                case INSTANT_CRYPTO_CURRENCY_ACCOUNT_PAYLOAD:
                    return InstantCryptoCurrencyPayload.fromProto(proto);

                // Cannot be deleted as it would break old trade history entries
                case O_K_PAY_ACCOUNT_PAYLOAD:
                    return OKPayAccountPayload.fromProto(proto);
                case CASH_APP_ACCOUNT_PAYLOAD:
                    return CashAppAccountPayload.fromProto(proto);
                case VENMO_ACCOUNT_PAYLOAD:
                    return VenmoAccountPayload.fromProto(proto);

                default:
                    throw new ProtobufferRuntimeException("Unknown proto message case(PB.PaymentAccountPayload). messageCase=" + messageCase);
            }
        } else {
            log.error("PersistableEnvelope.fromProto: PB.PaymentAccountPayload is null");
            throw new ProtobufferRuntimeException("PB.PaymentAccountPayload is null");
        }
    }

    @Override
    public PersistablePayload fromProto(protobuf.PersistableNetworkPayload proto) {
        if (proto != null) {
            switch (proto.getMessageCase()) {
                case ACCOUNT_AGE_WITNESS:
                    return AccountAgeWitness.fromProto(proto.getAccountAgeWitness());
                case TRADE_STATISTICS2:
                    return TradeStatistics2.fromProto(proto.getTradeStatistics2());
                case SIGNED_WITNESS:
                    return SignedWitness.fromProto(proto.getSignedWitness());
                case TRADE_STATISTICS3:
                    return TradeStatistics3.fromProto(proto.getTradeStatistics3());
                default:
                    throw new ProtobufferRuntimeException("Unknown proto message case (PB.PersistableNetworkPayload). messageCase=" + proto.getMessageCase());
            }
        } else {
            log.error("PB.PersistableNetworkPayload is null");
            throw new ProtobufferRuntimeException("PB.PersistableNetworkPayload is null");
        }
    }
}
