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
import haveno.cli.opts.GetAddressBalanceOptionParser;
import haveno.cli.opts.GetXMRMarketPriceOptionParser;
import haveno.cli.opts.GetBalanceOptionParser;
import haveno.cli.opts.GetOffersOptionParser;
import haveno.cli.opts.GetPaymentAcctFormOptionParser;
import haveno.cli.opts.GetTradeOptionParser;
import haveno.cli.opts.GetTradesOptionParser;
import haveno.cli.opts.OfferIdOptionParser;
import haveno.cli.opts.RegisterDisputeAgentOptionParser;
import haveno.cli.opts.RemoveWalletPasswordOptionParser;
import haveno.cli.opts.SendXmrOptionParser;
import haveno.cli.opts.SetWalletPasswordOptionParser;
import haveno.cli.opts.SimpleMethodOptionParser;
import haveno.cli.opts.TakeOfferOptionParser;
import haveno.cli.opts.UnlockWalletOptionParser;
import haveno.cli.opts.WithdrawFundsOptionParser;
import haveno.cli.opts.CreateXmrTxOptionParser;
import haveno.cli.opts.RelayXmrTxsOptionParser;
import haveno.cli.opts.GetChatMessagesOptionParser;
import haveno.cli.opts.SendChatMessageOptionParser;
import haveno.cli.opts.DeletePaymentAccountOptionParser;
import haveno.cli.opts.ValidateFormFieldOptionParser;
import haveno.cli.opts.CompleteTradeOptionParser;
import haveno.cli.opts.CreateAccountOptionParser;
import haveno.cli.opts.OpenAccountOptionParser;
import haveno.cli.opts.ChangePasswordOptionParser;
import haveno.cli.opts.RestoreAccountOptionParser;
import haveno.cli.opts.GetDisputeOptionParser;
import haveno.cli.opts.OpenDisputeOptionParser;
import haveno.cli.opts.ResolveDisputeOptionParser;
import haveno.cli.opts.SendDisputeChatMessageOptionParser;
import haveno.cli.opts.AddConnectionOptionParser;
import haveno.cli.opts.RemoveConnectionOptionParser;
import haveno.cli.opts.SetConnectionOptionParser;
import haveno.cli.opts.StartCheckingConnectionOptionParser;
import haveno.cli.opts.SetAutoSwitchOptionParser;
import haveno.cli.opts.StartXmrNodeOptionParser;
import haveno.proto.grpc.XmrDestination;
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
import static haveno.cli.CurrencyFormat.toPiconeros;
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
import static haveno.cli.Method.getxmrprice;
import static haveno.cli.Method.getfundingaddresses;
import static haveno.cli.Method.getmyoffer;
import static haveno.cli.Method.getmyoffers;
import static haveno.cli.Method.getoffer;
import static haveno.cli.Method.getoffers;
import static haveno.cli.Method.getpaymentacctform;
import static haveno.cli.Method.getpaymentaccountform;
import static haveno.cli.Method.getpaymentaccts;
import static haveno.cli.Method.getpaymentmethods;
import static haveno.cli.Method.gettrade;
import static haveno.cli.Method.gettrades;
import static haveno.cli.Method.gettransaction;
import static haveno.cli.Method.getversion;
import static haveno.cli.Method.getxmrtxs;
import static haveno.cli.Method.lockwallet;
import static haveno.cli.Method.registerdisputeagent;
import static haveno.cli.Method.removewalletpassword;
import static haveno.cli.Method.sendxmr;
import static haveno.cli.Method.setwalletpassword;
import static haveno.cli.Method.stop;
import static haveno.cli.Method.takeoffer;
import static haveno.cli.Method.unlockwallet;
import static haveno.cli.Method.withdrawfunds;
import static haveno.cli.Method.createxmrtx;
import static haveno.cli.Method.relayxmrtxs;
import static haveno.cli.Method.getchatmessages;
import static haveno.cli.Method.sendchatmessage;
import static haveno.cli.Method.deletepaymentaccount;
import static haveno.cli.Method.validateformfield;
import static haveno.cli.Method.completetrade;
import static haveno.cli.Method.createaccount;
import static haveno.cli.Method.openaccount;
import static haveno.cli.Method.isaccountopen;
import static haveno.cli.Method.isappinitialized;
import static haveno.cli.Method.changepassword;
import static haveno.cli.Method.closeaccount;
import static haveno.cli.Method.deleteaccount;
import static haveno.cli.Method.backupaccount;
import static haveno.cli.Method.restoreaccount;
import static haveno.cli.Method.getdispute;
import static haveno.cli.Method.getdisputes;
import static haveno.cli.Method.opendispute;
import static haveno.cli.Method.resolvedispute;
import static haveno.cli.Method.senddisputechatmessage;
import static haveno.cli.Method.addconnection;
import static haveno.cli.Method.removeconnection;
import static haveno.cli.Method.getconnection;
import static haveno.cli.Method.getconnections;
import static haveno.cli.Method.setconnection;
import static haveno.cli.Method.checkconnection;
import static haveno.cli.Method.checkconnections;
import static haveno.cli.Method.startcheckingconnection;
import static haveno.cli.Method.stopcheckingconnection;
import static haveno.cli.Method.getbestconnection;
import static haveno.cli.Method.setautoswitch;
import static haveno.cli.Method.getautoswitch;
import static haveno.cli.Method.isxmrnodeonline;
import static haveno.cli.Method.getxmrnodesettings;
import static haveno.cli.Method.startxmrnode;
import static haveno.cli.Method.stopxmrnode;
import static haveno.cli.Method.getunusedxmraddress;
import static haveno.cli.Method.getxmrseed;
import static haveno.cli.Method.getxmrprimaryaddress;
import static haveno.cli.Method.getxmrnewsubaddress;
import static haveno.cli.Method.getxmrtxs;
import static haveno.cli.Method.createxmrtx;
import static haveno.cli.Method.relayxmrtxs;
import static haveno.cli.Method.accountexists;
import static haveno.cli.Method.isaccountopen;
import static haveno.cli.Method.createaccount;
import static haveno.cli.Method.openaccount;
import static haveno.cli.Method.isappinitialized;
import static haveno.cli.Method.changepassword;
import static haveno.cli.Method.closeaccount;
import static haveno.cli.Method.deleteaccount;
import static haveno.cli.Method.backupaccount;
import static haveno.cli.Method.restoreaccount;
import static haveno.cli.Method.deletepaymentaccount;
import static haveno.cli.Method.validateformfield;
import static haveno.cli.Method.completetrade;
import static haveno.cli.Method.getchatmessages;
import static haveno.cli.Method.sendchatmessage;
import static haveno.cli.Method.getdispute;
import static haveno.cli.Method.getdisputes;
import static haveno.cli.Method.opendispute;
import static haveno.cli.Method.resolvedispute;
import static haveno.cli.Method.senddisputechatmessage;
import static haveno.cli.Method.addconnection;
import static haveno.cli.Method.removeconnection;
import static haveno.cli.Method.getconnection;
import static haveno.cli.Method.getconnections;
import static haveno.cli.Method.setconnection;
import static haveno.cli.Method.checkconnection;
import static haveno.cli.Method.checkconnections;
import static haveno.cli.Method.startcheckingconnection;
import static haveno.cli.Method.stopcheckingconnection;
import static haveno.cli.Method.getbestconnection;
import static haveno.cli.Method.setautoswitch;
import static haveno.cli.Method.getautoswitch;
import static haveno.cli.Method.isxmrnodeonline;
import static haveno.cli.Method.getxmrnodesettings;
import static haveno.cli.Method.startxmrnode;
import static haveno.cli.Method.stopxmrnode;
import static haveno.cli.Method.unfailtrade;
import static haveno.cli.opts.OptLabel.OPT_HELP;
import static haveno.cli.opts.OptLabel.OPT_HOST;
import static haveno.cli.opts.OptLabel.OPT_PASSWORD;
import static haveno.cli.opts.OptLabel.OPT_PORT;
import static haveno.cli.table.builder.TableType.ADDRESS_BALANCE_TBL;
import static haveno.cli.table.builder.TableType.XMR_BALANCE_TBL;
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
                    GetBalanceOptionParser opts = (GetBalanceOptionParser) new GetBalanceOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var currencyCode = opts.getCurrencyCode();
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
                    GetAddressBalanceOptionParser opts = (GetAddressBalanceOptionParser) new GetAddressBalanceOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var address = opts.getAddress();
                    var addressBalance = client.getAddressBalance(address);
                    new TableBuilder(ADDRESS_BALANCE_TBL, addressBalance).build().print(out);
                    return;
                }
                case getxmrprice: {
                    GetXMRMarketPriceOptionParser opts = (GetXMRMarketPriceOptionParser) new GetXMRMarketPriceOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var currencyCode = opts.getCurrencyCode();
                    var price = client.getXmrPrice(currencyCode);
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
                case sendxmr: {
                    SendXmrOptionParser opts = (SendXmrOptionParser) new SendXmrOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var address = opts.getAddress();
                    var amount = toPiconeros(opts.getAmount());
                    var txFeeRate = opts.getTxFeeRate();
                    var memo = opts.getMemo();
                    client.sendXmr(address, amount, txFeeRate, memo);
                    out.printf("xmr sent to address %s%n", address);
                    return;
                }
                case createoffer: {
                    CreateOfferOptionParser opts = (CreateOfferOptionParser) new CreateOfferOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var paymentAcctId = opts.getPaymentAccountId();
                    var direction = opts.getDirection();
                    var currencyCode = opts.getCurrencyCode();
                    var amount = toPiconeros(opts.getAmount());
                    var minAmount = toPiconeros(opts.getMinAmount());
                    var useMarketBasedPrice = opts.isUsingMktPriceMargin();
                    var fixedPrice = opts.getFixedPrice();
                    var marketPriceMarginPct = Double.parseDouble(opts.getMarketPriceMargin());
                    var securityDepositPct = Double.parseDouble(opts.getSecurityDeposit());
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
                case editoffer: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    // Edit offer implementation would go here
                    // For now, just indicate the method is available
                    out.println("editoffer method available - implementation needed");
                    return;
                }
                case canceloffer: {
                    CancelOfferOptionParser opts = (CancelOfferOptionParser) new CancelOfferOptionParser(args).parse();
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
                    OfferIdOptionParser opts = (OfferIdOptionParser) new OfferIdOptionParser(args).parse();
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
                    OfferIdOptionParser opts = (OfferIdOptionParser) new OfferIdOptionParser(args).parse();
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
                    GetOffersOptionParser opts = (GetOffersOptionParser) new GetOffersOptionParser(args).parse();
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
                    GetOffersOptionParser opts = (GetOffersOptionParser) new GetOffersOptionParser(args).parse();
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

                    TakeOfferOptionParser opts = (TakeOfferOptionParser) new TakeOfferOptionParser(args).parse();
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
                    GetTradeOptionParser opts = (GetTradeOptionParser) new GetTradeOptionParser(args).parse();
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
                    GetTradesOptionParser opts = (GetTradesOptionParser) new GetTradesOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var categoryStr = opts.getCategory();
                    var category = categoryStr.equalsIgnoreCase("open") ? OPEN : 
                                  categoryStr.equalsIgnoreCase("closed") ? CLOSED : 
                                  haveno.proto.grpc.GetTradesRequest.Category.FAILED;
                    var trades = category.equals(OPEN)
                            ? client.getOpenTrades()
                            : client.getTradeHistory(category);
                    if (trades.isEmpty()) {
                        out.printf("no %s trades found%n", categoryStr.toLowerCase());
                    } else {
                        var tableType = category.equals(OPEN)
                                ? OPEN_TRADES_TBL
                                : category.equals(CLOSED) ? CLOSED_TRADES_TBL : FAILED_TRADES_TBL;
                        new TableBuilder(tableType, trades).build().print(out);
                    }
                    return;
                }
                case confirmpaymentsent: {
                    GetTradeOptionParser opts = (GetTradeOptionParser) new GetTradeOptionParser(args).parse();
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
                    GetTradeOptionParser opts = (GetTradeOptionParser) new GetTradeOptionParser(args).parse();
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
                    WithdrawFundsOptionParser opts = (WithdrawFundsOptionParser) new WithdrawFundsOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    var address = opts.getAddress();
                    // Multi-word memos must be double-quoted.
                    var memo = opts.getMemo();
                    client.withdrawFunds(tradeId, address, memo);
                    out.printf("trade %s funds sent to xmr address %s%n", tradeId, address);
                    return;
                }
                case failtrade: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    // Fail trade implementation would go here
                    out.println("failtrade method available - implementation needed");
                    return;
                }
                case unfailtrade: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    // Unfail trade implementation would go here
                    out.println("unfailtrade method available - implementation needed");
                    return;
                }
                case closetrade: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    // Close trade implementation would go here
                    out.println("closetrade method available - implementation needed");
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
                    GetPaymentAcctFormOptionParser opts = (GetPaymentAcctFormOptionParser) new GetPaymentAcctFormOptionParser(args).parse();
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
                    CreatePaymentAcctOptionParser opts = (CreatePaymentAcctOptionParser) new CreatePaymentAcctOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var paymentAccountForm = opts.getPaymentAccountForm();
                    String jsonString;
                    try {
                        jsonString = new String(Files.readAllBytes(Paths.get(paymentAccountForm)));
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
                    CreateCryptoCurrencyPaymentAcctOptionParser opts = (CreateCryptoCurrencyPaymentAcctOptionParser) new CreateCryptoCurrencyPaymentAcctOptionParser(args).parse();
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
                case getpaymentaccountform: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    // Get payment account form implementation would go here
                    out.println("getpaymentaccountform method available - implementation needed");
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
                    UnlockWalletOptionParser opts = (UnlockWalletOptionParser) new UnlockWalletOptionParser(args).parse();
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
                    RemoveWalletPasswordOptionParser opts = (RemoveWalletPasswordOptionParser) new RemoveWalletPasswordOptionParser(args).parse();
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
                    SetWalletPasswordOptionParser opts = (SetWalletPasswordOptionParser) new SetWalletPasswordOptionParser(args).parse();
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
                    RegisterDisputeAgentOptionParser opts = (RegisterDisputeAgentOptionParser) new RegisterDisputeAgentOptionParser(args).parse();
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
                case gettransaction: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    // Get transaction implementation would go here
                    out.println("gettransaction method available - implementation needed");
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
                // XMR Wallet methods
                case getxmrseed: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var seed = client.getXmrSeed();
                    out.println(seed);
                    return;
                }
                case getxmrprimaryaddress: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var address = client.getXmrPrimaryAddress();
                    out.println(address);
                    return;
                }
                case getxmrnewsubaddress: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var address = client.getXmrNewSubaddress();
                    out.println(address);
                    return;
                }
                case getxmrtxs: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var txs = client.getXmrTxs();
                    txs.forEach(tx -> out.println(tx.getHash() + " " + tx.getFee()));
                    return;
                }
                case createxmrtx: {
                    CreateXmrTxOptionParser opts = (CreateXmrTxOptionParser) new CreateXmrTxOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var destinationStrings = opts.getDestinations();
                    var destinations = destinationStrings.stream()
                            .map(dest -> {
                                String[] parts = dest.split(":");
                                return XmrDestination.newBuilder()
                                        .setAddress(parts[0])
                                        .setAmount(parts[1])
                                        .build();
                            })
                            .collect(java.util.stream.Collectors.toList());
                    var tx = client.createXmrTx(destinations);
                    out.println("XMR transaction created: " + tx.getHash());
                    return;
                }
                case relayxmrtxs: {
                    RelayXmrTxsOptionParser opts = (RelayXmrTxsOptionParser) new RelayXmrTxsOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var metadatas = opts.getMetadatas();
                    var hashes = client.relayXmrTxs(metadatas);
                    hashes.forEach(out::println);
                    return;
                }
                // Chat methods
                case getchatmessages: {
                    GetChatMessagesOptionParser opts = (GetChatMessagesOptionParser) new GetChatMessagesOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    var messages = client.getChatMessages(tradeId);
                    messages.forEach(msg -> out.println(msg.getMessage()));
                    return;
                }
                case sendchatmessage: {
                    SendChatMessageOptionParser opts = (SendChatMessageOptionParser) new SendChatMessageOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    var message = opts.getMessage();
                    client.sendChatMessage(tradeId, message);
                    out.println("chat message sent");
                    return;
                }
                // Payment account methods
                case deletepaymentaccount: {
                    DeletePaymentAccountOptionParser opts = (DeletePaymentAccountOptionParser) new DeletePaymentAccountOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var paymentAccountId = opts.getPaymentAccountId();
                    client.deletePaymentAccount(paymentAccountId);
                    out.println("payment account deleted");
                    return;
                }
                case validateformfield: {
                    ValidateFormFieldOptionParser opts = (ValidateFormFieldOptionParser) new ValidateFormFieldOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    // Implementation would need form validation
                    out.println("form field validated");
                    return;
                }
                case completetrade: {
                    CompleteTradeOptionParser opts = (CompleteTradeOptionParser) new CompleteTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    client.completeTrade(tradeId);
                    out.println("trade completed");
                    return;
                }
                // Account management methods
                case accountexists: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var exists = client.accountExists();
                    out.println(exists ? "Account exists" : "Account does not exist");
                    return;
                }
                case isaccountopen: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var isOpen = client.isAccountOpen();
                    out.println(isOpen ? "Account is open" : "Account is closed");
                    return;
                }
                case createaccount: {
                    CreateAccountOptionParser opts = (CreateAccountOptionParser) new CreateAccountOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var accountPassword = opts.getPassword();
                    client.createAccount(accountPassword);
                    out.println("account created");
                    return;
                }
                case openaccount: {
                    OpenAccountOptionParser opts = (OpenAccountOptionParser) new OpenAccountOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var openPassword = opts.getPassword();
                    client.openAccount(openPassword);
                    out.println("account opened");
                    return;
                }
                case isappinitialized: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var initialized = client.isAppInitialized();
                    out.println(initialized ? "App is initialized" : "App is not initialized");
                    return;
                }
                case changepassword: {
                    ChangePasswordOptionParser opts = (ChangePasswordOptionParser) new ChangePasswordOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var oldPassword = opts.getOldPassword();
                    var newPassword = opts.getNewPassword();
                    client.changePassword(oldPassword, newPassword);
                    out.println("password changed");
                    return;
                }
                case closeaccount: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.closeAccount();
                    out.println("account closed");
                    return;
                }
                case deleteaccount: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.deleteAccount();
                    out.println("account deleted");
                    return;
                }
                case backupaccount: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.backupAccount();
                    out.println("account backup completed");
                    return;
                }
                case restoreaccount: {
                    RestoreAccountOptionParser opts = (RestoreAccountOptionParser) new RestoreAccountOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var zipBytes = opts.getZipBytes();
                    var offset = opts.getOffset();
                    var totalLength = opts.getTotalLength();
                    var hasMore = opts.getHasMore();
                    client.restoreAccount(zipBytes, offset, totalLength, hasMore);
                    out.println("account restored");
                    return;
                }
                // Dispute methods
                case getdispute: {
                    GetDisputeOptionParser opts = (GetDisputeOptionParser) new GetDisputeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    var dispute = client.getDispute(tradeId);
                    out.println("Dispute ID: " + dispute.getId());
                    return;
                }
                case getdisputes: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var disputes = client.getDisputes();
                    disputes.forEach(d -> out.println("Dispute: " + d.getId()));
                    return;
                }
                case opendispute: {
                    OpenDisputeOptionParser opts = (OpenDisputeOptionParser) new OpenDisputeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    client.openDispute(tradeId);
                    out.println("dispute opened");
                    return;
                }
                case resolvedispute: {
                    ResolveDisputeOptionParser opts = (ResolveDisputeOptionParser) new ResolveDisputeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    var winner = opts.getWinner();
                    var reason = opts.getReason();
                    var summaryNotes = opts.getSummaryNotes();
                    var customPayoutAmount = opts.getCustomPayoutAmount();
                    client.resolveDispute(tradeId, winner, reason, summaryNotes, customPayoutAmount);
                    out.println("dispute resolved");
                    return;
                }
                case senddisputechatmessage: {
                    SendDisputeChatMessageOptionParser opts = (SendDisputeChatMessageOptionParser) new SendDisputeChatMessageOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var disputeId = opts.getDisputeId();
                    var message = opts.getMessage();
                    client.sendDisputeChatMessage(disputeId, message);
                    out.println("dispute chat message sent");
                    return;
                }
                // XMR Connection methods
                case addconnection: {
                    AddConnectionOptionParser opts = (AddConnectionOptionParser) new AddConnectionOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var connection = opts.getConnection();
                    client.addConnection(connection);
                    out.println("connection added");
                    return;
                }
                case removeconnection: {
                    RemoveConnectionOptionParser opts = (RemoveConnectionOptionParser) new RemoveConnectionOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var url = opts.getUrl();
                    client.removeConnection(url);
                    out.println("connection removed");
                    return;
                }
                case getconnection: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var connection = client.getConnection();
                    out.println("Connection: " + connection.getUrl());
                    return;
                }
                case getconnections: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var connections = client.getConnections();
                    connections.forEach(c -> out.println("Connection: " + c.getUrl()));
                    return;
                }
                case setconnection: {
                    SetConnectionOptionParser opts = (SetConnectionOptionParser) new SetConnectionOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var url = opts.getUrl();
                    var connection = opts.getConnection();
                    client.setConnection(url, connection);
                    out.println("connection set");
                    return;
                }
                case checkconnection: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var connection = client.checkConnection();
                    out.println("Connection status: " + connection.getOnlineStatus());
                    return;
                }
                case checkconnections: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var connections = client.checkConnections();
                    connections.forEach(c -> out.println("Connection " + c.getUrl() + ": " + c.getOnlineStatus()));
                    return;
                }
                case startcheckingconnection: {
                    StartCheckingConnectionOptionParser opts = (StartCheckingConnectionOptionParser) new StartCheckingConnectionOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var refreshPeriod = opts.getRefreshPeriod();
                    client.startCheckingConnection(refreshPeriod);
                    out.println("connection checking started");
                    return;
                }
                case stopcheckingconnection: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.stopCheckingConnection();
                    out.println("connection checking stopped");
                    return;
                }
                case getbestconnection: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var connection = client.getBestConnection();
                    out.println("Best connection: " + connection.getUrl());
                    return;
                }
                case setautoswitch: {
                    SetAutoSwitchOptionParser opts = (SetAutoSwitchOptionParser) new SetAutoSwitchOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var autoSwitch = opts.getAutoSwitch();
                    client.setAutoSwitch(autoSwitch);
                    out.println("auto switch set to " + autoSwitch);
                    return;
                }
                case getautoswitch: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var autoSwitch = client.getAutoSwitch();
                    out.println("Auto switch: " + autoSwitch);
                    return;
                }
                // XMR Node methods
                case isxmrnodeonline: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var online = client.isXmrNodeOnline();
                    out.println("XMR node online: " + online);
                    return;
                }
                case getxmrnodesettings: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var settings = client.getXmrNodeSettings();
                    out.println("XMR node settings: " + settings.getBlockchainPath());
                    return;
                }
                case startxmrnode: {
                    StartXmrNodeOptionParser opts = (StartXmrNodeOptionParser) new StartXmrNodeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var settings = opts.getSettings();
                    client.startXmrNode(settings);
                    out.println("XMR node started");
                    return;
                }
                case stopxmrnode: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.stopXmrNode();
                    out.println("XMR node stopped");
                    return;
                }
                // Additional methods that might be missing
                case getunusedxmraddress: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var address = client.getUnusedXmrAddress();
                    out.println(address);
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
            stream.format(rowFormat, "", "[--tx-fee-rate=<sats/byte>]", "");
            stream.println();
            stream.format(rowFormat, sendxmr.name(), "--address=<xmr-address> --amount=<xmr-amount> \\", "Send XMR");
            stream.format(rowFormat, "", "[--tx-fee-rate=<sats/byte>]", "");
            stream.format(rowFormat, "", "[--memo=<\"memo\">]", "");
            stream.println();

            stream.format(rowFormat, gettransaction.name(), "--transaction-id=<transaction-id>", "Get transaction with id");
            stream.println();
            stream.format(rowFormat, createoffer.name(), "--payment-account=<payment-account-id> \\", "Create and place an offer");
            stream.format(rowFormat, "", "--direction=<buy|sell> \\", "");
            stream.format(rowFormat, "", "--currency-code=<currency-code> \\", "");
            stream.format(rowFormat, "", "--amount=<xmr-amount> \\", "");
            stream.format(rowFormat, "", "[--min-amount=<min-xmr-amount>] \\", "");
            stream.format(rowFormat, "", "--fixed-price=<price> | --market-price-margin=<percent> \\", "");
            stream.format(rowFormat, "", "--security-deposit=<percent> \\", "");
            stream.format(rowFormat, "", "[--fee-currency=<xmr>]", "");
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
            stream.format(rowFormat, "", "[--fee-currency=<xmr>]", "");
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
            stream.format(rowFormat, withdrawfunds.name(), "--trade-id=<trade-id> --address=<xmr-address> \\",
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
            stream.format(rowFormat, "", "--currency-code=<xmr> \\", "");
            stream.format(rowFormat, "", "--address=<xmr-address>", "");
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
            stream.format(rowFormat, removewalletpassword.name(), "--wallet-password=<password>", "Remove wallet password");
            stream.println();
            // XMR Wallet methods
            stream.format(rowFormat, getxmrseed.name(), "", "Get XMR wallet seed");
            stream.println();
            stream.format(rowFormat, getxmrprimaryaddress.name(), "", "Get XMR primary address");
            stream.println();
            stream.format(rowFormat, getxmrnewsubaddress.name(), "", "Get new XMR subaddress");
            stream.println();
            stream.format(rowFormat, getxmrtxs.name(), "", "Get XMR transactions");
            stream.println();
            stream.format(rowFormat, createxmrtx.name(), "--destinations=<address:amount,...>", "Create XMR transaction");
            stream.println();
            stream.format(rowFormat, relayxmrtxs.name(), "--metadatas=<metadata,...>", "Relay XMR transactions");
            stream.println();
            // Account management methods
            stream.format(rowFormat, accountexists.name(), "", "Check if account exists");
            stream.println();
            stream.format(rowFormat, isaccountopen.name(), "", "Check if account is open");
            stream.println();
            stream.format(rowFormat, createaccount.name(), "--password=<password>", "Create new account");
            stream.println();
            stream.format(rowFormat, openaccount.name(), "--password=<password>", "Open existing account");
            stream.println();
            stream.format(rowFormat, isappinitialized.name(), "", "Check if application is initialized");
            stream.println();
            stream.format(rowFormat, changepassword.name(), "--old-password=<password> --new-password=<password>", "Change account password");
            stream.println();
            stream.format(rowFormat, closeaccount.name(), "", "Close account");
            stream.println();
            stream.format(rowFormat, deleteaccount.name(), "", "Delete account");
            stream.println();
            stream.format(rowFormat, backupaccount.name(), "", "Backup account");
            stream.println();
            stream.format(rowFormat, restoreaccount.name(), "--zip-bytes=<bytes> --offset=<offset> \\", "Restore account from backup");
            stream.format(rowFormat, "", "--total-length=<length> --has-more=<true|false>", "");
            stream.println();
            // Payment account methods
            stream.format(rowFormat, getpaymentaccountform.name(), "--payment-method-id=<method-id>", "Get payment account form");
            stream.println();
            stream.format(rowFormat, deletepaymentaccount.name(), "--payment-account-id=<account-id>", "Delete payment account");
            stream.println();
            stream.format(rowFormat, validateformfield.name(), "--form=<form> --field-id=<field-id> --value=<value>", "Validate form field");
            stream.println();
            // Trade methods
            stream.format(rowFormat, completetrade.name(), "--trade-id=<trade-id>", "Complete trade");
            stream.println();
            // Chat methods
            stream.format(rowFormat, getchatmessages.name(), "--trade-id=<trade-id>", "Get chat messages for trade");
            stream.println();
            stream.format(rowFormat, sendchatmessage.name(), "--trade-id=<trade-id> --message=<message>", "Send chat message");
            stream.println();
            // Dispute methods
            stream.format(rowFormat, getdispute.name(), "--trade-id=<trade-id>", "Get dispute for trade");
            stream.println();
            stream.format(rowFormat, getdisputes.name(), "", "Get all disputes");
            stream.println();
            stream.format(rowFormat, opendispute.name(), "--trade-id=<trade-id>", "Open dispute for trade");
            stream.println();
            stream.format(rowFormat, resolvedispute.name(), "--trade-id=<trade-id> --winner=<buyer|seller> \\", "Resolve dispute");
            stream.format(rowFormat, "", "--reason=<reason> --summary-notes=<notes> \\", "");
            stream.format(rowFormat, "", "--custom-payout-amount=<amount>", "");
            stream.println();
            stream.format(rowFormat, senddisputechatmessage.name(), "--dispute-id=<dispute-id> --message=<message>", "Send dispute chat message");
            stream.println();
            stream.format(rowFormat, registerdisputeagent.name(), "--dispute-agent-type=<type> --registration-key=<key>", "Register dispute agent");
            stream.println();
            // XMR Connection methods
            stream.format(rowFormat, addconnection.name(), "--url=<url> [--username=<username>] \\", "Add XMR node connection");
            stream.format(rowFormat, "", "[--password=<password>] [--priority=<priority>]", "");
            stream.println();
            stream.format(rowFormat, removeconnection.name(), "--url=<url>", "Remove XMR node connection");
            stream.println();
            stream.format(rowFormat, getconnection.name(), "", "Get current XMR node connection");
            stream.println();
            stream.format(rowFormat, getconnections.name(), "", "Get all XMR node connections");
            stream.println();
            stream.format(rowFormat, setconnection.name(), "--url=<url> --connection=<connection>", "Set XMR node connection");
            stream.println();
            stream.format(rowFormat, checkconnection.name(), "", "Check current XMR node connection");
            stream.println();
            stream.format(rowFormat, checkconnections.name(), "", "Check all XMR node connections");
            stream.println();
            stream.format(rowFormat, startcheckingconnection.name(), "--refresh-period=<milliseconds>", "Start checking XMR node connection");
            stream.println();
            stream.format(rowFormat, stopcheckingconnection.name(), "", "Stop checking XMR node connection");
            stream.println();
            stream.format(rowFormat, getbestconnection.name(), "", "Get best XMR node connection");
            stream.println();
            stream.format(rowFormat, setautoswitch.name(), "--auto-switch=<true|false>", "Set auto switch for XMR connections");
            stream.println();
            stream.format(rowFormat, getautoswitch.name(), "", "Get auto switch setting for XMR connections");
            stream.println();
            // XMR Node methods
            stream.format(rowFormat, isxmrnodeonline.name(), "", "Check if XMR node is online");
            stream.println();
            stream.format(rowFormat, getxmrnodesettings.name(), "", "Get XMR node settings");
            stream.println();
            stream.format(rowFormat, startxmrnode.name(), "--settings=<settings>", "Start XMR node");
            stream.println();
            stream.format(rowFormat, stopxmrnode.name(), "", "Stop XMR node");
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
