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

package haveno.cli;

import haveno.cli.opts.ArgumentList;
import haveno.cli.opts.CancelOfferOptionParser;
import haveno.cli.opts.CreateCryptoCurrencyPaymentAcctOptionParser;
import haveno.cli.opts.CreateOfferOptionParser;
import haveno.cli.opts.CreatePaymentAcctOptionParser;
import haveno.cli.opts.CreateXmrTxOptionParser;
import haveno.cli.opts.GetAddressBalanceOptionParser;
import haveno.cli.opts.GetBalanceOptionParser;
import haveno.cli.opts.GetMarketPriceOptionParser;
import haveno.cli.opts.GetOffersOptionParser;
import haveno.cli.opts.GetPaymentAcctFormOptionParser;
import haveno.cli.opts.GetTradeOptionParser;
import haveno.cli.opts.GetTradesOptionParser;
import haveno.cli.opts.OfferIdOptionParser;
import haveno.cli.opts.OptLabel;
import haveno.cli.opts.RegisterDisputeAgentOptionParser;
import haveno.cli.opts.RelayXmrTxOptionParser;
import haveno.cli.opts.RemoveWalletPasswordOptionParser;
import haveno.cli.opts.SetWalletPasswordOptionParser;
import haveno.cli.opts.SimpleMethodOptionParser;
import haveno.cli.opts.TakeOfferOptionParser;
import haveno.cli.opts.UnlockWalletOptionParser;
import haveno.cli.opts.WithdrawFundsOptionParser;
import haveno.cli.table.builder.TableBuilder;
import haveno.proto.grpc.OfferInfo;
import haveno.proto.grpc.XmrDestination;
import haveno.proto.grpc.GetTradesRequest;
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

import static haveno.cli.Method.getversion;
import static haveno.cli.Method.getbalance;
import static haveno.cli.Method.getaddressbalance;
import static haveno.cli.Method.getxmrprice;
import static haveno.cli.Method.getfundingaddresses;
import static haveno.cli.Method.getunusedxmraddress;
import static haveno.cli.Method.getxmrseed;
import static haveno.cli.Method.getxmrprimaryaddress;
import static haveno.cli.Method.getxmrnewsubaddress;
import static haveno.cli.Method.getxmrtxs;
import static haveno.cli.Method.createxmrtx;
import static haveno.cli.Method.relayxmrtx;
import static haveno.cli.Method.createoffer;
import static haveno.cli.Method.canceloffer;
import static haveno.cli.Method.getoffer;
import static haveno.cli.Method.getmyoffer;
import static haveno.cli.Method.getoffers;
import static haveno.cli.Method.getmyoffers;
import static haveno.cli.Method.takeoffer;
import static haveno.cli.Method.gettrade;
import static haveno.cli.Method.gettrades;
import static haveno.cli.Method.confirmpaymentsent;
import static haveno.cli.Method.confirmpaymentreceived;
import static haveno.cli.Method.withdrawfunds;
import static haveno.cli.Method.getpaymentmethods;
import static haveno.cli.Method.getpaymentacctform;
import static haveno.cli.Method.createpaymentacct;
import static haveno.cli.Method.createcryptopaymentacct;
import static haveno.cli.Method.getpaymentaccts;
import static haveno.cli.Method.lockwallet;
import static haveno.cli.Method.unlockwallet;
import static haveno.cli.Method.removewalletpassword;
import static haveno.cli.Method.setwalletpassword;
import static haveno.cli.Method.registerdisputeagent;
import static haveno.cli.Method.stop;
import static haveno.cli.opts.OptLabel.OPT_HELP;
import static haveno.cli.opts.OptLabel.OPT_HOST;
import static haveno.cli.opts.OptLabel.OPT_PORT;
import static haveno.cli.opts.OptLabel.OPT_PASSWORD;
import static haveno.cli.table.builder.TableType.XMR_BALANCE_TBL;
import static haveno.cli.table.builder.TableType.ADDRESS_BALANCE_TBL;
import static haveno.cli.table.builder.TableType.OFFER_TBL;
import static haveno.cli.table.builder.TableType.TRADE_DETAIL_TBL;
import static haveno.cli.table.builder.TableType.OPEN_TRADES_TBL;
import static haveno.cli.table.builder.TableType.CLOSED_TRADES_TBL;
import static haveno.cli.table.builder.TableType.FAILED_TRADES_TBL;
import static haveno.cli.table.builder.TableType.PAYMENT_ACCOUNT_TBL;
import static haveno.cli.CurrencyFormat.formatInternalFiatPrice;
import static haveno.cli.CurrencyFormat.toPiconeros;
import static haveno.proto.grpc.GetTradesRequest.Category.CLOSED;
import static haveno.proto.grpc.GetTradesRequest.Category.OPEN;
import static java.lang.String.format;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;
import static haveno.cli.CurrencyFormat.formatInternalFiatPrice;
import static haveno.cli.CurrencyFormat.toPiconeros;
import static haveno.cli.Method.*;
import static haveno.cli.opts.OptLabel.*;
import static haveno.cli.table.builder.TableType.*;
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
        OptionParser parser = new OptionParser();

        parser.accepts(OPT_HELP, "Print this help text")
                .forHelp();

        parser.accepts(OPT_HOST, "rpc server hostname or ip")
                .withRequiredArg()
                .defaultsTo("localhost");

        parser.accepts(OPT_PORT, "rpc server port")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(9998);

        parser.accepts(OPT_PASSWORD, "rpc server password")
                .withRequiredArg();

        // Parse the CLI opts host, port, password, method name, and help.  The help opt
        // may indicate the user is asking for method level help, and will be excluded
        // from the parsed options if a method opt is present in String[] args.
        OptionSet options = parser.parse(new ArgumentList(args).getCLIArguments());
        @SuppressWarnings("unchecked")
        List<String> nonOptionArgs = (List<String>) options.nonOptionArguments();

        // If neither the help opt nor a method name is present, print CLI level help
        // to stderr and throw an exception.
        if (!options.has(OPT_HELP) && nonOptionArgs.isEmpty()) {
            printHelp(parser, err);
            throw new IllegalArgumentException("no method specified");
        }

        // If the help opt is present, but not a method name, print CLI level help
        // to stdout.
        if (options.has(OPT_HELP) && nonOptionArgs.isEmpty()) {
            printHelp(parser, out);
            return;
        }

        String host = (String) options.valueOf(OPT_HOST);
        int port = (Integer) options.valueOf(OPT_PORT);
        String password = (String) options.valueOf(OPT_PASSWORD);
        if (password == null)
            throw new IllegalArgumentException("missing required 'password' option");

        String methodName = nonOptionArgs.get(0);
        Method method;
        try {
            method = Method.valueOf(methodName.toLowerCase());
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
                    String version = client.getVersion();
                    out.println(version);
                    return;
                }
                case getbalance: {
                    GetBalanceOptionParser opts = new GetBalanceOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String currencyCode = opts.getCurrencyCode();
                    var balances = client.getBalances(currencyCode);
                    switch (currencyCode.toUpperCase()) {
                        case "XMR":
                            new TableBuilder(XMR_BALANCE_TBL, balances.getXmr()).build().print(out);
                            break;
                        case "":
                        default: {
                            out.println("XMR");
                            new TableBuilder(XMR_BALANCE_TBL, balances.getXmr()).build().print(out);
                            break;
                        }
                    }
                    return;
                }
                case getaddressbalance: {
                    GetAddressBalanceOptionParser opts = new GetAddressBalanceOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String address = opts.getAddress();
                    var addressBalance = client.getAddressBalance(address);
                    new TableBuilder(ADDRESS_BALANCE_TBL, addressBalance).build().print(out);
                    return;
                }
                case getxmrprice: {
                    GetMarketPriceOptionParser opts = new GetMarketPriceOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String currencyCode = opts.getCurrencyCode();
                    double price = client.getXmrPrice(currencyCode);
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
                case getunusedxmraddress: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String address = client.getUnusedXmrAddress();
                    out.println(address);
                    return;
                }
                case getxmrseed: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String seed = client.getXmrSeed();
                    out.println(seed);
                    return;
                }
                case getxmrprimaryaddress: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String address = client.getXmrPrimaryAddress();
                    out.println(address);
                    return;
                }
                case getxmrnewsubaddress: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String address = client.getXmrNewSubaddress();
                    out.println(address);
                    return;
                }
                case getxmrtxs: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var txs = client.getXmrTxs();
                    txs.forEach(tx -> out.println(tx));
                    return;
                }
                case createxmrtx: {
                    CreateXmrTxOptionParser optionParser = new CreateXmrTxOptionParser(args);
                    OptionSet optionSet = optionParser.parse(args);
                    CreateXmrTxOptionParser.CreateXmrTxOptions opts = new CreateXmrTxOptionParser.CreateXmrTxOptions(optionSet);
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    List<XmrDestination> destinations = opts.getDestinations();
                    var tx = client.createXmrTx(destinations);
                    out.println(tx);
                    return;
                }
                case relayxmrtx: {
                    RelayXmrTxOptionParser optionParser = new RelayXmrTxOptionParser(args);
                    OptionSet optionSet = optionParser.parse(args);
                    RelayXmrTxOptionParser.RelayXmrTxOptions opts = new RelayXmrTxOptionParser.RelayXmrTxOptions(optionSet);
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String metadata = opts.getMetadata();
                    String hash = client.relayXmrTx(metadata);
                    out.println(hash);
                    return;
                }
                case createoffer: {
                    CreateOfferOptionParser opts = new CreateOfferOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String paymentAcctId = opts.getPaymentAccountId();
                    String direction = opts.getDirection();
                    String currencyCode = opts.getCurrencyCode();
                    long amount = toPiconeros(opts.getAmount());
                    long minAmount = toPiconeros(opts.getMinAmount());
                    boolean useMarketBasedPrice = opts.isUsingMktPriceMargin();
                    String fixedPrice = opts.getFixedPrice();
                    double marketPriceMarginPct = opts.getMktPriceMarginPct();
                    double securityDepositPct = opts.getSecurityDepositPct();
                    String triggerPrice = "0";
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
                    CancelOfferOptionParser opts = new CancelOfferOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String offerId = opts.getOfferId();
                    client.cancelOffer(offerId);
                    out.println("offer canceled and removed from offer book");
                    return;
                }
                case getoffer: {
                    OfferIdOptionParser opts = new OfferIdOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String offerId = opts.getOfferId();
                    OfferInfo offer = client.getOffer(offerId);
                    new TableBuilder(OFFER_TBL, offer).build().print(out);
                    return;
                }
                case getmyoffer: {
                    OfferIdOptionParser opts = new OfferIdOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String offerId = opts.getOfferId();
                    OfferInfo offer = client.getMyOffer(offerId);
                    new TableBuilder(OFFER_TBL, offer).build().print(out);
                    return;
                }
                case getoffers: {
                    GetOffersOptionParser opts = new GetOffersOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String direction = opts.getDirection();
                    String currencyCode = opts.getCurrencyCode();
                    List<OfferInfo> offers = client.getOffers(direction, currencyCode);
                    if (offers.isEmpty())
                        out.printf("no %s %s offers found%n", direction, currencyCode);
                    else
                        new TableBuilder(OFFER_TBL, offers).build().print(out);

                    return;
                }
                case getmyoffers: {
                    GetOffersOptionParser opts = new GetOffersOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String direction = opts.getDirection();
                    String currencyCode = opts.getCurrencyCode();
                    List<OfferInfo> offers = client.getMyOffers(direction, currencyCode);
                    if (offers.isEmpty())
                        out.printf("no %s %s offers found%n", direction, currencyCode);
                    else
                        new TableBuilder(OFFER_TBL, offers).build().print(out);

                    return;
                }
                case takeoffer: {

                    TakeOfferOptionParser opts = new TakeOfferOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String offerId = opts.getOfferId();
                    String paymentAccountId = opts.getPaymentAccountId();
                    var trade = client.takeOffer(offerId, paymentAccountId);
                    out.printf("trade %s successfully taken%n", trade.getTradeId());
                    return;
                }
                case gettrade: {
                    // TODO make short-id a valid argument?
                    GetTradeOptionParser opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String tradeId = opts.getTradeId();
                    boolean showContract = opts.getShowContract();
                    var trade = client.getTrade(tradeId);
                    if (showContract)
                        out.println(trade.getContractAsJson());
                    else
                        new TableBuilder(TRADE_DETAIL_TBL, trade).build().print(out);

                    return;
                }
                case gettrades: {
                    GetTradesOptionParser opts = new GetTradesOptionParser(args).parse();
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
                    GetTradeOptionParser opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String tradeId = opts.getTradeId();
                    client.confirmPaymentSent(tradeId);
                    out.printf("trade %s payment started message sent%n", tradeId);
                    return;
                }
                case confirmpaymentreceived: {
                    GetTradeOptionParser opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String tradeId = opts.getTradeId();
                    client.confirmPaymentReceived(tradeId);
                    out.printf("trade %s payment received message sent%n", tradeId);
                    return;
                }
                case withdrawfunds: {
                    WithdrawFundsOptionParser opts = new WithdrawFundsOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String tradeId = opts.getTradeId();
                    String address = opts.getAddress();
                    // Multi-word memos must be double-quoted.
                    String memo = opts.getMemo();
                    client.withdrawFunds(tradeId, address, memo);
                    out.printf("trade %s funds sent to xmr address %s%n", tradeId, address);
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
                    GetPaymentAcctFormOptionParser opts = new GetPaymentAcctFormOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String paymentMethodId = opts.getPaymentMethodId();
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
                    CreatePaymentAcctOptionParser opts = new CreatePaymentAcctOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    Path paymentAccountForm = opts.getPaymentAcctForm();
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
                    CreateCryptoCurrencyPaymentAcctOptionParser opts =
                            new CreateCryptoCurrencyPaymentAcctOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String accountName = opts.getAccountName();
                    String currencyCode = opts.getCurrencyCode();
                    String address = opts.getAddress();
                    boolean isTradeInstant = opts.getIsTradeInstant();
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
                    UnlockWalletOptionParser opts = new UnlockWalletOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String walletPassword = opts.getPassword();
                    long timeout = opts.getUnlockTimeout();
                    client.unlockWallet(walletPassword, timeout);
                    out.println("wallet unlocked");
                    return;
                }
                case removewalletpassword: {
                    RemoveWalletPasswordOptionParser opts = new RemoveWalletPasswordOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String walletPassword = opts.getPassword();
                    client.removeWalletPassword(walletPassword);
                    out.println("wallet decrypted");
                    return;
                }
                case setwalletpassword: {
                    SetWalletPasswordOptionParser opts = new SetWalletPasswordOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String walletPassword = opts.getPassword();
                    String newWalletPassword = opts.getNewPassword();
                    client.setWalletPassword(walletPassword, newWalletPassword);
                    out.println("wallet encrypted" + (!newWalletPassword.isEmpty() ? " with new password" : ""));
                    return;
                }
                case registerdisputeagent: {
                    RegisterDisputeAgentOptionParser opts = new RegisterDisputeAgentOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    String disputeAgentType = opts.getDisputeAgentType();
                    String registrationKey = opts.getRegistrationKey();
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

    private static void printHelp(OptionParser parser, PrintStream stream) {
        try {
            stream.println("Haveno RPC Client");
            stream.println();
            stream.println("Usage: haveno-cli [options] <method> [params]");
            stream.println();
            parser.printHelpOn(stream);
            stream.println();
            String rowFormat = "%-25s%-52s%s%n";
            stream.format(rowFormat, "Method", "Params", "Description");
            stream.format(rowFormat, "------", "------", "-----------");
            stream.format(rowFormat, getversion.name(), "", "Get server version");
            stream.println();
            stream.format(rowFormat, getbalance.name(), "[--currency-code=<xmr>]", "Get server wallet balances");
            stream.println();
            stream.format(rowFormat, getaddressbalance.name(), "--address=<xmr-address>", "Get server wallet address balance");
            stream.println();
            stream.format(rowFormat, getxmrprice.name(), "--currency-code=<currency-code>", "Get current market xmr price");
            stream.println();
            stream.format(rowFormat, getfundingaddresses.name(), "", "Get XMR funding addresses");
            stream.println();
            stream.format(rowFormat, getunusedxmraddress.name(), "", "Get unused XMR address");
            stream.println();
            stream.format(rowFormat, getxmrseed.name(), "", "Get XMR seed");
            stream.println();
            stream.format(rowFormat, getxmrprimaryaddress.name(), "", "Get XMR primary address");
            stream.println();
            stream.format(rowFormat, getxmrnewsubaddress.name(), "", "Get new XMR subaddress");
            stream.println();
            stream.format(rowFormat, getxmrtxs.name(), "", "Get XMR transactions");
            stream.println();
            stream.format(rowFormat, createxmrtx.name(), "--destinations=<destinations>", "Create XMR transaction");
            stream.println();
            stream.format(rowFormat, relayxmrtx.name(), "--metadata=<metadata>", "Relay XMR transaction");
            stream.println();
            stream.format(rowFormat, createoffer.name(), "--payment-account=<payment-account-id> \\", "Create and place an offer");
            stream.format(rowFormat, "", "--direction=<buy|sell> \\", "");
            stream.format(rowFormat, "", "--currency-code=<currency-code> \\", "");
            stream.format(rowFormat, "", "--amount=<xmr-amount> \\", "");
            stream.format(rowFormat, "", "[--min-amount=<min-xmr-amount>] \\", "");
            stream.format(rowFormat, "", "--fixed-price=<price> | --market-price-margin=<percent> \\", "");
            stream.format(rowFormat, "", "--security-deposit=<percent>", "");
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
            stream.format(rowFormat, withdrawfunds.name(), "--trade-id=<trade-id> --address=<xmr-address> \\", "Withdraw received trade funds to external wallet address");
            stream.format(rowFormat, "", "[--memo=<\"memo\">]", "");
            stream.println();
            stream.format(rowFormat, getpaymentmethods.name(), "", "Get list of supported payment account method ids");
            stream.println();
            stream.format(rowFormat, getpaymentacctform.name(), "--payment-method-id=<payment-method-id>", "Get a new payment account form");
            stream.println();
            stream.format(rowFormat, createpaymentacct.name(), "--payment-account-form=<path>", "Create a new payment account");
            stream.println();
            stream.format(rowFormat, createcryptopaymentacct.name(), "--account-name=<name> \\", "Create a new cryptocurrency payment account");
            stream.format(rowFormat, "", "--currency-code=<xmr> \\", "");
            stream.format(rowFormat, "", "--address=<xmr-address>", "");
            stream.format(rowFormat, "", "--trade-instant=<true|false>", "");
            stream.println();
            stream.format(rowFormat, getpaymentaccts.name(), "", "Get user payment accounts");
            stream.println();
            stream.format(rowFormat, lockwallet.name(), "", "Remove wallet password from memory, locking the wallet");
            stream.println();
            stream.format(rowFormat, unlockwallet.name(), "--wallet-password=<password> --timeout=<seconds>", "Store wallet password in memory for timeout seconds");
            stream.println();
            stream.format(rowFormat, setwalletpassword.name(), "--wallet-password=<password> \\", "Encrypt wallet with password, or set new password on encrypted wallet");
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
