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

package haveno.core.trade;

import com.google.common.base.CaseFormat;
import com.google.common.base.Charsets;

import common.utils.GenUtils;
import haveno.common.config.Config;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Hash;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.Sig;
import haveno.common.file.FileUtil;
import haveno.common.util.Base64;
import haveno.common.util.Utilities;
import haveno.core.api.CoreNotificationService;
import haveno.core.api.CorePaymentAccountsService;
import haveno.core.api.XmrConnectionService;
import haveno.core.app.HavenoSetup;
import haveno.core.locale.CurrencyUtil;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OpenOfferManager;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.trade.messages.PaymentReceivedMessage;
import haveno.core.trade.messages.PaymentSentMessage;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.core.util.JsonUtil;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;
import monero.common.MoneroUtils;
import monero.daemon.model.MoneroOutput;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroTxWallet;

import org.bitcoinj.core.Coin;

/**
 * Collection of utilities.
 */
@Slf4j
public class HavenoUtils {

    // configure release date
    private static final String RELEASE_DATE = "25-05-2024 00:00:00"; // optionally set to release date of the network in format dd-mm-yyyy to impose temporary limits, etc. e.g. "25-05-2024 00:00:00"
    public static final int RELEASE_LIMIT_DAYS = 60; // number of days to limit sell offers to max buy limit for new accounts
    public static final int WARN_ON_OFFER_EXCEEDS_UNSIGNED_BUY_LIMIT_DAYS = 182; // number of days to warn if sell offer exceeds unsigned buy limit
    public static final int ARBITRATOR_ACK_TIMEOUT_SECONDS = 60;

    // configure fees
    public static final boolean ARBITRATOR_ASSIGNS_TRADE_FEE_ADDRESS = true;
    public static final double PENALTY_FEE_PCT = 0.25; // charge 25% of security deposit for penalty
    private static final double MAKER_FEE_PCT_CRYPTO = 0.0015;
    private static final double TAKER_FEE_PCT_CRYPTO = 0.0075;
    private static final double MAKER_FEE_PCT_TRADITIONAL = 0.0015;
    private static final double TAKER_FEE_PCT_TRADITIONAL = 0.0075;
    private static final double MAKER_FEE_FOR_TAKER_WITHOUT_DEPOSIT_PCT_CRYPTO = MAKER_FEE_PCT_CRYPTO + TAKER_FEE_PCT_CRYPTO; // can customize maker's fee when no deposit from taker
    private static final double MAKER_FEE_FOR_TAKER_WITHOUT_DEPOSIT_PCT_TRADITIONAL = MAKER_FEE_PCT_TRADITIONAL + TAKER_FEE_PCT_TRADITIONAL;
    public static final double MINER_FEE_TOLERANCE_FACTOR = 5.0; // miner fees must be within 5x of each other

    // other configuration
    public static final long LOG_POLL_ERROR_PERIOD_MS = 1000 * 60 * 4; // log poll errors up to once every 4 minutes
    public static final long LOG_MONEROD_NOT_SYNCED_WARN_PERIOD_MS = 1000 * 30; // log warnings when daemon not synced once every 30s
    public static final int PRIVATE_OFFER_PASSPHRASE_NUM_WORDS = 8; // number of words in a private offer passphrase

    // synchronize requests to the daemon
    private static boolean SYNC_DAEMON_REQUESTS = false; // sync long requests to daemon (e.g. refresh, update pool) // TODO: performance suffers by syncing daemon requests, but otherwise we sometimes get sporadic errors?
    private static boolean SYNC_WALLET_REQUESTS = false; // additionally sync wallet functions to daemon (e.g. create txs)
    private static Object DAEMON_LOCK = new Object();
    public static Object getDaemonLock() {
        return SYNC_DAEMON_REQUESTS ? DAEMON_LOCK : new Object();
    }
    public static Object getWalletFunctionLock() {
        return SYNC_WALLET_REQUESTS ? getDaemonLock() : new Object();
    }

    // non-configurable
    public static final DecimalFormatSymbols DECIMAL_FORMAT_SYMBOLS = DecimalFormatSymbols.getInstance(Locale.US); // use the US locale as a base for all DecimalFormats (commas should be omitted from number strings)
    public static int XMR_SMALLEST_UNIT_EXPONENT = 12;
    public static final String LOOPBACK_HOST = "127.0.0.1"; // local loopback address to host Monero node
    public static final String LOCALHOST = "localhost";
    private static final long CENTINEROS_AU_MULTIPLIER = 10000;
    private static final BigInteger XMR_AU_MULTIPLIER = new BigInteger("1000000000000");
    public static final DecimalFormat XMR_FORMATTER = new DecimalFormat("##############0.000000000000", DECIMAL_FORMAT_SYMBOLS);
    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
    private static List<String> bip39Words = new ArrayList<String>();

    // shared references TODO: better way to share references?
    public static HavenoSetup havenoSetup;
    public static ArbitrationManager arbitrationManager;
    public static XmrWalletService xmrWalletService;
    public static XmrConnectionService xmrConnectionService;
    public static OpenOfferManager openOfferManager;
    public static CoreNotificationService notificationService;
    public static CorePaymentAccountsService corePaymentAccountService;
    public static TradeStatisticsManager tradeStatisticsManager;
    public static Preferences preferences;

    public static boolean isSeedNode() {
        return havenoSetup == null;
    }

    public static boolean isDaemon() {
        if (isSeedNode()) return true;
        return havenoSetup.getCoreContext().isApiUser();
    }

    @SuppressWarnings("unused")
    public static Date getReleaseDate() {
        if (RELEASE_DATE == null) return null;
        return parseDate(RELEASE_DATE);
    }

    private static Date parseDate(String date) {
        synchronized (DATE_FORMAT) {
            try {
                return DATE_FORMAT.parse(date);
            } catch (Exception e) {
                log.error("Failed to parse date: " + date, e);
                throw new IllegalArgumentException(e);
            }
        }
    }

    public static boolean isReleasedWithinDays(int days) {
        Date releaseDate = getReleaseDate();
        if (releaseDate == null) return false;
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(releaseDate);
        calendar.add(Calendar.DATE, days);
        Date releaseDatePlusDays = calendar.getTime();
        return new Date().before(releaseDatePlusDays);
    }

    public static void waitFor(long waitMs) {
        GenUtils.waitFor(waitMs);
    }

    public static double getMakerFeePct(String currencyCode, boolean hasBuyerAsTakerWithoutDeposit) {
        if (CurrencyUtil.isCryptoCurrency(currencyCode)) {
            return hasBuyerAsTakerWithoutDeposit ? MAKER_FEE_FOR_TAKER_WITHOUT_DEPOSIT_PCT_CRYPTO : MAKER_FEE_PCT_CRYPTO;
        } else if (CurrencyUtil.isTraditionalCurrency(currencyCode)) {
            return hasBuyerAsTakerWithoutDeposit ? MAKER_FEE_FOR_TAKER_WITHOUT_DEPOSIT_PCT_TRADITIONAL : MAKER_FEE_PCT_TRADITIONAL;
        } else {
            throw new IllegalArgumentException("Unsupported currency code: " + currencyCode);
        }
    }

    public static double getTakerFeePct(String currencyCode, boolean hasBuyerAsTakerWithoutDeposit) {
        if (CurrencyUtil.isCryptoCurrency(currencyCode)) {
            return hasBuyerAsTakerWithoutDeposit ? 0d : TAKER_FEE_PCT_CRYPTO;
        } else if (CurrencyUtil.isTraditionalCurrency(currencyCode)) {
            return hasBuyerAsTakerWithoutDeposit ? 0d : TAKER_FEE_PCT_TRADITIONAL;
        } else {
            throw new IllegalArgumentException("Unsupported currency code: " + currencyCode);
        }
    }

    // ----------------------- CONVERSION UTILS -------------------------------

    public static BigInteger coinToAtomicUnits(Coin coin) {
        return centinerosToAtomicUnits(coin.value);
    }

    public static BigInteger centinerosToAtomicUnits(long centineros) {
        return BigInteger.valueOf(centineros).multiply(BigInteger.valueOf(CENTINEROS_AU_MULTIPLIER));
    }

    public static double centinerosToXmr(long centineros) {
        return atomicUnitsToXmr(centinerosToAtomicUnits(centineros));
    }

    public static Coin centinerosToCoin(long centineros) {
        return atomicUnitsToCoin(centinerosToAtomicUnits(centineros));
    }

    public static long atomicUnitsToCentineros(long atomicUnits) {
        return atomicUnitsToCentineros(BigInteger.valueOf(atomicUnits));
    }

    public static long atomicUnitsToCentineros(BigInteger atomicUnits) {
        return atomicUnits.divide(BigInteger.valueOf(CENTINEROS_AU_MULTIPLIER)).longValueExact();
    }

    public static Coin atomicUnitsToCoin(long atomicUnits) {
        return Coin.valueOf(atomicUnitsToCentineros(atomicUnits));
    }

    public static Coin atomicUnitsToCoin(BigInteger atomicUnits) {
        return atomicUnitsToCoin(atomicUnits.longValueExact());
    }

    public static double atomicUnitsToXmr(long atomicUnits) {
        return atomicUnitsToXmr(BigInteger.valueOf(atomicUnits));
    }

    public static double atomicUnitsToXmr(BigInteger atomicUnits) {
        return MoneroUtils.atomicUnitsToXmr(atomicUnits);
    }

    public static BigInteger xmrToAtomicUnits(double xmr) {
        return MoneroUtils.xmrToAtomicUnits(xmr);
    }

    public static long xmrToCentineros(double xmr) {
        return atomicUnitsToCentineros(xmrToAtomicUnits(xmr));
    }

    public static double coinToXmr(Coin coin) {
        return atomicUnitsToXmr(coinToAtomicUnits(coin));
    }

    public static double divide(BigInteger auDividend, BigInteger auDivisor) {
        return MoneroUtils.divide(auDividend, auDivisor);
    }

    public static BigInteger multiply(BigInteger amount1, double amount2) {
        return MoneroUtils.multiply(amount1, amount2);
    }

    // ------------------------- FORMAT UTILS ---------------------------------

    public static String formatXmr(BigInteger atomicUnits) {
        return formatXmr(atomicUnits, false);
    }

    public static String formatXmr(BigInteger atomicUnits, int decimalPlaces) {
        return formatXmr(atomicUnits, false, decimalPlaces);
    }

    public static String formatXmr(BigInteger atomicUnits, boolean appendCode) {
        return formatXmr(atomicUnits, appendCode, 0);
    }

    public static String formatXmr(BigInteger atomicUnits, boolean appendCode, int decimalPlaces) {
        if (atomicUnits == null) return "";
        return formatXmr(atomicUnits.longValueExact(), appendCode, decimalPlaces);
    }

    public static String formatXmr(long atomicUnits) {
        return formatXmr(atomicUnits, false, 0);
    }

    public static String formatXmr(long atomicUnits, boolean appendCode) {
        return formatXmr(atomicUnits, appendCode, 0);
    }

    public static String formatXmr(long atomicUnits, boolean appendCode, int decimalPlaces) {
        String formatted = XMR_FORMATTER.format(atomicUnitsToXmr(atomicUnits));

        // strip trailing 0s
        if (formatted.contains(".")) {
            while (formatted.length() > 3 && formatted.charAt(formatted.length() - 1) == '0') {
                formatted = formatted.substring(0, formatted.length() - 1);
            }
        }
        return applyDecimals(formatted, Math.max(2, decimalPlaces)) + (appendCode ? " XMR" : "");
    }

    public static String formatPercent(double percent) {
        return (percent * 100) + "%";
    }

    private static String applyDecimals(String decimalStr, int decimalPlaces) {
        if (decimalStr.contains(".")) return decimalStr + getNumZeros(decimalPlaces - (decimalStr.length() - decimalStr.indexOf(".") - 1));
        else return decimalStr + "." + getNumZeros(decimalPlaces);
    }

    private static String getNumZeros(int numZeros) {
        String zeros = "";
        for (int i = 0; i < numZeros; i++) zeros += "0";
        return zeros;
    }

    public static BigInteger parseXmr(String input) {
        if (input == null || input.length() == 0) return BigInteger.ZERO;
        try {
            return new BigDecimal(input).multiply(new BigDecimal(XMR_AU_MULTIPLIER)).toBigInteger();
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    // ------------------------ SIGNING AND VERIFYING -------------------------

    public static String generateChallenge() {
        try {

            // load bip39 words
            loadBip39Words();

            // select words randomly
            List<String> passphraseWords = new ArrayList<String>();
            SecureRandom secureRandom = new SecureRandom();
            for (int i = 0; i < PRIVATE_OFFER_PASSPHRASE_NUM_WORDS; i++) {
                passphraseWords.add(bip39Words.get(secureRandom.nextInt(bip39Words.size())));
            }
            return String.join(" ", passphraseWords);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate challenge", e);
        }
    }

    private static synchronized void loadBip39Words() {
        if (bip39Words.isEmpty()) {
            try {
                String fileName = "bip39_english.txt";
                File bip39File = new File(havenoSetup.getConfig().appDataDir, fileName);
                if (!bip39File.exists()) FileUtil.resourceToFile(fileName, bip39File);
                bip39Words = Files.readAllLines(bip39File.toPath(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load BIP39 words", e);
            }
        }
    }

    public static String getChallengeHash(String challenge) {
        if (challenge == null) return null;

        // tokenize passphrase
        String[] words = challenge.toLowerCase().split(" ");

        // collect up to first 4 letters of each word, which are unique in bip39
        List<String> prefixes = new ArrayList<String>();
        for (String word : words) prefixes.add(word.substring(0, Math.min(word.length(), 4)));

        // hash the result
        return Base64.encode(Hash.getSha256Hash(String.join(" ", prefixes).getBytes()));
    }

    public static byte[] sign(KeyRing keyRing, String message) {
        return sign(keyRing.getSignatureKeyPair().getPrivate(), message);
    }

    public static byte[] sign(KeyRing keyRing, byte[] message) {
        return sign(keyRing.getSignatureKeyPair().getPrivate(), message);
    }

    public static byte[] sign(PrivateKey privateKey, String message) {
        return sign(privateKey, message.getBytes(Charsets.UTF_8));
    }

    public static byte[] sign(PrivateKey privateKey, byte[] message) {
        try {
            return Sig.sign(privateKey, message);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static void verifySignature(PubKeyRing pubKeyRing, String message, byte[] signature) {
        verifySignature(pubKeyRing, message.getBytes(Charsets.UTF_8), signature);
    }

    public static void verifySignature(PubKeyRing pubKeyRing, byte[] message, byte[] signature) {
        try {
            boolean isValid = Sig.verify(pubKeyRing.getSignaturePubKey(), message, signature);
            if (!isValid) throw new IllegalArgumentException("Signature verification failed.");
        } catch (CryptoException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static boolean isSignatureValid(PubKeyRing pubKeyRing, String message, byte[] signature) {
        return isSignatureValid(pubKeyRing, message.getBytes(Charsets.UTF_8), signature);
    }

    public static boolean isSignatureValid(PubKeyRing pubKeyRing, byte[] message, byte[] signature) {
        try {
            verifySignature(pubKeyRing, message, signature);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sign an offer.
     * 
     * @param offer is an unsigned offer to sign
     * @param keyRing is the arbitrator's key ring to sign with
     * @return the arbitrator's signature
     */
    public static byte[] signOffer(OfferPayload offer, KeyRing keyRing) {
        return HavenoUtils.sign(keyRing, offer.getSignatureHash());
    }

    /**
     * Check if the arbitrator signature is valid for an offer.
     *
     * @param offer is a signed offer with payload
     * @param arbitrator is the original signing arbitrator
     * @return true if the arbitrator's signature is valid for the offer
     */
    public static boolean isArbitratorSignatureValid(OfferPayload offer, Arbitrator arbitrator) {
        return isSignatureValid(arbitrator.getPubKeyRing(), offer.getSignatureHash(), offer.getArbitratorSignature());
    }

    /**
     * Verify the buyer signature for a PaymentSentMessage.
     *
     * @param trade - the trade to verify
     * @param message - signed payment sent message to verify
     * @return true if the buyer's signature is valid for the message
     */
    public static void verifyPaymentSentMessage(Trade trade, PaymentSentMessage message) {

        // remove signature from message
        byte[] signature = message.getBuyerSignature();
        message.setBuyerSignature(null);

        // get unsigned message as json string
        String unsignedMessageAsJson = JsonUtil.objectToJson(message);

        // replace signature
        message.setBuyerSignature(signature);

        // verify signature
        if (!isSignatureValid(trade.getBuyer().getPubKeyRing(), unsignedMessageAsJson, signature)) {
            throw new IllegalArgumentException("The buyer signature is invalid for the " + message.getClass().getSimpleName() + " for " + trade.getClass().getSimpleName() + " " + trade.getId());
        }

        // verify trade id
        if (!trade.getId().equals(message.getOfferId())) throw new IllegalArgumentException("The " + message.getClass().getSimpleName() + " has the wrong trade id, expected " + trade.getId() + " but was " + message.getOfferId());
    }

    /**
     * Verify the seller signature for a PaymentReceivedMessage.
     *
     * @param trade - the trade to verify
     * @param message - signed payment received message to verify
     * @return true if the seller's signature is valid for the message
     */
    public static void verifyPaymentReceivedMessage(Trade trade, PaymentReceivedMessage message) {

        // remove signature from message
        byte[] signature = message.getSellerSignature();
        message.setSellerSignature(null);

        // get unsigned message as json string
        String unsignedMessageAsJson = JsonUtil.objectToJson(message);

        // replace signature
        message.setSellerSignature(signature);

        // verify signature
        if (!isSignatureValid(trade.getSeller().getPubKeyRing(), unsignedMessageAsJson, signature)) {
            throw new IllegalArgumentException("The seller signature is invalid for the " + message.getClass().getSimpleName() + " for " + trade.getClass().getSimpleName() + " " + trade.getId());
        }

        // verify trade id
        if (!trade.getId().equals(message.getOfferId())) throw new IllegalArgumentException("The " + message.getClass().getSimpleName() + " has the wrong trade id, expected " + trade.getId() + " but was " + message.getOfferId());

        // verify buyer signature of payment sent message
        if (message.getPaymentSentMessage() != null) verifyPaymentSentMessage(trade, message.getPaymentSentMessage());
    }

    // ----------------------------- OTHER UTILS ------------------------------

    public static String getGlobalTradeFeeAddress() {
        switch (Config.baseCurrencyNetwork()) {
        case XMR_LOCAL:
            return "Bd37nTGHjL3RvPxc9dypzpWiXQrPzxxG4RsWAasD9CV2iZ1xfFZ7mzTKNDxWBfsqQSUimctAsGtTZ8c8bZJy35BYL9jYj88";
        case XMR_STAGENET:
            return "5B11hTJdG2XDNwjdKGLRxwSLwDhkbGg7C7UEAZBxjE6FbCeRMjudrpNACmDNtWPiSnNfjDQf39QRjdtdgoL69txv81qc2Mc";
        case XMR_MAINNET:
            throw new RuntimeException("Mainnet fee address not implemented");
        default:
            throw new RuntimeException("Unhandled base currency network: " + Config.baseCurrencyNetwork());
        }
    }

    public static String getBurnAddress() {
        switch (Config.baseCurrencyNetwork()) {
        case XMR_LOCAL:
            return "Bd37nTGHjL3RvPxc9dypzpWiXQrPzxxG4RsWAasD9CV2iZ1xfFZ7mzTKNDxWBfsqQSUimctAsGtTZ8c8bZJy35BYL9jYj88";
        case XMR_STAGENET:
            return "577XbZ8yGfrWJM3aAoCpHVgDCm5higshGVJBb4ZNpTYARp8rLcCdcA1J8QgRfFWTzmJ8QgRfFWTzmJ8QgRfFWTzmCbXF9hd";
        case XMR_MAINNET:
            return "46uVWiE1d4kWJM3aAoCpHVgDCm5higshGVJBb4ZNpTYARp8rLcCdcA1J8QgRfFWTzmJ8QgRfFWTzmJ8QgRfFWTzmCag5CXT";
        default:
            throw new RuntimeException("Unhandled base currency network: " + Config.baseCurrencyNetwork());
        }
    }

    /**
     * Check if the given URI is on local host.
     */
    public static boolean isLocalHost(String uriString) {
        try {
            String host = new URI(uriString).getHost();
            return LOOPBACK_HOST.equals(host) || LOCALHOST.equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if the given URI is local or a private IP address.
     */
    public static boolean isPrivateIp(String uriString) {
        if (isLocalHost(uriString)) return true;
        try {

            // get the host
            URI uri = new URI(uriString);
            String host = uri.getHost();

            // check if private IP address
            if (host == null) return false;
            InetAddress inetAddress = InetAddress.getByName(host);
            return inetAddress.isAnyLocalAddress() || inetAddress.isLoopbackAddress() || inetAddress.isSiteLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns a unique deterministic id for sending a trade mailbox message.
     *
     * @param trade the trade
     * @param tradeMessageClass the trade message class
     * @param receiver the receiver address
     * @return a unique deterministic id for sending a trade mailbox message
     */
    public static String getDeterministicId(Trade trade, Class<?> tradeMessageClass, NodeAddress receiver) {
        String uniqueId = trade.getId() + "_" + tradeMessageClass.getSimpleName() + "_" + trade.getRole() + "_to_" + trade.getPeerRole(trade.getTradePeer(receiver));
        return Utilities.bytesAsHexString(Hash.getSha256Ripemd160hash(uniqueId.getBytes(Charsets.UTF_8)));
    }

    public static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static String toCamelCase(String underscore) {
        return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, underscore);
    }

    public static boolean connectionConfigsEqual(MoneroRpcConnection c1, MoneroRpcConnection c2) {
        if (c1 == c2) return true;
        if (c1 == null) return false;
        return c1.equals(c2); // equality considers uri, username, and password
    }

    // TODO: move to monero-java MoneroTxWallet
    public static MoneroDestination getDestination(String address, MoneroTxWallet tx) {
        for (MoneroDestination destination : tx.getOutgoingTransfer().getDestinations()) {
            if (address.equals(destination.getAddress())) return destination;
        }
        return null;
    }

    public static List<String> getInputKeyImages(MoneroTxWallet tx) {
        List<String> inputKeyImages = new ArrayList<String>();
        for (MoneroOutput input : tx.getInputs()) inputKeyImages.add(input.getKeyImage().getHex());
        return inputKeyImages;
    }

    public static int getDefaultMoneroPort() {
        if (Config.baseCurrencyNetwork().isMainnet()) return 18081;
        else if (Config.baseCurrencyNetwork().isTestnet()) return 28081;
        else if (Config.baseCurrencyNetwork().isStagenet()) return 38081;
        else throw new RuntimeException("Base network is not local testnet, stagenet, or mainnet");
    }

    public static void setTopError(String msg) {
        havenoSetup.getTopErrorMsg().set(msg);
    }

    public static boolean isConnectionRefused(Throwable e) {
        return e != null && e.getMessage().contains("Connection refused");
    }

    public static boolean isReadTimeout(Throwable e) {
        return e != null && e.getMessage().contains("Read timed out");
    }

    public static boolean isUnresponsive(Throwable e) {
        return isConnectionRefused(e) || isReadTimeout(e);
    }

    public static boolean isNotEnoughSigners(Throwable e) {
        return e != null && e.getMessage().contains("Not enough signers");
    }

    public static boolean isFailedToParse(Throwable e) {
        return e != null && e.getMessage().contains("Failed to parse");
    }

    public static boolean isTransactionRejected(Throwable e) {
        return e != null && e.getMessage().contains("was rejected");
    }

    public static boolean isIllegal(Throwable e) {
        return e instanceof IllegalArgumentException || e instanceof IllegalStateException;
    }
    
    public static void playChimeSound() {
        playAudioFile("chime.wav");
    }

    public static void playCashRegisterSound() {
        playAudioFile("cash_register.wav");
    }

    private static void playAudioFile(String fileName) {
        if (isDaemon()) return; // ignore if running as daemon
        if (!preferences.getUseSoundForNotificationsProperty().get()) return; // ignore if sounds disabled
        new Thread(() -> {
            try {

                // get audio file
                File wavFile = new File(havenoSetup.getConfig().appDataDir, fileName);
                if (!wavFile.exists()) FileUtil.resourceToFile(fileName, wavFile);
                AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(wavFile);
    
                // get original format
                AudioFormat baseFormat = audioInputStream.getFormat();
    
                // set target format: PCM_SIGNED, 16-bit, 44100 Hz
                AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    44100.0f,
                    16, // 16-bit instead of 32-bit float
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2, // Frame size: 2 bytes per channel (16-bit)
                    44100.0f,
                    false // Little-endian
                );
    
                // convert audio to target format
                AudioInputStream convertedStream = AudioSystem.getAudioInputStream(targetFormat, audioInputStream);
    
                // play audio
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, targetFormat);
                SourceDataLine sourceLine = (SourceDataLine) AudioSystem.getLine(info);
                sourceLine.open(targetFormat);
                sourceLine.start();
                byte[] buffer = new byte[1024];
                int bytesRead = 0;
                while ((bytesRead = convertedStream.read(buffer, 0, buffer.length)) != -1) {
                    sourceLine.write(buffer, 0, bytesRead);
                }
                sourceLine.drain();
                sourceLine.close();
                convertedStream.close();
                audioInputStream.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public static void verifyMinerFee(BigInteger expected, BigInteger actual) {
        BigInteger max = expected.max(actual);
        BigInteger min = expected.min(actual);
        if (min.compareTo(BigInteger.ZERO) <= 0) {
            throw new IllegalArgumentException("Miner fees must be greater than zero");
        }
        double factor = divide(max, min);
        if (factor > MINER_FEE_TOLERANCE_FACTOR) {
            throw new IllegalArgumentException("Miner fees are not within " + MINER_FEE_TOLERANCE_FACTOR + "x of each other. Expected=" + expected + ", actual=" + actual + ", factor=" + factor);
        }
    }
}
