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

import haveno.cli.opts.ArgumentList;
import haveno.cli.opts.CancelOfferOptionParser;
import haveno.cli.opts.CreateCryptoCurrencyPaymentAcctOptionParser;
import haveno.cli.opts.CreateOfferOptionParser;
import haveno.cli.opts.CreatePaymentAcctOptionParser;
import haveno.cli.opts.GetAddressBalanceOptionParser;
import haveno.cli.opts.GetBTCMarketPriceOptionParser;
import haveno.cli.opts.GetBalanceOptionParser;
import haveno.cli.opts.GetOffersOptionParser;
import haveno.cli.opts.GetPaymentAcctFormOptionParser;
import haveno.cli.opts.GetTradeOptionParser;
import haveno.cli.opts.GetTradesOptionParser;
import haveno.cli.opts.OfferIdOptionParser;
import haveno.cli.opts.RegisterDisputeAgentOptionParser;
import haveno.cli.opts.RemoveWalletPasswordOptionParser;
import haveno.cli.opts.SendBtcOptionParser;
import haveno.cli.opts.SetWalletPasswordOptionParser;
import haveno.cli.opts.SimpleMethodOptionParser;
import haveno.cli.opts.TakeOfferOptionParser;
import haveno.cli.opts.UnlockWalletOptionParser;
import haveno.cli.opts.WithdrawFundsOptionParser;
import haveno.cli.table.builder.TableBuilder;
import haveno.proto.grpc.OfferInfo;
import io.grpc.StatusRuntimeException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;

import static haveno.cli.CurrencyFormat.formatInternalFiatPrice;
import static haveno.cli.CurrencyFormat.toSatoshis;
import static haveno.cli.Method.canceloffer;
import static haveno.cli.Method.closetrade;
import static haveno.cli.Method.confirmpaymentreceived;
import static haveno.cli.Method.confirmpaymentsent;
import static haveno.cli.Method.createcryptopaymentacct;
import static haveno.cli.Method.createoffer;
import static haveno.cli.Method.createpaymentacct;
import static haveno.cli.Method.editoffer;
import static haveno.cli.Method.failtrade;
import static haveno.cli.Method.getaddressbalance;
import static haveno.cli.Method.getbalance;
import static haveno.cli.Method.getbtcprice;
import static haveno.cli.Method.getfundingaddresses;
import static haveno.cli.Method.getmyoffer;
import static haveno.cli.Method.getmyoffers;
import static haveno.cli.Method.getoffer;
import static haveno.cli.Method.getoffers;
import static haveno.cli.Method.getpaymentacctform;
import static haveno.cli.Method.getpaymentaccts;
import static haveno.cli.Method.getpaymentmethods;
import static haveno.cli.Method.gettrade;
import static haveno.cli.Method.gettrades;
import static haveno.cli.Method.gettransaction;
import static haveno.cli.Method.gettxfeerate;
import static haveno.cli.Method.getunusedbsqaddress;
import static haveno.cli.Method.getversion;
import static haveno.cli.Method.lockwallet;
import static haveno.cli.Method.sendbtc;
import static haveno.cli.Method.settxfeerate;
import static haveno.cli.Method.setwalletpassword;
import static haveno.cli.Method.stop;
import static haveno.cli.Method.takeoffer;
import static haveno.cli.Method.unfailtrade;
import static haveno.cli.Method.unlockwallet;
import static haveno.cli.Method.unsettxfeerate;
import static haveno.cli.Method.withdrawfunds;
import static haveno.cli.opts.OptLabel.OPT_AMOUNT;
import static haveno.cli.opts.OptLabel.OPT_HELP;
import static haveno.cli.opts.OptLabel.OPT_HOST;
import static haveno.cli.opts.OptLabel.OPT_PASSWORD;
import static haveno.cli.opts.OptLabel.OPT_PORT;
import static haveno.cli.opts.OptLabel.OPT_TX_FEE_RATE;
import static haveno.cli.table.builder.TableType.ADDRESS_BALANCE_TBL;
import static haveno.cli.table.builder.TableType.BTC_BALANCE_TBL;
import static haveno.cli.table.builder.TableType.CLOSED_TRADES_TBL;
import static haveno.cli.table.builder.TableType.FAILED_TRADES_TBL;
import static haveno.cli.table.builder.TableType.OFFER_TBL;
import static haveno.cli.table.builder.TableType.OPEN_TRADES_TBL;
import static haveno.cli.table.builder.TableType.PAYMENT_ACCOUNT_TBL;
import static haveno.cli.table.builder.TableType.TRADE_DETAIL_TBL;
import static haveno.proto.grpc.GetTradesRequest.Category.CLOSED;
import static haveno.proto.grpc.GetTradesRequest.Category.OPEN;
import static java.lang.String.format;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;

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
                            new TableBuilder(BTC_BALANCE_TBL, balances.getBtc()).build().print(out);
                            break;
                        case "":
                        default: {
                            out.println("BTC");
                            new TableBuilder(BTC_BALANCE_TBL, balances.getBtc()).build().print(out);
                            break;
                        }
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
                    new TableBuilder(ADDRESS_BALANCE_TBL, addressBalance).build().print(out);
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
                    out.println(formatInternalFiatPrice(price));
                    return;
                }
                case getfundingaddresses: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var fundingAddresses = client.getFundingAddresses();
                    new TableBuilder(ADDRESS_BALANCE_TBL, fundingAddresses).build().print(out);
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

                    throw new RuntimeException("Send BTC not implemented");
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
                    var marketPriceMarginPct = opts.getMktPriceMarginPct();
                    var securityDepositPct = opts.getSecurityDepositPct();
                    var triggerPrice = "0"; // Cannot be defined until the new offer is added to book.
                    OfferInfo offer;
                    offer = client.createOffer(direction,
                            currencyCode,
                            amount,
                            minAmount,
                            useMarketBasedPrice,
                            fixedPrice,
                            marketPriceMarginPct,
                            securityDepositPct,
                            paymentAcctId,
                            triggerPrice);
                    new TableBuilder(OFFER_TBL, offer).build().print(out);
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
                    var opts = new OfferIdOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var offerId = opts.getOfferId();
                    var offer = client.getOffer(offerId);
                    new TableBuilder(OFFER_TBL, offer).build().print(out);
                    return;
                }
                case getmyoffer: {
                    var opts = new OfferIdOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var offerId = opts.getOfferId();
                    var offer = client.getMyOffer(offerId);
                    new TableBuilder(OFFER_TBL, offer).build().print(out);
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
                        new TableBuilder(OFFER_TBL, offers).build().print(out);

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
                        new TableBuilder(OFFER_TBL, offers).build().print(out);

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
                        new TableBuilder(TRADE_DETAIL_TBL, trade).build().print(out);

                    return;
                }
                case gettrades: {
                    var opts = new GetTradesOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var category = opts.getCategory();
                    var trades = category.equals(OPEN)
                            ? client.getOpenTrades()
                            : client.getTradeHistory(category);
                    if (trades.isEmpty()) {
                        out.printf("no %s trades found%n", category.name().toLowerCase());
                    } else {
                        var tableType = category.equals(OPEN)
                                ? OPEN_TRADES_TBL
                                : category.equals(CLOSED) ? CLOSED_TRADES_TBL : FAILED_TRADES_TBL;
                        new TableBuilder(tableType, trades).build().print(out);
                    }
                    return;
                }
                case confirmpaymentsent: {
                    var opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    client.confirmPaymentSent(tradeId);
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
                case withdrawfunds: {
                    var opts = new WithdrawFundsOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    var address = opts.getAddress();
                    // Multi-word memos must be double-quoted.
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
                    new TableBuilder(PAYMENT_ACCOUNT_TBL, paymentAccount).build().print(out);
                    return;
                }
                case createcryptopaymentacct: {
                    var opts =
                            new CreateCryptoCurrencyPaymentAcctOptionParser(args).parse();
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
                    new TableBuilder(PAYMENT_ACCOUNT_TBL, paymentAccount).build().print(out);
                    return;
                }
                case getpaymentaccts: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var paymentAccounts = client.getPaymentAccounts();
                    if (paymentAccounts.size() > 0)
                        new TableBuilder(PAYMENT_ACCOUNT_TBL, paymentAccounts).build().print(out);
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
            // Remove the leading gRPC status code, e.g., INVALID_ARGUMENT,
            // NOT_FOUND, ..., UNKNOWN from the exception message.
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
            stream.format(rowFormat, getbalance.name(), "[--currency-code=<bsq|btc>]", "Get server wallet balances");
            stream.println();
            stream.format(rowFormat, getaddressbalance.name(), "--address=<btc-address>", "Get server wallet address balance");
            stream.println();
            stream.format(rowFormat, getbtcprice.name(), "--currency-code=<currency-code>", "Get current market btc price");
            stream.println();
            stream.format(rowFormat, getfundingaddresses.name(), "", "Get BTC funding addresses");
            stream.println();
            stream.format(rowFormat, getunusedbsqaddress.name(), "", "Get unused BSQ address");
            stream.println();
            stream.format(rowFormat, "", "[--tx-fee-rate=<sats/byte>]", "");
            stream.println();
            stream.format(rowFormat, sendbtc.name(), "--address=<btc-address> --amount=<btc-amount> \\", "Send BTC");
            stream.format(rowFormat, "", "[--tx-fee-rate=<sats/byte>]", "");
            stream.format(rowFormat, "", "[--memo=<\"memo\">]", "");
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
            stream.format(rowFormat, "", "--fixed-price=<price> | --market-price-margin=<percent> \\", "");
            stream.format(rowFormat, "", "--security-deposit=<percent> \\", "");
            stream.format(rowFormat, "", "[--fee-currency=<bsq|btc>]", "");
            stream.format(rowFormat, "", "[--trigger-price=<price>]", "");
            stream.format(rowFormat, "", "[--swap=<true|false>]", "");
            stream.println();
            stream.format(rowFormat, editoffer.name(), "--offer-id=<offer-id> \\", "Edit offer with id");
            stream.format(rowFormat, "", "[--fixed-price=<price>] \\", "");
            stream.format(rowFormat, "", "[--market-price-margin=<percent>] \\", "");
            stream.format(rowFormat, "", "[--trigger-price=<price>] \\", "");
            stream.format(rowFormat, "", "[--enabled=<true|false>]", "");
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
            stream.format(rowFormat, "", "[--payment-account=<payment-account-id>]", "");
            stream.format(rowFormat, "", "[--fee-currency=<btc|bsq>]", "");
            stream.println();
            stream.format(rowFormat, gettrade.name(), "--trade-id=<trade-id> \\", "Get trade summary or full contract");
            stream.format(rowFormat, "", "[--show-contract=<true|false>]", "");
            stream.println();
            stream.format(rowFormat, gettrades.name(), "[--category=<open|closed|failed>]", "Get open (default), closed, or failed trades");
            stream.println();
            stream.format(rowFormat, confirmpaymentsent.name(), "--trade-id=<trade-id>", "Confirm payment started");
            stream.println();
            stream.format(rowFormat, confirmpaymentreceived.name(), "--trade-id=<trade-id>", "Confirm payment received");
            stream.println();
            stream.format(rowFormat, closetrade.name(), "--trade-id=<trade-id>", "Close completed trade");
            stream.println();
            stream.format(rowFormat, withdrawfunds.name(), "--trade-id=<trade-id> --address=<btc-address> \\",
                    "Withdraw received trade funds to external wallet address");
            stream.format(rowFormat, "", "[--memo=<\"memo\">]", "");
            stream.println();
            stream.format(rowFormat, failtrade.name(), "--trade-id=<trade-id>", "Change open trade to failed trade");
            stream.println();
            stream.format(rowFormat, unfailtrade.name(), "--trade-id=<trade-id>", "Change failed trade to open trade");
            stream.println();
            stream.format(rowFormat, getpaymentmethods.name(), "", "Get list of supported payment account method ids");
            stream.println();
            stream.format(rowFormat, getpaymentacctform.name(), "--payment-method-id=<payment-method-id>", "Get a new payment account form");
            stream.println();
            stream.format(rowFormat, createpaymentacct.name(), "--payment-account-form=<path>", "Create a new payment account");
            stream.println();
            stream.format(rowFormat, createcryptopaymentacct.name(), "--account-name=<name> \\", "Create a new cryptocurrency payment account");
            stream.format(rowFormat, "", "--currency-code=<bsq> \\", "");
            stream.format(rowFormat, "", "--address=<bsq-address>", "");
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
