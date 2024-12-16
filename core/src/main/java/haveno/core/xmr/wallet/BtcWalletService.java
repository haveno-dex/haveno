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

package haveno.core.xmr.wallet;

import com.google.common.base.Preconditions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Inject;
import haveno.common.util.Tuple2;
import haveno.core.user.Preferences;
import haveno.core.xmr.exceptions.AddressEntryException;
import haveno.core.xmr.exceptions.InsufficientFundsException;
import haveno.core.xmr.exceptions.TransactionVerificationException;
import haveno.core.xmr.exceptions.WalletException;
import haveno.core.xmr.model.AddressEntry;
import haveno.core.xmr.model.AddressEntryList;
import haveno.core.xmr.setup.WalletsSetup;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptPattern;
import org.bitcoinj.wallet.SendRequest;
import org.bouncycastle.crypto.params.KeyParameter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BtcWalletService extends WalletService {
    private static final Logger log = LoggerFactory.getLogger(BtcWalletService.class);

    private final AddressEntryList addressEntryList;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public BtcWalletService(WalletsSetup walletsSetup,
                            AddressEntryList addressEntryList,
                            Preferences preferences) {
        super(walletsSetup,
                preferences);

        this.addressEntryList = addressEntryList;

        // TODO: set and use chainHeightProperty in XmrWalletService
        walletsSetup.addSetupCompletedHandler(() -> {
//            wallet = walletsSetup.getBtcWallet();
//            addListenersToWallet();
//
//            walletsSetup.getChain().addNewBestBlockListener(block -> chainHeightProperty.set(block.getHeight()));
//            chainHeightProperty.set(walletsSetup.getChain().getBestChainHeight());
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Overridden Methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isWalletSyncedWithinTolerance() {
        throw new RuntimeException("Not implemented");
    }

    @Override
    void decryptWallet(@NotNull KeyParameter key) {
        super.decryptWallet(key);

        addressEntryList.getAddressEntriesAsListImmutable().forEach(e -> {
            DeterministicKey keyPair = e.getKeyPair();
            if (keyPair.isEncrypted())
                e.setDeterministicKey(keyPair.decrypt(key));
        });
        addressEntryList.requestPersistence();
    }

    @Override
    void encryptWallet(KeyCrypterScrypt keyCrypterScrypt, KeyParameter key) {
        super.encryptWallet(keyCrypterScrypt, key);
        addressEntryList.getAddressEntriesAsListImmutable().forEach(e -> {
            DeterministicKey keyPair = e.getKeyPair();
            if (keyPair.isEncrypted())
                e.setDeterministicKey(keyPair.encrypt(keyCrypterScrypt, key));
        });
        addressEntryList.requestPersistence();
    }

    @Override
    String getWalletAsString(boolean includePrivKeys) {
        StringBuilder sb = new StringBuilder();
        getAddressEntryListAsImmutableList().forEach(e -> sb.append(e.toString()).append("\n"));
        //boolean reallyIncludePrivKeys = includePrivKeys && !wallet.isEncrypted();
        return "Address entry list:\n" +
                sb.toString() +
                "\n\n" +
                wallet.toString(true, includePrivKeys, this.aesKey, true, true, walletsSetup.getChain()) + "\n\n" +
                "All pubKeys as hex:\n" +
                wallet.printAllPubKeysAsHex();
    }

    private Tuple2<Integer, Integer> getNumInputs(Transaction tx) {
        int numLegacyInputs = 0;
        int numSegwitInputs = 0;
        for (TransactionInput input : tx.getInputs()) {
            TransactionOutput connectedOutput = input.getConnectedOutput();
            if (connectedOutput == null || ScriptPattern.isP2PKH(connectedOutput.getScriptPubKey()) ||
                    ScriptPattern.isP2PK(connectedOutput.getScriptPubKey())) {
                // If connectedOutput is null, we don't know here the input type. To avoid underpaying fees,
                // we treat it as a legacy input which will result in a higher fee estimation.
                numLegacyInputs++;
            } else if (ScriptPattern.isP2WPKH(connectedOutput.getScriptPubKey())) {
                numSegwitInputs++;
            } else {
                throw new IllegalArgumentException("Inputs should spend a P2PKH, P2PK or P2WPKH ouput");
            }
        }
        return new Tuple2(numLegacyInputs, numSegwitInputs);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Commit tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void commitTx(Transaction tx) {
        wallet.commitTx(tx);
        // printTx("BTC commit Tx", tx);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // AddressEntry
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Optional<AddressEntry> getAddressEntry(String offerId,
                                                  @SuppressWarnings("SameParameterValue") AddressEntry.Context context) {
        return getAddressEntryListAsImmutableList().stream()
                .filter(e -> offerId.equals(e.getOfferId()))
                .filter(e -> context == e.getContext())
                .findAny();
    }

    public AddressEntry getOrCreateAddressEntry(String offerId, AddressEntry.Context context) {
        Optional<AddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream()
                .filter(e -> offerId.equals(e.getOfferId()))
                .filter(e -> context == e.getContext())
                .findAny();
        if (addressEntry.isPresent()) {
            return addressEntry.get();
        } else {
            // We try to use available and not yet used entries
            Optional<AddressEntry> emptyAvailableAddressEntry = getAddressEntryListAsImmutableList().stream()
                    .filter(e -> AddressEntry.Context.AVAILABLE == e.getContext())
                    .filter(e -> isAddressUnused(e.getAddress()))
                    .filter(e -> Script.ScriptType.P2WPKH.equals(e.getAddress().getOutputScriptType()))
                    .findAny();
            if (emptyAvailableAddressEntry.isPresent()) {
                return addressEntryList.swapAvailableToAddressEntryWithOfferId(emptyAvailableAddressEntry.get(), context, offerId);
            } else {
                DeterministicKey key = (DeterministicKey) wallet.findKeyFromAddress(wallet.freshReceiveAddress(Script.ScriptType.P2WPKH));
                AddressEntry entry = new AddressEntry(key, context, offerId, true);
                log.info("getOrCreateAddressEntry: new AddressEntry={}", entry);
                addressEntryList.addAddressEntry(entry);
                return entry;
            }
        }
    }

    public AddressEntry getArbitratorAddressEntry() {
        AddressEntry.Context context = AddressEntry.Context.ARBITRATOR;
        Optional<AddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream()
                .filter(e -> context == e.getContext())
                .findAny();
        return getOrCreateAddressEntry(context, addressEntry, false);
    }

    public AddressEntry getFreshAddressEntry() {
        return getFreshAddressEntry(true);
    }

    public AddressEntry getFreshAddressEntry(boolean segwit) {
        AddressEntry.Context context = AddressEntry.Context.AVAILABLE;
        Optional<AddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream()
                .filter(e -> context == e.getContext())
                .filter(e -> isAddressUnused(e.getAddress()))
                .filter(e -> {
                    boolean isSegwitOutputScriptType = Script.ScriptType.P2WPKH.equals(e.getAddress().getOutputScriptType());
                    // We need to ensure that we take only addressEntries which matches our segWit flag
                    return isSegwitOutputScriptType == segwit;
                })
                .findAny();
        return getOrCreateAddressEntry(context, addressEntry, segwit);
    }

    public void recoverAddressEntry(String offerId, String address, AddressEntry.Context context) {
        findAddressEntry(address, AddressEntry.Context.AVAILABLE).ifPresent(addressEntry ->
                addressEntryList.swapAvailableToAddressEntryWithOfferId(addressEntry, context, offerId));
    }

    private AddressEntry getOrCreateAddressEntry(AddressEntry.Context context,
                                                 Optional<AddressEntry> addressEntry,
                                                 boolean segwit) {
        if (addressEntry.isPresent()) {
            return addressEntry.get();
        } else {
            DeterministicKey key;
            if (segwit) {
                key = (DeterministicKey) wallet.findKeyFromAddress(wallet.freshReceiveAddress(Script.ScriptType.P2WPKH));
            } else {
                key = (DeterministicKey) wallet.findKeyFromAddress(wallet.freshReceiveAddress(Script.ScriptType.P2PKH));
            }
            AddressEntry entry = new AddressEntry(key, context, segwit);
            log.info("getOrCreateAddressEntry: add new AddressEntry {}", entry);
            addressEntryList.addAddressEntry(entry);
            return entry;
        }
    }

    private Optional<AddressEntry> findAddressEntry(String address, AddressEntry.Context context) {
        return getAddressEntryListAsImmutableList().stream()
                .filter(e -> address.equals(e.getAddressString()))
                .filter(e -> context == e.getContext())
                .findAny();
    }

    public List<AddressEntry> getAvailableAddressEntries() {
        return getAddressEntryListAsImmutableList().stream()
                .filter(addressEntry -> AddressEntry.Context.AVAILABLE == addressEntry.getContext())
                .collect(Collectors.toList());
    }

    public List<AddressEntry> getAddressEntriesForOpenOffer() {
        return getAddressEntryListAsImmutableList().stream()
                .filter(addressEntry -> AddressEntry.Context.OFFER_FUNDING == addressEntry.getContext() ||
                        AddressEntry.Context.RESERVED_FOR_TRADE == addressEntry.getContext())
                .collect(Collectors.toList());
    }

    public List<AddressEntry> getAddressEntriesForTrade() {
        return getAddressEntryListAsImmutableList().stream()
                .filter(addressEntry -> AddressEntry.Context.MULTI_SIG == addressEntry.getContext() ||
                        AddressEntry.Context.TRADE_PAYOUT == addressEntry.getContext())
                .collect(Collectors.toList());
    }

    public List<AddressEntry> getAddressEntries(AddressEntry.Context context) {
        return getAddressEntryListAsImmutableList().stream()
                .filter(addressEntry -> context == addressEntry.getContext())
                .collect(Collectors.toList());
    }

    public List<AddressEntry> getFundedAvailableAddressEntries() {
        return getAvailableAddressEntries().stream()
                .filter(addressEntry -> getBalanceForAddress(addressEntry.getAddress()).isPositive())
                .collect(Collectors.toList());
    }

    public List<AddressEntry> getAddressEntryListAsImmutableList() {
        return addressEntryList.getAddressEntriesAsListImmutable();
    }

    public void swapAddressEntryToAvailable(String offerId, AddressEntry.Context context) {
        if (context == AddressEntry.Context.MULTI_SIG) {
            log.error("swapAddressEntryToAvailable called with MULTI_SIG context. " +
                    "This in not permitted as we must not reuse those address entries and there " +
                    "are no redeemable funds on that addresses. Only the keys are used for creating " +
                    "the Multisig address. offerId={}, context={}", offerId, context);
            return;
        }

        getAddressEntryListAsImmutableList().stream()
                .filter(e -> offerId.equals(e.getOfferId()))
                .filter(e -> context == e.getContext())
                .forEach(e -> {
                    log.info("swap addressEntry with address {} and offerId {} from context {} to available",
                            e.getAddressString(), e.getOfferId(), context);
                    addressEntryList.swapToAvailable(e);
                });
    }

    // When funds from MultiSig address is spent we reset the coinLockedInMultiSig value to 0.
    public void resetCoinLockedInMultiSigAddressEntry(String offerId) {
        setCoinLockedInMultiSigAddressEntry(offerId, 0);
    }

    public void setCoinLockedInMultiSigAddressEntry(String offerId, long value) {
        getAddressEntryListAsImmutableList().stream()
                .filter(e -> AddressEntry.Context.MULTI_SIG == e.getContext())
                .filter(e -> offerId.equals(e.getOfferId()))
                .forEach(addressEntry -> setCoinLockedInMultiSigAddressEntry(addressEntry, value));
    }

    public void setCoinLockedInMultiSigAddressEntry(AddressEntry addressEntry, long value) {
        log.info("Set coinLockedInMultiSig for addressEntry {} to value {}", addressEntry, value);
        addressEntryList.setCoinLockedInMultiSigAddressEntry(addressEntry, value);
    }

    public void resetAddressEntriesForOpenOffer(String offerId) {
        log.info("resetAddressEntriesForOpenOffer offerId={}", offerId);
        swapAddressEntryToAvailable(offerId, AddressEntry.Context.OFFER_FUNDING);
        swapAddressEntryToAvailable(offerId, AddressEntry.Context.RESERVED_FOR_TRADE);
    }

    public void resetAddressEntriesForPendingTrade(String offerId) {
        // We must not swap MULTI_SIG entries as those addresses are not detected in the isAddressUnused
        // check at getOrCreateAddressEntry and could lead to a reuse of those keys and result in the same 2of2 MS
        // address if same peers trade again.

        // We swap TRADE_PAYOUT to be sure all is cleaned up. There might be cases where a user cannot send the funds
        // to an external wallet directly in the last step of the trade, but the funds are in the Haveno wallet anyway and
        // the dealing with the external wallet is pure UI thing. The user can move the funds to the wallet and then
        // send out the funds to the external wallet. As this cleanup is a rare situation and most users do not use
        // the feature to send out the funds we prefer that strategy (if we keep the address entry it might cause
        // complications in some edge cases after a SPV resync).
        swapAddressEntryToAvailable(offerId, AddressEntry.Context.TRADE_PAYOUT);
    }

    public void swapAnyTradeEntryContextToAvailableEntry(String offerId) {
        resetAddressEntriesForOpenOffer(offerId);
        resetAddressEntriesForPendingTrade(offerId);
    }

    public void saveAddressEntryList() {
        addressEntryList.requestPersistence();
    }

    public DeterministicKey getMultiSigKeyPair(String tradeId, byte[] pubKey) {
        Optional<AddressEntry> multiSigAddressEntryOptional = getAddressEntry(tradeId, AddressEntry.Context.MULTI_SIG);
        DeterministicKey multiSigKeyPair;
        if (multiSigAddressEntryOptional.isPresent()) {
            AddressEntry multiSigAddressEntry = multiSigAddressEntryOptional.get();
            multiSigKeyPair = multiSigAddressEntry.getKeyPair();
            if (!Arrays.equals(pubKey, multiSigAddressEntry.getPubKey())) {
                log.error("Pub Key from AddressEntry does not match key pair from trade data. Trade ID={}\n" +
                        "We try to find the keypair in the wallet with the pubKey we found in the trade data.", tradeId);
                multiSigKeyPair = findKeyFromPubKey(pubKey);
            }
        } else {
            log.error("multiSigAddressEntry not found for trade ID={}.\n" +
                    "We try to find the keypair in the wallet with the pubKey we found in the trade data.", tradeId);
            multiSigKeyPair = findKeyFromPubKey(pubKey);
        }

        return multiSigKeyPair;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Balance
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Coin getSavingWalletBalance() {
        return Coin.valueOf(getFundedAvailableAddressEntries().stream()
                .mapToLong(addressEntry -> getBalanceForAddress(addressEntry.getAddress()).value)
                .sum());
    }

    public Stream<AddressEntry> getAddressEntriesForAvailableBalanceStream() {
        Stream<AddressEntry> availableAndPayout = Stream.concat(getAddressEntries(AddressEntry.Context.TRADE_PAYOUT)
                .stream(), getFundedAvailableAddressEntries().stream());
        Stream<AddressEntry> available = Stream.concat(availableAndPayout,
                getAddressEntries(AddressEntry.Context.ARBITRATOR).stream());
        available = Stream.concat(available, getAddressEntries(AddressEntry.Context.OFFER_FUNDING).stream());
        return available.filter(addressEntry -> getBalanceForAddress(addressEntry.getAddress()).isPositive());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Withdrawal Fee calculation
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Coin getTxFeeForWithdrawalPerVbyte() {
        throw new RuntimeException("BTC fee estimation removed");
    }

    public Transaction getFeeEstimationTransaction(String fromAddress,
                                                   String toAddress,
                                                   Coin amount,
                                                   AddressEntry.Context context)
            throws AddressFormatException, AddressEntryException, InsufficientFundsException {

        Optional<AddressEntry> addressEntry = findAddressEntry(fromAddress, context);
        if (!addressEntry.isPresent())
            throw new AddressEntryException("WithdrawFromAddress is not found in our wallet.");

        checkNotNull(addressEntry.get().getAddress(), "addressEntry.get().getAddress() must nto be null");

        try {
            Coin fee;
            int counter = 0;
            int txVsize = 0;
            Transaction tx;
            Coin txFeeForWithdrawalPerVbyte = getTxFeeForWithdrawalPerVbyte();
            do {
                counter++;
                fee = txFeeForWithdrawalPerVbyte.multiply(txVsize);
                SendRequest sendRequest = getSendRequest(fromAddress, toAddress, amount, fee, aesKey, context);
                wallet.completeTx(sendRequest);
                tx = sendRequest.tx;
                txVsize = tx.getVsize();
                printTx("FeeEstimationTransaction", tx);
            }
            while (feeEstimationNotSatisfied(counter, tx));
            if (counter == 10)
                log.error("Could not calculate the fee. Tx=" + tx);

            return tx;
        } catch (InsufficientMoneyException e) {
            throw new InsufficientFundsException("The fees for that transaction exceed the available funds " +
                    "or the resulting output value is below the min. dust value:\n" +
                    "Missing " + (e.missing != null ? e.missing.toFriendlyString() : "null"));
        }
    }

    public Transaction getFeeEstimationTransactionForMultipleAddresses(Set<String> fromAddresses,
                                                                       Coin amount)
            throws AddressFormatException, AddressEntryException, InsufficientFundsException {
        Coin txFeeForWithdrawalPerVbyte = getTxFeeForWithdrawalPerVbyte();
        return getFeeEstimationTransactionForMultipleAddresses(fromAddresses, amount, txFeeForWithdrawalPerVbyte);
    }

    public Transaction getFeeEstimationTransactionForMultipleAddresses(Set<String> fromAddresses,
                                                                       Coin amount,
                                                                       Coin txFeeForWithdrawalPerVbyte)
            throws AddressFormatException, AddressEntryException, InsufficientFundsException {
        Set<AddressEntry> addressEntries = fromAddresses.stream()
                .map(address -> {
                    Optional<AddressEntry> addressEntryOptional = findAddressEntry(address, AddressEntry.Context.AVAILABLE);
                    if (!addressEntryOptional.isPresent())
                        addressEntryOptional = findAddressEntry(address, AddressEntry.Context.OFFER_FUNDING);
                    if (!addressEntryOptional.isPresent())
                        addressEntryOptional = findAddressEntry(address, AddressEntry.Context.TRADE_PAYOUT);
                    if (!addressEntryOptional.isPresent())
                        addressEntryOptional = findAddressEntry(address, AddressEntry.Context.ARBITRATOR);
                    return addressEntryOptional;
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());
        if (addressEntries.isEmpty())
            throw new AddressEntryException("No Addresses for withdraw  found in our wallet");

        try {
            Coin fee;
            int counter = 0;
            int txVsize = 0;
            Transaction tx;
            do {
                counter++;
                fee = txFeeForWithdrawalPerVbyte.multiply(txVsize);
                // We use a dummy address for the output
                // We don't know here whether the output is segwit or not but we don't care too much because the size of
                // a segwit ouput is just 3 byte smaller than the size of a legacy ouput.
                final String dummyReceiver = SegwitAddress.fromKey(params, new ECKey()).toString();
                SendRequest sendRequest = getSendRequestForMultipleAddresses(fromAddresses, dummyReceiver, amount, fee, null, aesKey);
                wallet.completeTx(sendRequest);
                tx = sendRequest.tx;
                txVsize = tx.getVsize();
                printTx("FeeEstimationTransactionForMultipleAddresses", tx);
            }
            while (feeEstimationNotSatisfied(counter, tx));
            if (counter == 10)
                log.error("Could not calculate the fee. Tx=" + tx);

            return tx;
        } catch (InsufficientMoneyException e) {
            throw new InsufficientFundsException("The fees for that transaction exceed the available funds " +
                    "or the resulting output value is below the min. dust value:\n" +
                    "Missing " + (e.missing != null ? e.missing.toFriendlyString() : "null"));
        }
    }

    private boolean feeEstimationNotSatisfied(int counter, Transaction tx) {
        return feeEstimationNotSatisfied(counter, tx, getTxFeeForWithdrawalPerVbyte());
    }

    private boolean feeEstimationNotSatisfied(int counter, Transaction tx, Coin txFeeForWithdrawalPerVbyte) {
        long targetFee = txFeeForWithdrawalPerVbyte.multiply(tx.getVsize()).value;
        return counter < 10 &&
                (tx.getFee().value < targetFee ||
                        tx.getFee().value - targetFee > 1000);
    }

    public int getEstimatedFeeTxVsize(List<Coin> outputValues, Coin txFee)
            throws InsufficientMoneyException, AddressFormatException {
        Transaction transaction = new Transaction(params);
        // In reality txs have a mix of segwit/legacy ouputs, but we don't care too much because the size of
        // a segwit ouput is just 3 byte smaller than the size of a legacy ouput.
        Address dummyAddress = SegwitAddress.fromKey(params, new ECKey());
        outputValues.forEach(outputValue -> transaction.addOutput(outputValue, dummyAddress));

        SendRequest sendRequest = SendRequest.forTx(transaction);
        sendRequest.shuffleOutputs = false;
        sendRequest.aesKey = aesKey;
        sendRequest.coinSelector = new BtcCoinSelector(walletsSetup.getAddressesByContext(AddressEntry.Context.AVAILABLE),
                preferences.getIgnoreDustThreshold());
        sendRequest.fee = txFee;
        sendRequest.feePerKb = Coin.ZERO;
        sendRequest.ensureMinRequiredFee = false;
        sendRequest.changeAddress = dummyAddress;
        wallet.completeTx(sendRequest);
        return transaction.getVsize();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Withdrawal Send
    ///////////////////////////////////////////////////////////////////////////////////////////

    private SendRequest getSendRequest(String fromAddress,
                                       String toAddress,
                                       Coin amount,
                                       Coin fee,
                                       @Nullable KeyParameter aesKey,
                                       AddressEntry.Context context) throws AddressFormatException,
            AddressEntryException {
        Transaction tx = new Transaction(params);
        final Coin receiverAmount = amount.subtract(fee);
        Preconditions.checkArgument(Restrictions.isAboveDust(receiverAmount),
                "The amount is too low (dust limit).");
        tx.addOutput(receiverAmount, Address.fromString(params, toAddress));

        SendRequest sendRequest = SendRequest.forTx(tx);
        sendRequest.fee = fee;
        sendRequest.feePerKb = Coin.ZERO;
        sendRequest.ensureMinRequiredFee = false;
        sendRequest.aesKey = aesKey;
        sendRequest.shuffleOutputs = false;
        Optional<AddressEntry> addressEntry = findAddressEntry(fromAddress, context);
        if (!addressEntry.isPresent())
            throw new AddressEntryException("WithdrawFromAddress is not found in our wallet.");

        checkNotNull(addressEntry.get(), "addressEntry.get() must not be null");
        checkNotNull(addressEntry.get().getAddress(), "addressEntry.get().getAddress() must not be null");
        sendRequest.coinSelector = new BtcCoinSelector(addressEntry.get().getAddress(), preferences.getIgnoreDustThreshold());
        sendRequest.changeAddress = addressEntry.get().getAddress();
        return sendRequest;
    }

    private SendRequest getSendRequestForMultipleAddresses(Set<String> fromAddresses,
                                                           String toAddress,
                                                           Coin amount,
                                                           Coin fee,
                                                           @Nullable String changeAddress,
                                                           @Nullable KeyParameter aesKey) throws
            AddressFormatException, AddressEntryException {
        Transaction tx = new Transaction(params);
        final Coin netValue = amount.subtract(fee);
        checkArgument(Restrictions.isAboveDust(netValue),
                "The amount is too low (dust limit).");

        tx.addOutput(netValue, Address.fromString(params, toAddress));

        SendRequest sendRequest = SendRequest.forTx(tx);
        sendRequest.fee = fee;
        sendRequest.feePerKb = Coin.ZERO;
        sendRequest.ensureMinRequiredFee = false;
        sendRequest.aesKey = aesKey;
        sendRequest.shuffleOutputs = false;
        Set<AddressEntry> addressEntries = fromAddresses.stream()
                .map(address -> {
                    Optional<AddressEntry> addressEntryOptional = findAddressEntry(address, AddressEntry.Context.AVAILABLE);
                    if (!addressEntryOptional.isPresent())
                        addressEntryOptional = findAddressEntry(address, AddressEntry.Context.OFFER_FUNDING);
                    if (!addressEntryOptional.isPresent())
                        addressEntryOptional = findAddressEntry(address, AddressEntry.Context.TRADE_PAYOUT);
                    if (!addressEntryOptional.isPresent())
                        addressEntryOptional = findAddressEntry(address, AddressEntry.Context.ARBITRATOR);
                    return addressEntryOptional;
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());
        if (addressEntries.isEmpty())
            throw new AddressEntryException("No Addresses for withdraw found in our wallet");

        sendRequest.coinSelector = new BtcCoinSelector(walletsSetup.getAddressesFromAddressEntries(addressEntries),
                preferences.getIgnoreDustThreshold());
        Optional<AddressEntry> addressEntryOptional = Optional.empty();

        if (changeAddress != null)
            addressEntryOptional = findAddressEntry(changeAddress, AddressEntry.Context.AVAILABLE);

        AddressEntry changeAddressAddressEntry = addressEntryOptional.orElseGet(this::getFreshAddressEntry);
        checkNotNull(changeAddressAddressEntry, "change address must not be null");
        sendRequest.changeAddress = changeAddressAddressEntry.getAddress();
        return sendRequest;
    }

    // We ignore utxos which are considered dust attacks for spying on users' wallets.
    // The ignoreDustThreshold value is set in the preferences. If not set we use default non dust
    // value of 546 sat.
    @Override
    protected boolean isDustAttackUtxo(TransactionOutput output) {
        return output.getValue().value < preferences.getIgnoreDustThreshold();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Refund payoutTx
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Transaction createRefundPayoutTx(Coin buyerAmount,
                                            Coin sellerAmount,
                                            Coin fee,
                                            String buyerAddressString,
                                            String sellerAddressString)
            throws AddressFormatException, InsufficientMoneyException, WalletException, TransactionVerificationException {
        Transaction tx = new Transaction(params);
        Preconditions.checkArgument(buyerAmount.add(sellerAmount).isPositive(),
                "The sellerAmount + buyerAmount must be positive.");
        // buyerAmount can be 0
        if (buyerAmount.isPositive()) {
            Preconditions.checkArgument(Restrictions.isAboveDust(buyerAmount),
                    "The buyerAmount is too low (dust limit).");

            tx.addOutput(buyerAmount, Address.fromString(params, buyerAddressString));
        }
        // sellerAmount can be 0
        if (sellerAmount.isPositive()) {
            Preconditions.checkArgument(Restrictions.isAboveDust(sellerAmount),
                    "The sellerAmount is too low (dust limit).");

            tx.addOutput(sellerAmount, Address.fromString(params, sellerAddressString));
        }

        SendRequest sendRequest = SendRequest.forTx(tx);
        sendRequest.fee = fee;
        sendRequest.feePerKb = Coin.ZERO;
        sendRequest.ensureMinRequiredFee = false;
        sendRequest.aesKey = aesKey;
        sendRequest.shuffleOutputs = false;
        sendRequest.coinSelector = new BtcCoinSelector(walletsSetup.getAddressesByContext(AddressEntry.Context.AVAILABLE),
                preferences.getIgnoreDustThreshold());
        sendRequest.changeAddress = getFreshAddressEntry().getAddress();

        checkNotNull(wallet);
        wallet.completeTx(sendRequest);

        Transaction resultTx = sendRequest.tx;
        checkWalletConsistency(wallet);
        verifyTransaction(resultTx);

        WalletService.printTx("createRefundPayoutTx", resultTx);

        return resultTx;
    }
}
