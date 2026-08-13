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

import haveno.cli.opts.AccountPasswordOptionParser;
import haveno.cli.opts.AddConnectionOptionParser;
import haveno.cli.opts.ArgumentList;
import haveno.cli.opts.BackupAccountOptionParser;
import haveno.cli.opts.CancelOfferOptionParser;
import haveno.cli.opts.ChangePasswordOptionParser;
import haveno.cli.opts.CreateCryptoCurrencyPaymentAcctOptionParser;
import haveno.cli.opts.CreateOfferOptionParser;
import haveno.cli.opts.CreatePaymentAcctOptionParser;
import haveno.cli.opts.CreateXmrSweepTxsOptionParser;
import haveno.cli.opts.CreateXmrTxOptionParser;
import haveno.cli.opts.CurrencyCodeOptionParser;
import haveno.cli.opts.EditOfferOptionParser;
import haveno.cli.opts.GetAddressBalanceOptionParser;
import haveno.cli.opts.GetBalanceOptionParser;
import haveno.cli.opts.GetOffersOptionParser;
import haveno.cli.opts.GetPaymentAcctFormOptionParser;
import haveno.cli.opts.GetTradeOptionParser;
import haveno.cli.opts.GetTradesOptionParser;
import haveno.cli.opts.OfferIdOptionParser;
import haveno.cli.opts.PaymentAccountIdOptionParser;
import haveno.cli.opts.RegisterDisputeAgentOptionParser;
import haveno.cli.opts.RelayXmrTxsOptionParser;
import haveno.cli.opts.ResolveDisputeOptionParser;
import haveno.cli.opts.RestoreAccountOptionParser;
import haveno.cli.opts.SendChatMessageOptionParser;
import haveno.cli.opts.SendDisputeChatMessageOptionParser;
import haveno.cli.opts.SendXmrOptionParser;
import haveno.cli.opts.SetAutoSwitchOptionParser;
import haveno.cli.opts.SimpleMethodOptionParser;
import haveno.cli.opts.StartXmrNodeOptionParser;
import haveno.cli.opts.TakeOfferOptionParser;
import haveno.cli.opts.UnregisterDisputeAgentOptionParser;
import haveno.cli.opts.UrlOptionParser;
import haveno.cli.opts.WithdrawFundsOptionParser;
import haveno.cli.table.builder.TableBuilder;
import haveno.proto.grpc.MarketDepthInfo;
import haveno.proto.grpc.MarketPriceInfo;
import haveno.proto.grpc.NotificationMessage;
import haveno.proto.grpc.OfferInfo;
import haveno.proto.grpc.UrlConnection;
import haveno.proto.grpc.XmrDestination;
import haveno.proto.grpc.XmrTx;
import io.grpc.StatusRuntimeException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import lombok.extern.slf4j.Slf4j;
import protobuf.ChatMessage;
import protobuf.Dispute;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static haveno.cli.CurrencyFormat.formatMarketPrice;
import static haveno.cli.CurrencyFormat.toPiconeros;
import static haveno.cli.Method.accountexists;
import static haveno.cli.Method.activateoffer;
import static haveno.cli.Method.addconnection;
import static haveno.cli.Method.backupaccount;
import static haveno.cli.Method.canceloffer;
import static haveno.cli.Method.changepassword;
import static haveno.cli.Method.checkconnection;
import static haveno.cli.Method.closeaccount;
import static haveno.cli.Method.completetrade;
import static haveno.cli.Method.confirmpaymentreceived;
import static haveno.cli.Method.confirmpaymentsent;
import static haveno.cli.Method.createaccount;
import static haveno.cli.Method.createcryptopaymentacct;
import static haveno.cli.Method.createoffer;
import static haveno.cli.Method.createpaymentacct;
import static haveno.cli.Method.createxmrsweeptxs;
import static haveno.cli.Method.createxmrtx;
import static haveno.cli.Method.deactivateoffer;
import static haveno.cli.Method.deleteaccount;
import static haveno.cli.Method.deletepaymentacct;
import static haveno.cli.Method.editoffer;
import static haveno.cli.Method.getaddressbalance;
import static haveno.cli.Method.getautoswitch;
import static haveno.cli.Method.getbalance;
import static haveno.cli.Method.getbestconnection;
import static haveno.cli.Method.getchatmessages;
import static haveno.cli.Method.getconnection;
import static haveno.cli.Method.getconnections;
import static haveno.cli.Method.getcryptopaymentmethods;
import static haveno.cli.Method.getdispute;
import static haveno.cli.Method.getdisputes;
import static haveno.cli.Method.getfundingaddresses;
import static haveno.cli.Method.getmarketdepth;
import static haveno.cli.Method.getmyoffer;
import static haveno.cli.Method.getmyoffers;
import static haveno.cli.Method.getoffer;
import static haveno.cli.Method.getoffers;
import static haveno.cli.Method.getpaymentacctform;
import static haveno.cli.Method.getpaymentaccts;
import static haveno.cli.Method.getpaymentmethods;
import static haveno.cli.Method.gettrade;
import static haveno.cli.Method.gettrades;
import static haveno.cli.Method.gettradestatistics;
import static haveno.cli.Method.getversion;
import static haveno.cli.Method.getwalletheight;
import static haveno.cli.Method.getxmrnewsubaddress;
import static haveno.cli.Method.getxmrnodesettings;
import static haveno.cli.Method.getxmrprice;
import static haveno.cli.Method.getxmrprices;
import static haveno.cli.Method.getxmrprimaryaddress;
import static haveno.cli.Method.getxmrseed;
import static haveno.cli.Method.getxmrtxs;
import static haveno.cli.Method.isaccountopen;
import static haveno.cli.Method.isappinitialized;
import static haveno.cli.Method.isxmrnodeonline;
import static haveno.cli.Method.lockwallet;
import static haveno.cli.Method.openaccount;
import static haveno.cli.Method.opendispute;
import static haveno.cli.Method.registerdisputeagent;
import static haveno.cli.Method.registernotificationlistener;
import static haveno.cli.Method.relayxmrtxs;
import static haveno.cli.Method.removeconnection;
import static haveno.cli.Method.removewalletpassword;
import static haveno.cli.Method.resolvedispute;
import static haveno.cli.Method.restoreaccount;
import static haveno.cli.Method.sendchatmessage;
import static haveno.cli.Method.senddisputechatmessage;
import static haveno.cli.Method.sendxmr;
import static haveno.cli.Method.setautoswitch;
import static haveno.cli.Method.setconnection;
import static haveno.cli.Method.setwalletpassword;
import static haveno.cli.Method.startxmrnode;
import static haveno.cli.Method.stop;
import static haveno.cli.Method.stopxmrnode;
import static haveno.cli.Method.takeoffer;
import static haveno.cli.Method.unlockwallet;
import static haveno.cli.Method.unregisterdisputeagent;
import static haveno.cli.Method.withdrawfunds;
import static haveno.cli.opts.OptLabel.OPT_HELP;
import static haveno.cli.opts.OptLabel.OPT_HOST;
import static haveno.cli.opts.OptLabel.OPT_PASSWORD;
import static haveno.cli.opts.OptLabel.OPT_PORT;
import static haveno.cli.table.builder.TableType.ADDRESS_BALANCE_TBL;
import static haveno.cli.table.builder.TableType.CLOSED_TRADES_TBL;
import static haveno.cli.table.builder.TableType.FAILED_TRADES_TBL;
import static haveno.cli.table.builder.TableType.OFFER_TBL;
import static haveno.cli.table.builder.TableType.OPEN_TRADES_TBL;
import static haveno.cli.table.builder.TableType.PAYMENT_ACCOUNT_TBL;
import static haveno.cli.table.builder.TableType.TRADE_DETAIL_TBL;
import static haveno.cli.table.builder.TableType.XMR_BALANCE_TBL;
import static haveno.cli.table.builder.TableType.XMR_TX_TBL;
import static haveno.proto.grpc.GetTradesRequest.Category.CLOSED;
import static haveno.proto.grpc.GetTradesRequest.Category.OPEN;
import static java.lang.String.format;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;
import static java.util.TimeZone.getTimeZone;

/**
 * A command-line client for the Haveno gRPC API.
 */
@Slf4j
public class CliMain {

    private static final int RESTORE_ACCOUNT_CHUNK_SIZE = 2 * 1024 * 1024;

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
                case accountexists: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    out.println(client.accountExists());
                    return;
                }
                case isaccountopen: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    out.println(client.isAccountOpen());
                    return;
                }
                case isappinitialized: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    out.println(client.isAppInitialized());
                    return;
                }
                case createaccount: {
                    var opts = new AccountPasswordOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.createAccount(opts.getAccountPassword());
                    out.println("account created");
                    return;
                }
                case openaccount: {
                    var opts = new AccountPasswordOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.openAccount(opts.getAccountPassword());
                    out.println("account opened");
                    return;
                }
                case changepassword: {
                    var opts = new ChangePasswordOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.changePassword(opts.getAccountPassword(), opts.getNewAccountPassword());
                    out.println("account password changed");
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
                    var opts = new BackupAccountOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var backupFile = opts.getBackupFile().isEmpty()
                            ? format("haveno-account-backup_%d.zip", new Date().getTime())
                            : opts.getBackupFile();
                    Path path = Paths.get(backupFile);
                    if (Files.exists(path))
                        throw new IllegalStateException(format("could not overwrite existing file '%s'", backupFile));

                    try (var outputStream = new FileOutputStream(path.toFile())) {
                        var backupBytes = client.backupAccount();
                        while (backupBytes.hasNext())
                            outputStream.write(backupBytes.next().getZipBytes().toByteArray());
                    } catch (IOException ex) {
                        throw new IllegalStateException(format("could not write backup file '%s'", backupFile), ex);
                    }
                    out.printf("account backup saved to %s%n", path.toAbsolutePath());
                    return;
                }
                case restoreaccount: {
                    var opts = new RestoreAccountOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    byte[] zipBytes;
                    try {
                        zipBytes = Files.readAllBytes(opts.getBackupFile());
                    } catch (IOException ex) {
                        throw new IllegalStateException(format("could not read %s", opts.getBackupFile()));
                    }
                    // Upload the zip in chunks small enough to fit in a grpc message.
                    for (long offset = 0; offset < zipBytes.length; offset += RESTORE_ACCOUNT_CHUNK_SIZE) {
                        int chunkSize = (int) Math.min(RESTORE_ACCOUNT_CHUNK_SIZE, zipBytes.length - offset);
                        byte[] chunk = new byte[chunkSize];
                        System.arraycopy(zipBytes, (int) offset, chunk, 0, chunkSize);
                        boolean hasMore = offset + chunkSize < zipBytes.length;
                        client.restoreAccount(chunk, offset, zipBytes.length, hasMore);
                    }
                    out.println("account restored, restart the server before using the account");
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
                    new TableBuilder(XMR_BALANCE_TBL, balances.getXmr()).build().print(out);
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
                case getfundingaddresses: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var fundingAddresses = client.getFundingAddresses();
                    new TableBuilder(ADDRESS_BALANCE_TBL, fundingAddresses).build().print(out);
                    return;
                }
                case getxmrseed: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    out.println(client.getXmrSeed());
                    return;
                }
                case getxmrprimaryaddress: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    out.println(client.getXmrPrimaryAddress());
                    return;
                }
                case getxmrnewsubaddress: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    out.println(client.getXmrNewSubaddress());
                    return;
                }
                case getxmrtxs: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var txs = client.getXmrTxs();
                    if (txs.isEmpty())
                        out.println("no xmr txs found");
                    else
                        new TableBuilder(XMR_TX_TBL, txs).build().print(out);

                    return;
                }
                case getwalletheight: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    out.println(client.getWalletHeight().getHeight());
                    return;
                }
                case sendxmr: {
                    var opts = new SendXmrOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var address = opts.getAddress();
                    var amount = toPiconeros(opts.getAmount());
                    var destination = XmrDestination.newBuilder()
                            .setAddress(address)
                            .setAmount(String.valueOf(amount))
                            .build();
                    var tx = client.createXmrTx(List.of(destination));
                    client.relayXmrTxs(List.of(tx.getMetadata()));
                    out.printf("%s xmr sent to %s in tx %s%n", opts.getAmount(), address, tx.getHash());
                    return;
                }
                case createxmrtx: {
                    var opts = new CreateXmrTxOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tx = client.createXmrTx(opts.getDestinations());
                    printXmrTxsWithMetadata(List.of(tx));
                    return;
                }
                case createxmrsweeptxs: {
                    var opts = new CreateXmrSweepTxsOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var txs = client.createXmrSweepTxs(opts.getAddress());
                    printXmrTxsWithMetadata(txs);
                    return;
                }
                case relayxmrtxs: {
                    var opts = new RelayXmrTxsOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var hashes = client.relayXmrTxs(opts.getMetadatas());
                    hashes.forEach(out::println);
                    return;
                }
                case getxmrprice: {
                    var opts = new CurrencyCodeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var currencyCode = opts.getCurrencyCode();
                    var price = client.getXmrPrice(currencyCode);
                    out.println(formatMarketPrice(price));
                    return;
                }
                case getxmrprices: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    printMarketPrices(client.getXmrPrices());
                    return;
                }
                case getmarketdepth: {
                    var opts = new CurrencyCodeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    printMarketDepth(client.getMarketDepth(opts.getCurrencyCode()));
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
                    var amount = toPiconeros(opts.getAmount());
                    var minAmount = toPiconeros(opts.getMinAmount());
                    var useMarketBasedPrice = opts.isUsingMktPriceMargin();
                    var fixedPrice = opts.getFixedPrice();
                    var marketPriceMarginPct = opts.getMktPriceMarginPct();
                    var securityDepositPct = opts.getSecurityDepositPct();
                    var triggerPrice = opts.getTriggerPrice();
                    var reserveExactAmount = opts.getReserveExactAmount();
                    var extraInfo = opts.getExtraInfo();
                    OfferInfo offer = client.createOffer(direction,
                            currencyCode,
                            amount,
                            minAmount,
                            useMarketBasedPrice,
                            fixedPrice,
                            marketPriceMarginPct,
                            securityDepositPct,
                            paymentAcctId,
                            triggerPrice,
                            reserveExactAmount,
                            extraInfo);
                    new TableBuilder(OFFER_TBL, offer).build().print(out);
                    return;
                }
                case editoffer: {
                    var opts = new EditOfferOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var offer = client.editOffer(opts.getOfferId(),
                            opts.getCurrencyCode(),
                            opts.getFixedPrice(),
                            opts.isUsingMktPriceMargin(),
                            opts.getMktPriceMarginPct(),
                            opts.getTriggerPrice(),
                            opts.getPaymentAccountId(),
                            opts.getExtraInfo());
                    new TableBuilder(OFFER_TBL, offer).build().print(out);
                    return;
                }
                case activateoffer: {
                    var opts = new OfferIdOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.activateOffer(opts.getOfferId());
                    out.println("offer activated");
                    return;
                }
                case deactivateoffer: {
                    var opts = new OfferIdOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.deactivateOffer(opts.getOfferId());
                    out.println("offer deactivated");
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
                    var amount = opts.getAmount();
                    var challenge = opts.getChallenge();
                    var trade = client.takeOffer(offerId, paymentAccountId, amount, challenge);
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
                    out.printf("trade %s payment sent message sent%n", tradeId);
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
                case completetrade: {
                    var opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    client.completeTrade(tradeId);
                    out.printf("trade %s completed%n", tradeId);
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
                    out.printf("trade %s funds sent to xmr address %s%n", tradeId, address);
                    return;
                }
                case getchatmessages: {
                    var opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var messages = client.getChatMessages(opts.getTradeId());
                    if (messages.isEmpty())
                        out.printf("no chat messages found for trade %s%n", opts.getTradeId());
                    else
                        printChatMessages(messages);

                    return;
                }
                case sendchatmessage: {
                    var opts = new SendChatMessageOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.sendChatMessage(opts.getTradeId(), opts.getMessage());
                    out.printf("trade %s chat message sent%n", opts.getTradeId());
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
                case getcryptopaymentmethods: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var paymentMethods = client.getCryptoPaymentMethods();
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
                case deletepaymentacct: {
                    var opts = new PaymentAccountIdOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var paymentAccountId = opts.getPaymentAccountId();
                    client.deletePaymentAccount(paymentAccountId);
                    out.printf("payment account %s deleted%n", paymentAccountId);
                    return;
                }
                case getdispute: {
                    var opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var dispute = client.getDispute(opts.getTradeId());
                    printDisputes(List.of(dispute));
                    printChatMessages(dispute.getChatMessageList());
                    return;
                }
                case getdisputes: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var disputes = client.getDisputes();
                    if (disputes.isEmpty())
                        out.println("no disputes found");
                    else
                        printDisputes(disputes);

                    return;
                }
                case opendispute: {
                    var opts = new GetTradeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeId = opts.getTradeId();
                    client.openDispute(tradeId);
                    out.printf("dispute opened for trade %s%n", tradeId);
                    return;
                }
                case resolvedispute: {
                    var opts = new ResolveDisputeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.resolveDispute(opts.getTradeId(),
                            opts.getWinner(),
                            opts.getReason(),
                            opts.getSummaryNotes(),
                            opts.getCustomPayoutAmount());
                    out.printf("dispute resolved for trade %s%n", opts.getTradeId());
                    return;
                }
                case senddisputechatmessage: {
                    var opts = new SendDisputeChatMessageOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.sendDisputeChatMessage(opts.getDisputeId(), opts.getMessage());
                    out.printf("dispute %s chat message sent%n", opts.getDisputeId());
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
                case unregisterdisputeagent: {
                    var opts = new UnregisterDisputeAgentOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var disputeAgentType = opts.getDisputeAgentType();
                    client.unregisterDisputeAgent(disputeAgentType);
                    out.println(disputeAgentType + " unregistered");
                    return;
                }
                case gettradestatistics: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var tradeStatistics = client.getTradeStatistics();
                    if (tradeStatistics.isEmpty())
                        out.println("no trade statistics found");
                    else
                        printTradeStatistics(tradeStatistics);

                    return;
                }
                case addconnection: {
                    var opts = new AddConnectionOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var connection = UrlConnection.newBuilder()
                            .setUrl(opts.getUrl())
                            .setUsername(opts.getConnectionUser())
                            .setPassword(opts.getConnectionPassword())
                            .setPriority(opts.getPriority())
                            .build();
                    client.addConnection(connection);
                    out.printf("connection %s added%n", opts.getUrl());
                    return;
                }
                case removeconnection: {
                    var opts = new UrlOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.removeConnection(opts.getUrl());
                    out.printf("connection %s removed%n", opts.getUrl());
                    return;
                }
                case getconnection: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    printConnections(List.of(client.getConnection()));
                    return;
                }
                case getconnections: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var connections = client.getConnections();
                    if (connections.isEmpty())
                        out.println("no connections found");
                    else
                        printConnections(connections);

                    return;
                }
                case setconnection: {
                    var opts = new UrlOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.setConnection(opts.getUrl());
                    out.printf("connection set to %s%n", opts.getUrl());
                    return;
                }
                case checkconnection: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    printConnections(List.of(client.checkConnection()));
                    return;
                }
                case getbestconnection: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    printConnections(List.of(client.getBestConnection()));
                    return;
                }
                case setautoswitch: {
                    var opts = new SetAutoSwitchOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var autoSwitch = opts.getAutoSwitch();
                    client.setAutoSwitch(autoSwitch);
                    out.printf("auto switch %s%n", autoSwitch ? "enabled" : "disabled");
                    return;
                }
                case getautoswitch: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    out.println(client.getAutoSwitch());
                    return;
                }
                case isxmrnodeonline: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    out.println(client.isXmrNodeOnline());
                    return;
                }
                case getxmrnodesettings: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    var settings = client.getXmrNodeSettings();
                    out.printf("blockchain path: %s%n", settings.getBlockchainPath());
                    out.printf("bootstrap url: %s%n", settings.getBootstrapUrl());
                    out.printf("startup flags: %s%n", String.join(" ", settings.getStartupFlagsList()));
                    out.printf("sync blockchain: %s%n", settings.getSyncBlockchain());
                    return;
                }
                case startxmrnode: {
                    var opts = new StartXmrNodeOptionParser(args).parse();
                    if (opts.isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.startXmrNode(opts.getXmrNodeSettings());
                    out.println("xmr node started");
                    return;
                }
                case stopxmrnode: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    client.stopXmrNode();
                    out.println("xmr node stopped");
                    return;
                }
                case registernotificationlistener: {
                    if (new SimpleMethodOptionParser(args).parse().isForHelp()) {
                        out.println(client.getMethodHelp(method));
                        return;
                    }
                    out.println("listening for notifications, press ctrl-c to quit");
                    var notifications = client.registerNotificationListener();
                    while (notifications.hasNext())
                        printNotification(notifications.next());

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

    private static final SimpleDateFormat DATE_FORMAT_ISO_8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private static String formatTimestamp(long timestamp) {
        DATE_FORMAT_ISO_8601.setTimeZone(getTimeZone("UTC"));
        return DATE_FORMAT_ISO_8601.format(new Date(timestamp));
    }

    private static void printXmrTxsWithMetadata(List<XmrTx> txs) {
        new TableBuilder(XMR_TX_TBL, txs).build().print(out);
        out.println("Relay with relayxmrtxs --metadatas=<comma delimited metadata list below>");
        txs.forEach(tx -> out.println(tx.getMetadata()));
    }

    private static void printMarketPrices(List<MarketPriceInfo> prices) {
        String rowFormat = "%-14s%16s%n";
        out.format(rowFormat, "Currency", "Price");
        out.format(rowFormat, "--------", "-----");
        prices.stream()
                .sorted((p1, p2) -> p1.getCurrencyCode().compareTo(p2.getCurrencyCode()))
                .forEach(p -> out.format(rowFormat, p.getCurrencyCode(), formatMarketPrice(p.getPrice())));
    }

    private static void printMarketDepth(MarketDepthInfo depth) {
        String rowFormat = "%-6s%18s%18s%n";
        out.format(rowFormat, "Side", "Price", "Depth (XMR)");
        out.format(rowFormat, "----", "-----", "-----------");
        for (int i = 0; i < depth.getBuyPricesCount(); i++)
            out.format(rowFormat, "BUY", formatMarketPrice(depth.getBuyPrices(i)), depth.getBuyDepth(i));
        for (int i = 0; i < depth.getSellPricesCount(); i++)
            out.format(rowFormat, "SELL", formatMarketPrice(depth.getSellPrices(i)), depth.getSellDepth(i));
    }

    private static void printDisputes(List<Dispute> disputes) {
        String rowFormat = "%-52s%-22s%-12s%-10s%-8s%n";
        out.format(rowFormat, "Trade ID", "Created (UTC)", "State", "Opener", "Closed");
        out.format(rowFormat, "--------", "-------------", "-----", "------", "------");
        disputes.forEach(d -> out.format(rowFormat,
                d.getTradeId(),
                formatTimestamp(d.getOpeningDate()),
                d.getState().name(),
                d.getDisputeOpenerIsBuyer() ? "buyer" : "seller",
                d.getIsClosed() ? "YES" : "NO"));
    }

    private static void printChatMessages(List<ChatMessage> messages) {
        messages.stream()
                .sorted((m1, m2) -> Long.compare(m1.getDate(), m2.getDate()))
                .forEach(m -> out.printf("[%s] %s%s%n",
                        formatTimestamp(m.getDate()),
                        m.getIsSystemMessage() ? "(system) " : "",
                        m.getMessage()));
    }

    private static void printTradeStatistics(List<protobuf.TradeStatistics3> tradeStatistics) {
        String rowFormat = "%-22s%-12s%20s%20s  %-16s%n";
        out.format(rowFormat, "Date (UTC)", "Market", "Price", "Amount (XMR)", "Payment Method");
        out.format(rowFormat, "----------", "------", "-----", "------------", "--------------");
        tradeStatistics.stream()
                .sorted((t1, t2) -> Long.compare(t1.getDate(), t2.getDate()))
                .forEach(t -> out.format(rowFormat,
                        formatTimestamp(t.getDate()),
                        "XMR/" + t.getCurrency(),
                        new BigDecimal(t.getPrice()).movePointLeft(8).stripTrailingZeros().toPlainString(),
                        CurrencyFormat.formatXmr(t.getAmount()),
                        t.getPaymentMethod()));
    }

    private static void printConnections(List<UrlConnection> connections) {
        String rowFormat = "%-64s%-10s%-9s%-19s%n";
        out.format(rowFormat, "URL", "Priority", "Online", "Authenticated");
        out.format(rowFormat, "---", "--------", "------", "-------------");
        connections.forEach(c -> out.format(rowFormat,
                c.getUrl(),
                c.getPriority(),
                c.getOnlineStatus().name(),
                c.getAuthenticationStatus().name()));
    }

    private static void printNotification(NotificationMessage notification) {
        out.printf("[%s] %s %s %s%n",
                formatTimestamp(notification.getTimestamp()),
                notification.getType().name(),
                notification.getTitle(),
                notification.getMessage());
        if (notification.hasTrade())
            out.printf("        trade %s %s %s%n",
                    notification.getTrade().getShortId(),
                    notification.getTrade().getPhase(),
                    notification.getTrade().getState());
        if (notification.hasChatMessage())
            out.printf("        chat message for trade %s: %s%n",
                    notification.getChatMessage().getTradeId(),
                    notification.getChatMessage().getMessage());
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
            String rowFormat = "%-28s%-52s%s%n";
            stream.format(rowFormat, "Method", "Params", "Description");
            stream.format(rowFormat, "------", "------", "------------");
            stream.format(rowFormat, getversion.name(), "", "Get server version");
            stream.println();
            stream.format(rowFormat, accountexists.name(), "", "Check if a Haveno account exists");
            stream.println();
            stream.format(rowFormat, isaccountopen.name(), "", "Check if the Haveno account is open");
            stream.println();
            stream.format(rowFormat, isappinitialized.name(), "", "Check if the server application is initialized");
            stream.println();
            stream.format(rowFormat, createaccount.name(), "--account-password=<password>", "Create a Haveno account");
            stream.println();
            stream.format(rowFormat, openaccount.name(), "--account-password=<password>", "Open the Haveno account");
            stream.println();
            stream.format(rowFormat, changepassword.name(), "--account-password=<password> \\", "Change the Haveno account password");
            stream.format(rowFormat, "", "--new-account-password=<new-password>", "");
            stream.println();
            stream.format(rowFormat, closeaccount.name(), "", "Close the Haveno account");
            stream.println();
            stream.format(rowFormat, deleteaccount.name(), "", "Delete the Haveno account and all its data");
            stream.println();
            stream.format(rowFormat, backupaccount.name(), "[--backup-file=<path>]", "Back up the Haveno account to a zip file");
            stream.println();
            stream.format(rowFormat, restoreaccount.name(), "--backup-file=<path>", "Restore the Haveno account from a backup zip file");
            stream.println();
            stream.format(rowFormat, getbalance.name(), "[--currency-code=<xmr>]", "Get server wallet balances");
            stream.println();
            stream.format(rowFormat, getaddressbalance.name(), "--address=<xmr-address>", "Get server wallet address balance");
            stream.println();
            stream.format(rowFormat, getfundingaddresses.name(), "", "Get XMR funding addresses");
            stream.println();
            stream.format(rowFormat, getxmrseed.name(), "", "Get XMR wallet seed");
            stream.println();
            stream.format(rowFormat, getxmrprimaryaddress.name(), "", "Get XMR wallet primary address");
            stream.println();
            stream.format(rowFormat, getxmrnewsubaddress.name(), "", "Get new XMR wallet subaddress");
            stream.println();
            stream.format(rowFormat, getxmrtxs.name(), "", "Get XMR wallet transactions");
            stream.println();
            stream.format(rowFormat, getwalletheight.name(), "", "Get XMR wallet sync height");
            stream.println();
            stream.format(rowFormat, sendxmr.name(), "--address=<xmr-address> --amount=<xmr-amount>", "Send XMR to an external wallet address");
            stream.println();
            stream.format(rowFormat, createxmrtx.name(), "--destinations=<address:xmr-amount[,address:xmr-amount]>", "Create but do not relay an XMR transaction");
            stream.println();
            stream.format(rowFormat, createxmrsweeptxs.name(), "--address=<xmr-address>", "Create but do not relay tx(s) sweeping the wallet");
            stream.println();
            stream.format(rowFormat, relayxmrtxs.name(), "--metadatas=<metadata[,metadata]>", "Relay previously created XMR transaction(s)");
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
            stream.format(rowFormat, removewalletpassword.name(), "--wallet-password=<password>", "Remove wallet password, decrypting the wallet");
            stream.println();
            stream.format(rowFormat, getxmrprice.name(), "--currency-code=<currency-code>", "Get current market xmr price");
            stream.println();
            stream.format(rowFormat, getxmrprices.name(), "", "Get current market xmr prices for all currencies");
            stream.println();
            stream.format(rowFormat, getmarketdepth.name(), "--currency-code=<currency-code>", "Get market depth for a currency");
            stream.println();
            stream.format(rowFormat, createoffer.name(), "--payment-account-id=<payment-account-id> \\", "Create and place an offer");
            stream.format(rowFormat, "", "--direction=<buy|sell> \\", "");
            stream.format(rowFormat, "", "--currency-code=<currency-code> \\", "");
            stream.format(rowFormat, "", "--amount=<xmr-amount> \\", "");
            stream.format(rowFormat, "", "[--min-amount=<min-xmr-amount>] \\", "");
            stream.format(rowFormat, "", "--fixed-price=<price> | --market-price-margin=<percent> \\", "");
            stream.format(rowFormat, "", "--security-deposit=<percent> \\", "");
            stream.format(rowFormat, "", "[--trigger-price=<price>] \\", "");
            stream.format(rowFormat, "", "[--reserve-exact-amount=<true|false>] \\", "");
            stream.format(rowFormat, "", "[--extra-info=<\"extra info\">]", "");
            stream.println();
            stream.format(rowFormat, editoffer.name(), "--offer-id=<offer-id> \\", "Edit offer with id");
            stream.format(rowFormat, "", "[--fixed-price=<price>] \\", "");
            stream.format(rowFormat, "", "[--market-price-margin=<percent>] \\", "");
            stream.format(rowFormat, "", "[--trigger-price=<price>] \\", "");
            stream.format(rowFormat, "", "[--payment-account-id=<payment-account-id>] \\", "");
            stream.format(rowFormat, "", "[--extra-info=<\"extra info\">]", "");
            stream.println();
            stream.format(rowFormat, activateoffer.name(), "--offer-id=<offer-id>", "Activate a deactivated offer");
            stream.println();
            stream.format(rowFormat, deactivateoffer.name(), "--offer-id=<offer-id>", "Deactivate an offer without removing it");
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
            stream.format(rowFormat, "", "--payment-account-id=<payment-account-id> \\", "");
            stream.format(rowFormat, "", "[--amount=<xmr-amount>] \\", "");
            stream.format(rowFormat, "", "[--challenge=<passphrase>]", "");
            stream.println();
            stream.format(rowFormat, gettrade.name(), "--trade-id=<trade-id> \\", "Get trade summary or full contract");
            stream.format(rowFormat, "", "[--show-contract=<true|false>]", "");
            stream.println();
            stream.format(rowFormat, gettrades.name(), "[--category=<open|closed|failed>]", "Get open (default), closed, or failed trades");
            stream.println();
            stream.format(rowFormat, confirmpaymentsent.name(), "--trade-id=<trade-id>", "Confirm payment sent");
            stream.println();
            stream.format(rowFormat, confirmpaymentreceived.name(), "--trade-id=<trade-id>", "Confirm payment received");
            stream.println();
            stream.format(rowFormat, completetrade.name(), "--trade-id=<trade-id>", "Complete trade, keeping funds in Haveno wallet");
            stream.println();
            stream.format(rowFormat, withdrawfunds.name(), "--trade-id=<trade-id> --address=<xmr-address> \\",
                    "Withdraw received trade funds to external wallet address");
            stream.format(rowFormat, "", "[--memo=<\"memo\">]", "");
            stream.println();
            stream.format(rowFormat, getchatmessages.name(), "--trade-id=<trade-id>", "Get trade chat messages");
            stream.println();
            stream.format(rowFormat, sendchatmessage.name(), "--trade-id=<trade-id> --message=<\"message\">", "Send a trade chat message");
            stream.println();
            stream.format(rowFormat, getpaymentmethods.name(), "", "Get list of supported payment account method ids");
            stream.println();
            stream.format(rowFormat, getcryptopaymentmethods.name(), "", "Get list of supported crypto payment method ids");
            stream.println();
            stream.format(rowFormat, getpaymentacctform.name(), "--payment-method-id=<payment-method-id>", "Get a new payment account form");
            stream.println();
            stream.format(rowFormat, createpaymentacct.name(), "--payment-account-form=<path>", "Create a new payment account");
            stream.println();
            stream.format(rowFormat, createcryptopaymentacct.name(), "--account-name=<name> \\", "Create a new cryptocurrency payment account");
            stream.format(rowFormat, "", "--currency-code=<bch|btc|eth|...> \\", "");
            stream.format(rowFormat, "", "--address=<crypto-address> \\", "");
            stream.format(rowFormat, "", "--trade-instant=<true|false>", "");
            stream.println();
            stream.format(rowFormat, getpaymentaccts.name(), "", "Get user payment accounts");
            stream.println();
            stream.format(rowFormat, deletepaymentacct.name(), "--payment-account-id=<payment-account-id>", "Delete a user payment account");
            stream.println();
            stream.format(rowFormat, getdispute.name(), "--trade-id=<trade-id>", "Get dispute and its chat messages for a trade");
            stream.println();
            stream.format(rowFormat, getdisputes.name(), "", "Get all disputes");
            stream.println();
            stream.format(rowFormat, opendispute.name(), "--trade-id=<trade-id>", "Open a dispute for a trade");
            stream.println();
            stream.format(rowFormat, resolvedispute.name(), "--trade-id=<trade-id> --winner=<buyer|seller> \\", "Resolve a dispute (arbitrator only)");
            stream.format(rowFormat, "", "[--reason=<reason>] \\", "");
            stream.format(rowFormat, "", "[--summary-notes=<\"notes\">] \\", "");
            stream.format(rowFormat, "", "[--custom-payout-amount=<xmr-amount>]", "");
            stream.println();
            stream.format(rowFormat, senddisputechatmessage.name(), "--dispute-id=<dispute-id> --message=<\"message\">", "Send a dispute chat message");
            stream.println();
            stream.format(rowFormat, registerdisputeagent.name(), "--dispute-agent-type=<type> \\", "Register a dispute agent");
            stream.format(rowFormat, "", "--registration-key=<registration-key>", "");
            stream.println();
            stream.format(rowFormat, unregisterdisputeagent.name(), "--dispute-agent-type=<type>", "Unregister a dispute agent");
            stream.println();
            stream.format(rowFormat, gettradestatistics.name(), "", "Get published trade statistics");
            stream.println();
            stream.format(rowFormat, addconnection.name(), "--url=<url> \\", "Add a monero daemon connection");
            stream.format(rowFormat, "", "[--connection-user=<username>] \\", "");
            stream.format(rowFormat, "", "[--connection-password=<password>] \\", "");
            stream.format(rowFormat, "", "[--priority=<number>]", "");
            stream.println();
            stream.format(rowFormat, removeconnection.name(), "--url=<url>", "Remove a monero daemon connection");
            stream.println();
            stream.format(rowFormat, getconnection.name(), "", "Get the current monero daemon connection");
            stream.println();
            stream.format(rowFormat, getconnections.name(), "", "Get all monero daemon connections");
            stream.println();
            stream.format(rowFormat, setconnection.name(), "--url=<url>", "Set the current monero daemon connection");
            stream.println();
            stream.format(rowFormat, checkconnection.name(), "", "Check the current monero daemon connection");
            stream.println();
            stream.format(rowFormat, getbestconnection.name(), "", "Get the best available monero daemon connection");
            stream.println();
            stream.format(rowFormat, setautoswitch.name(), "--auto-switch=<true|false>", "Enable or disable auto switching to the best connection");
            stream.println();
            stream.format(rowFormat, getautoswitch.name(), "", "Get whether auto switching to the best connection is enabled");
            stream.println();
            stream.format(rowFormat, isxmrnodeonline.name(), "", "Check if the local monero node is running");
            stream.println();
            stream.format(rowFormat, getxmrnodesettings.name(), "", "Get local monero node settings");
            stream.println();
            stream.format(rowFormat, startxmrnode.name(), "[--blockchain-path=<path>] \\", "Start the local monero node");
            stream.format(rowFormat, "", "[--bootstrap-url=<url>] \\", "");
            stream.format(rowFormat, "", "[--startup-flags=<flag[,flag]>] \\", "");
            stream.format(rowFormat, "", "[--sync-blockchain=<true|false>]", "");
            stream.println();
            stream.format(rowFormat, stopxmrnode.name(), "", "Stop the local monero node");
            stream.println();
            stream.format(rowFormat, registernotificationlistener.name(), "", "Listen for and print server notifications");
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
