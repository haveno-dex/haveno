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

package haveno.cli;

import haveno.proto.grpc.OfferInfo;

import io.grpc.StatusRuntimeException;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;

import java.util.Date;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import static haveno.cli.CurrencyFormat.formatMarketPrice;
import static haveno.cli.CurrencyFormat.formatTxFeeRateInfo;
import static haveno.cli.CurrencyFormat.toSatoshis;
import static haveno.cli.CurrencyFormat.toSecurityDepositAsPct;
import static haveno.cli.Method.*;
import static haveno.cli.TableFormat.*;
import static haveno.cli.opts.OptLabel.*;
import static java.lang.String.format;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;
import static java.util.Collections.singletonList;



import haveno.cli.opts.ArgumentList;
import haveno.cli.opts.CancelOfferOptionParser;
import haveno.cli.opts.CreateCryptoCurrencyPaymentAcctOptionParser;
import haveno.cli.opts.CreateOfferOptionParser;
import haveno.cli.opts.CreatePaymentAcctOptionParser;
import haveno.cli.opts.GetAddressBalanceOptionParser;
import haveno.cli.opts.GetBTCMarketPriceOptionParser;
import haveno.cli.opts.GetBalanceOptionParser;
import haveno.cli.opts.GetOfferOptionParser;
import haveno.cli.opts.GetOffersOptionParser;
import haveno.cli.opts.GetPaymentAcctFormOptionParser;
import haveno.cli.opts.GetTradeOptionParser;
import haveno.cli.opts.GetTransactionOptionParser;
import haveno.cli.opts.RegisterDisputeAgentOptionParser;
import haveno.cli.opts.RemoveWalletPasswordOptionParser;
import haveno.cli.opts.SendBtcOptionParser;
import haveno.cli.opts.SetTxFeeRateOptionParser;
import haveno.cli.opts.SetWalletPasswordOptionParser;
import haveno.cli.opts.SimpleMethodOptionParser;
import haveno.cli.opts.TakeOfferOptionParser;
import haveno.cli.opts.UnlockWalletOptionParser;
import haveno.cli.opts.WithdrawFundsOptionParser;

/**
 * A command-line client for the Haveno gRPC API.
 */
@Slf4j
public class CliMain {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            err.println("Error: " + t.getMessage());
            exit(1);
        }
    }

    public static void run(String[] args) {
        var parser = new OptionParser();

        var helpOpt = parser.accepts(OPT_HELP, "Print this help text")
                .forHelp();

        var hostOpt = parser.accepts(OPT_HOST, "rpc server hostname or ip")
                .withRequiredArg()
                .defaultsTo("localhost");

        var portOpt = parser.accepts(OPT_PORT, "rpc server port")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(9998);

        var passwordOpt = parser.accepts(OPT_PASSWORD, "rpc server password")
                .withRequiredArg();

        // Parse the CLI opts host, port, password, method name, and help.  The help opt
        // may indicate the user is asking for method level help, and will be excluded
        // from the parsed options if a method opt is present in String[] args.
        OptionSet options = parser.parse(new ArgumentList(args).getCLIArguments());
        @SuppressWarnings("unchecked")
        var nonOptionArgs = (List<String>) options.nonOptionArguments();

        // If neither the help opt nor a method name is present, print CLI level help
        // to stderr and throw an exception.
        if (!options.has(helpOpt) && nonOptionArgs.isEmpty()) {
            printHelp(parser, err);
            throw new IllegalArgumentException("no method specified");
        }

        // If the help opt is present, but not a method name, print CLI level help
        // to stdout.
        if (options.has(helpOpt) && nonOptionArgs.isEmpty()) {
            printHelp(parser, out);
            return;
        }

        var host = options.valueOf(hostOpt);
        var port = options.valueOf(portOpt);
        var password = options.valueOf(passwordOpt);
        if (password == null)
            throw new IllegalArgumentException("missing required 'password' option");

        var methodName = nonOptionArgs.get(0);
        Method method;
        try {
            method = getMethodFromCmd(methodName);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(format("'%s' is not a supported method", methodName));
        }

        GrpcClient client = new GrpcClient(host, port, password);
        try {
            switch (method) {
                case getversion: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var version = client.getVersion();
                    out.println(version);
                    return;
                }
                case getbalance: {
                    var opts = new GetBalanceOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var currencyCode = opts.getCurrencyCode();
                    var balances = client.getBalances(currencyCode);
                    switch (currencyCode.toUpperCase()) {
                        case "BTC":
                            out.println(formatBtcBalanceInfoTbl(balances.getBtc()));
                            break;
                        case "XMR":
                          out.println(formatXmrBalanceInfoTbl(balances.getXmr()));
                          break;
                        case "":
                        default:
                            out.println(formatBalancesTbls(balances));
                            break;
                    }
                    return;
                }
                case getaddressbalance: {
                    var opts = new GetAddressBalanceOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var address = opts.getAddress();
                    var addressBalance = client.getAddressBalance(address);
                    out.println(formatAddressBalanceTbl(singletonList(addressBalance)));
                    return;
                }
                case getbtcprice: {
                    var opts = new GetBTCMarketPriceOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var currencyCode = opts.getCurrencyCode();
                    var price = client.getBtcPrice(currencyCode);
                    out.println(formatMarketPrice(price));
                    return;
                }
                case getfundingaddresses: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var fundingAddresses = client.getFundingAddresses();
                    out.println(formatAddressBalanceTbl(fundingAddresses));
                    return;
                }
                case sendbtc: {
                    var opts = new SendBtcOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var address = opts.getAddress();
                    var amount = opts.getAmount();
                    verifyStringIsValidDecimal(OPT_AMOUNT, amount);

                    var txFeeRate = opts.getFeeRate();
                    if (!txFeeRate.isEmpty())
                        verifyStringIsValidLong(OPT_TX_FEE_RATE, txFeeRate);

                    var memo = opts.getMemo();

                    var txInfo = client.sendBtc(address, amount, txFeeRate, memo);
                    out.printf("%s btc sent to %s in tx %s%n",
                            amount,
                            address,
                            txInfo.getTxId());
                    return;
                }
                case gettxfeerate: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var txFeeRate = client.getTxFeeRate();
                    out.println(formatTxFeeRateInfo(txFeeRate));
                    return;
                }
                case settxfeerate: {
                    var opts = new SetTxFeeRateOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var txFeeRate = client.setTxFeeRate(toLong(opts.getFeeRate()));
                    out.println(formatTxFeeRateInfo(txFeeRate));
                    return;
                }
                case unsettxfeerate: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var txFeeRate = client.unsetTxFeeRate();
                    out.println(formatTxFeeRateInfo(txFeeRate));
                    return;
                }
                case gettransaction: {
                    var opts = new GetTransactionOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var txId = opts.getTxId();
                    var tx = client.getTransaction(txId);
                    out.println(TransactionFormat.format(tx));
                    return;
                }
                case createoffer: {
                    var opts = new CreateOfferOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var paymentAcctId = opts.getPaymentAccountId();
                    var direction = opts.getDirection();
                    var currencyCode = opts.getCurrencyCode();
                    var amount = toSatoshis(opts.getAmount());
                    var minAmount = toSatoshis(opts.getMinAmount());
                    var useMarketBasedPrice = opts.isUsingMktPriceMargin();
                    var fixedPrice = opts.getFixedPrice();
                    var marketPriceMargin = opts.getMktPriceMarginAsBigDecimal();
                    var securityDeposit = toSecurityDepositAsPct(opts.getSecurityDeposit());
                    var offer = client.createOffer(direction,
                            currencyCode,
                            amount,
                            minAmount,
                            useMarketBasedPrice,
                            fixedPrice,
                            marketPriceMargin.doubleValue(),
                            securityDeposit,
                            paymentAcctId);
                    out.println(formatOfferTable(singletonList(offer), currencyCode));
                    return;
                }
                case canceloffer: {
                    var opts = new CancelOfferOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var offerId = opts.getOfferId();
                    client.cancelOffer(offerId);
                    out.println("offer canceled and removed from offer book");
                    return;
                }
                case getoffer: {
                    var opts = new GetOfferOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var offerId = opts.getOfferId();
                    var offer = client.getOffer(offerId);
                    out.println(formatOfferTable(singletonList(offer), offer.getCounterCurrencyCode()));
                    return;
                }
                case getmyoffer: {
                    var opts = new GetOfferOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var offerId = opts.getOfferId();
                    var offer = client.getMyOffer(offerId);
                    out.println(formatOfferTable(singletonList(offer), offer.getCounterCurrencyCode()));
                    return;
                }
                case getoffers: {
                    var opts = new GetOffersOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var direction = opts.getDirection();
                    var currencyCode = opts.getCurrencyCode();
                    List<OfferInfo> offers = client.getOffers(direction, currencyCode);
                    if (offers.isEmpty())
                        out.printf("no %s %s offers found%n", direction, currencyCode);
                    else
                        out.println(formatOfferTable(offers, currencyCode));

                    return;
                }
                case getmyoffers: {
                    var opts = new GetOffersOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var direction = opts.getDirection();
                    var currencyCode = opts.getCurrencyCode();
                    List<OfferInfo> offers = client.getMyOffers(direction, currencyCode);
                    if (offers.isEmpty())
                        out.printf("no %s %s offers found%n", direction, currencyCode);
                    else
                        out.println(formatOfferTable(offers, currencyCode));

                    return;
                }
                case takeoffer: {
                    var opts = new TakeOfferOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var offerId = opts.getOfferId();
                    var paymentAccountId = opts.getPaymentAccountId();
                    var trade = client.takeOffer(offerId, paymentAccountId);
                    out.printf("trade %s successfully taken%n", trade.getTradeId());
                    return;
                }
                case gettrade: {
                    // TODO make short-id a valid argument?
                    var opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    var showContract = opts.getShowContract();
                    var trade = client.getTrade(tradeId);
                    if (showContract)
                        out.println(trade.getContractAsJson());
                    else
                        out.println(TradeFormat.format(trade));

                    return;
                }
                case confirmpaymentstarted: {
                    var opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    client.confirmPaymentStarted(tradeId);
                    out.printf("trade %s payment started message sent%n", tradeId);
                    return;
                }
                case confirmpaymentreceived: {
                    var opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    client.confirmPaymentReceived(tradeId);
                    out.printf("trade %s payment received message sent%n", tradeId);
                    return;
                }
                case keepfunds: {
                    var opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    client.keepFunds(tradeId);
                    out.printf("funds from trade %s saved in haveno wallet%n", tradeId);
                    return;
                }
                case withdrawfunds: {
                    var opts = new WithdrawFundsOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    var address = opts.getAddress();
                    // Multi-word memos must be double quoted.
                    var memo = opts.getMemo();
                    client.withdrawFunds(tradeId, address, memo);
                    out.printf("trade %s funds sent to btc address %s%n", tradeId, address);
                    return;
                }
                case getpaymentmethods: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var paymentMethods = client.getPaymentMethods();
                    paymentMethods.forEach(p -> out.println(p.getId()));
                    return;
                }
                case getpaymentacctform: {
                    var opts = new GetPaymentAcctFormOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var paymentMethodId = opts.getPaymentMethodId();
                    String jsonString = client.getPaymentAcctFormAsJson(paymentMethodId);
                    File jsonFile = saveFileToDisk(paymentMethodId.toLowerCase(),
                            ".json",
                            jsonString);
                    out.printf("payment account form %s%nsaved to %s%n",
                            jsonString, jsonFile.getAbsolutePath());
                    out.println("Edit the file, and use as the argument to a 'createpaymentacct' command.");
                    return;
                }
                case createpaymentacct: {
                    var opts = new CreatePaymentAcctOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var paymentAccountForm = opts.getPaymentAcctForm();
                    String jsonString;
                    try {
                        jsonString = new String(Files.readAllBytes(paymentAccountForm));
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                format("could not read %s", paymentAccountForm));
                    }
                    var paymentAccount = client.createPaymentAccount(jsonString);
                    out.println("payment account saved");
                    out.println(formatPaymentAcctTbl(singletonList(paymentAccount)));
                    return;
                }
                case createcryptopaymentacct: {
                    var opts = new CreateCryptoCurrencyPaymentAcctOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var accountName = opts.getAccountName();
                    var currencyCode = opts.getCurrencyCode();
                    var address = opts.getAddress();
                    var isTradeInstant = opts.getIsTradeInstant();
                    var paymentAccount = client.createCryptoCurrencyPaymentAccount(accountName,
                            currencyCode,
                            address,
                            isTradeInstant);
                    out.println("payment account saved");
                    out.println(formatPaymentAcctTbl(singletonList(paymentAccount)));
                    return;
                }
                case getpaymentaccts: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var paymentAccounts = client.getPaymentAccounts();
                    if (paymentAccounts.size() > 0)
                        out.println(formatPaymentAcctTbl(paymentAccounts));
                    else
                        out.println("no payment accounts are saved");

                    return;
                }
                case lockwallet: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.lockWallet();
                    out.println("wallet locked");
                    return;
                }
                case unlockwallet: {
                    var opts = new UnlockWalletOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var walletPassword = opts.getPassword();
                    var timeout = opts.getUnlockTimeout();
                    client.unlockWallet(walletPassword, timeout);
                    out.println("wallet unlocked");
                    return;
                }
                case removewalletpassword: {
                    var opts = new RemoveWalletPasswordOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var walletPassword = opts.getPassword();
                    client.removeWalletPassword(walletPassword);
                    out.println("wallet decrypted");
                    return;
                }
                case setwalletpassword: {
                    var opts = new SetWalletPasswordOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var walletPassword = opts.getPassword();
                    var newWalletPassword = opts.getNewPassword();
                    client.setWalletPassword(walletPassword, newWalletPassword);
                    out.println("wallet encrypted" + (!newWalletPassword.isEmpty() ? " with new password" : ""));
                    return;
                }
                case registerdisputeagent: {
                    var opts = new RegisterDisputeAgentOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var disputeAgentType = opts.getDisputeAgentType();
                    var registrationKey = opts.getRegistrationKey();
                    client.registerDisputeAgent(disputeAgentType, registrationKey);
                    out.println(disputeAgentType + " registered");
                    return;
                }
                case stop: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.stopServer();
                    out.println("server shutdown signal received");
                    return;
                }
                default: {
                    throw new RuntimeException(format("unhandled method '%s'", method));
                }
            }
        } catch (StatusRuntimeException ex) {
            // Remove the leading gRPC status code (e.g. "UNKNOWN: ") from the message
            String message = ex.getMessage().replaceFirst("^[A-Z_]+: ", "");
            if (message.equals("io exception"))
                throw new RuntimeException(message + ", server may not be running", ex);
            else
                throw new RuntimeException(message, ex);
        }
    }

    private static Method getMethodFromCmd(String methodName) {
        // TODO if we use const type for enum we need add some mapping.  Even if we don't
        //  change now it is handy to have flexibility in case we change internal code
        //  and don't want to break user commands.
        return Method.valueOf(methodName.toLowerCase());
    }

    @SuppressWarnings("SameParameterValue")
    private static void verifyStringIsValidDecimal(String optionLabel, String optionValue) {
        try {
            Double.parseDouble(optionValue);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(format("--%s=%s, '%s' is not a number",
                    optionLabel,
                    optionValue,
                    optionValue));
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static void verifyStringIsValidLong(String optionLabel, String optionValue) {
        try {
            Long.parseLong(optionValue);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(format("--%s=%s, '%s' is not a number",
                    optionLabel,
                    optionValue,
                    optionValue));
        }
    }

    private static long toLong(String param) {
        try {
            return Long.parseLong(param);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(format("'%s' is not a number", param));
        }
    }

    private static File saveFileToDisk(String prefix,
                                       @SuppressWarnings("SameParameterValue") String suffix,
                                       String text) {
        String timestamp = Long.toUnsignedString(new Date().getTime());
        String relativeFileName = prefix + "_" + timestamp + suffix;
        try {
            Path path = Paths.get(relativeFileName);
            if (!Files.exists(path)) {
                try (PrintWriter out = new PrintWriter(path.toString())) {
                    out.println(text);
                }
                return path.toAbsolutePath().toFile();
            } else {
                throw new IllegalStateException(format("could not overwrite existing file '%s'", relativeFileName));
            }
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(format("could not create file '%s'", relativeFileName));
        }
    }

    private static void printHelp(OptionParser parser, @SuppressWarnings("SameParameterValue") PrintStream stream) {
        try {
            stream.println("Haveno RPC Client");
            stream.println();
            stream.println("Usage: haveno-cli [options] <method> [params]");
            stream.println();
            parser.printHelpOn(stream);
            stream.println();
            String rowFormat = "%-25s%-52s%s%n";
            stream.format(rowFormat, "Method", "Params", "Description");
            stream.format(rowFormat, "------", "------", "------------");
            stream.format(rowFormat, getversion.name(), "", "Get server version");
            stream.println();
            stream.format(rowFormat, getbalance.name(), "[--currency-code=<btc>]", "Get server wallet balances");
            stream.println();
            stream.format(rowFormat, getaddressbalance.name(), "--address=<btc-address>", "Get server wallet address balance");
            stream.println();
            stream.format(rowFormat, getbtcprice.name(), "--currency-code=<currency-code>", "Get current market btc price");
            stream.println();
            stream.format(rowFormat, getfundingaddresses.name(), "", "Get BTC funding addresses");
            stream.println();
            stream.format(rowFormat, sendbtc.name(), "--address=<btc-address> --amount=<btc-amount> \\", "Send BTC");
            stream.format(rowFormat, "", "[--tx-fee-rate=<sats/byte>]", "");
            stream.format(rowFormat, "", "[--memo=<\"memo\">]", "");
            stream.println();
            stream.println();
            stream.format(rowFormat, gettxfeerate.name(), "", "Get current tx fee rate in sats/byte");
            stream.println();
            stream.format(rowFormat, settxfeerate.name(), "--tx-fee-rate=<sats/byte>", "Set custom tx fee rate in sats/byte");
            stream.println();
            stream.format(rowFormat, unsettxfeerate.name(), "", "Unset custom tx fee rate");
            stream.println();
            stream.format(rowFormat, gettransaction.name(), "--transaction-id=<transaction-id>", "Get transaction with id");
            stream.println();
            stream.format(rowFormat, createoffer.name(), "--payment-account=<payment-account-id> \\", "Create and place an offer");
            stream.format(rowFormat, "", "--direction=<buy|sell> \\", "");
            stream.format(rowFormat, "", "--currency-code=<currency-code> \\", "");
            stream.format(rowFormat, "", "--amount=<btc-amount> \\", "");
            stream.format(rowFormat, "", "[--min-amount=<min-btc-amount>] \\", "");
            stream.format(rowFormat, "", "--fixed-price=<price> | --market-price=margin=<percent> \\", "");
            stream.format(rowFormat, "", "--security-deposit=<percent> \\", "");
            stream.format(rowFormat, "", "[--fee-currency=<btc>]", "");
            stream.println();
            stream.format(rowFormat, canceloffer.name(), "--offer-id=<offer-id>", "Cancel offer with id");
            stream.println();
            stream.format(rowFormat, getoffer.name(), "--offer-id=<offer-id>", "Get current offer with id");
            stream.println();
            stream.format(rowFormat, getmyoffer.name(), "--offer-id=<offer-id>", "Get my current offer with id");
            stream.println();
            stream.format(rowFormat, getoffers.name(), "--direction=<buy|sell> \\", "Get current offers");
            stream.format(rowFormat, "", "--currency-code=<currency-code>", "");
            stream.println();
            stream.format(rowFormat, getmyoffers.name(), "--direction=<buy|sell> \\", "Get my current offers");
            stream.format(rowFormat, "", "--currency-code=<currency-code>", "");
            stream.println();
            stream.format(rowFormat, takeoffer.name(), "--offer-id=<offer-id> \\", "Take offer with id");
            stream.format(rowFormat, "", "--payment-account=<payment-account-id>", "");
            stream.format(rowFormat, "", "[--fee-currency=<btc>]", "");
            stream.println();
            stream.format(rowFormat, gettrade.name(), "--trade-id=<trade-id> \\", "Get trade summary or full contract");
            stream.format(rowFormat, "", "[--show-contract=<true|false>]", "");
            stream.println();
            stream.format(rowFormat, confirmpaymentstarted.name(), "--trade-id=<trade-id>", "Confirm payment started");
            stream.println();
            stream.format(rowFormat, confirmpaymentreceived.name(), "--trade-id=<trade-id>", "Confirm payment received");
            stream.println();
            stream.format(rowFormat, keepfunds.name(), "--trade-id=<trade-id>", "Keep received funds in Haveno wallet");
            stream.println();
            stream.format(rowFormat, withdrawfunds.name(), "--trade-id=<trade-id> --address=<btc-address> \\",
                    "Withdraw received funds to external wallet address");
            stream.format(rowFormat, "", "[--memo=<\"memo\">]", "");
            stream.println();
            stream.format(rowFormat, getpaymentmethods.name(), "", "Get list of supported payment account method ids");
            stream.println();
            stream.format(rowFormat, getpaymentacctform.name(), "--payment-method-id=<payment-method-id>", "Get a new payment account form");
            stream.println();
            stream.format(rowFormat, createpaymentacct.name(), "--payment-account-form=<path>", "Create a new payment account");
            stream.println();
            stream.format(rowFormat, createcryptopaymentacct.name(), "--account-name=<name> \\", "Create a new cryptocurrency payment account");
            stream.format(rowFormat, "", "--currency-code=<btc> \\", "");
            stream.format(rowFormat, "", "--address=<address>", "");
            stream.format(rowFormat, "", "--trade-instant=<true|false>", "");
            stream.println();
            stream.format(rowFormat, getpaymentaccts.name(), "", "Get user payment accounts");
            stream.println();
            stream.format(rowFormat, lockwallet.name(), "", "Remove wallet password from memory, locking the wallet");
            stream.println();
            stream.format(rowFormat, unlockwallet.name(), "--wallet-password=<password> --timeout=<seconds>",
                    "Store wallet password in memory for timeout seconds");
            stream.println();
            stream.format(rowFormat, setwalletpassword.name(), "--wallet-password=<password> \\",
                    "Encrypt wallet with password, or set new password on encrypted wallet");
            stream.format(rowFormat, "", "[--new-wallet-password=<new-password>]", "");
            stream.println();
            stream.format(rowFormat, stop.name(), "", "Shut down the server");
            stream.println();
            stream.println("Method Help Usage: haveno-cli [options] <method> --help");
            stream.println();
        } catch (IOException ex) {
            ex.printStackTrace(stream);
        }
    }
}
