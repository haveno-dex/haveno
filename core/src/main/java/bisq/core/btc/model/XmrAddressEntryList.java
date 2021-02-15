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

package bisq.core.btc.model;

import bisq.common.persistence.PersistenceManager;
import bisq.common.proto.persistable.PersistableEnvelope;
import bisq.common.proto.persistable.PersistedDataHost;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.protobuf.Message;
import java.math.BigInteger;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroAccount;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroWalletListener;

/**
 * The AddressEntries was previously stored as list, now as hashSet. We still keep the old name to reflect the
 * associated protobuf message.
 */
@Slf4j
public final class XmrAddressEntryList implements PersistableEnvelope, PersistedDataHost {
    transient private PersistenceManager<XmrAddressEntryList> persistenceManager;
    transient private MoneroWallet wallet;
    private final Set<XmrAddressEntry> entrySet = new CopyOnWriteArraySet<>();

    @Inject
    public XmrAddressEntryList(PersistenceManager<XmrAddressEntryList> persistenceManager) {
        this.persistenceManager = persistenceManager;

        this.persistenceManager.initialize(this, PersistenceManager.Source.PRIVATE);
    }

    @Override
    public void readPersisted() {
        XmrAddressEntryList persisted = persistenceManager.getPersisted();
        if (persisted != null) {
            entrySet.clear();
            entrySet.addAll(persisted.entrySet);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private XmrAddressEntryList(Set<XmrAddressEntry> entrySet) {
        this.entrySet.addAll(entrySet);
    }

    public static XmrAddressEntryList fromProto(protobuf.XmrAddressEntryList proto) {
        Set<XmrAddressEntry> entrySet = proto.getXmrAddressEntryList().stream()
                .map(XmrAddressEntry::fromProto)
                .collect(Collectors.toSet());
        return new XmrAddressEntryList(entrySet);
    }

    @Override
    public Message toProtoMessage() {
        Set<protobuf.XmrAddressEntry> addressEntries = entrySet.stream()
                .map(XmrAddressEntry::toProtoMessage)
                .collect(Collectors.toSet());
        return protobuf.PersistableEnvelope.newBuilder()
                .setXmrAddressEntryList(protobuf.XmrAddressEntryList.newBuilder()
                        .addAllXmrAddressEntry(addressEntries))
                .build();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onWalletReady(MoneroWallet wallet) {
        this.wallet = wallet;

        if (!entrySet.isEmpty()) {
//            Set<XmrAddressEntry> toBeRemoved = new HashSet<>();
//            entrySet.forEach(addressEntry -> {
//                DeterministicKey keyFromPubHash = (DeterministicKey) wallet.findKeyFromPubKeyHash(
//                        addressEntry.getPubKeyHash(),
//                        Script.ScriptType.P2PKH);
//                if (keyFromPubHash != null) {
//                    Address addressFromKey = LegacyAddress.fromKey(Config.baseCurrencyNetworkParameters(), keyFromPubHash);
//                    // We want to ensure key and address matches in case we have address in entry available already
//                    if (addressEntry.getAddress() == null || addressFromKey.equals(addressEntry.getAddress())) {
//                        addressEntry.setDeterministicKey(keyFromPubHash);
//                    } else {
//                        log.error("We found an address entry without key but cannot apply the key as the address " +
//                                        "is not matching. " +
//                                        "We remove that entry as it seems it is not compatible with our wallet. " +
//                                        "addressFromKey={}, addressEntry.getAddress()={}",
//                                addressFromKey, addressEntry.getAddress());
//                        toBeRemoved.add(addressEntry);
//                    }
//                } else {
//                    log.error("Key from addressEntry {} not found in that wallet. We remove that entry. " +
//                            "This is expected at restore from seeds.", addressEntry.toString());
//                    toBeRemoved.add(addressEntry);
//                }
//            });
//
//            toBeRemoved.forEach(entrySet::remove);
        } else {
            // As long the old arbitration domain is not removed from the code base we still support it here.
            MoneroAccount account = wallet.createAccount();
            entrySet.add(new XmrAddressEntry(account.getIndex(), account.getPrimaryAddress(), XmrAddressEntry.Context.ARBITRATOR));
        }

        // In case we restore from seed words and have balance we need to add the relevant addresses to our list.
        // IssuedReceiveAddresses does not contain all addresses where we expect balance so we need to listen to
        // incoming txs at blockchain sync to add the rest.
        if (wallet.getBalance().compareTo(new BigInteger("0")) > 0) {
          wallet.getAccounts().forEach(acct -> {
            log.info("Create XmrAddressEntry for IssuedReceiveAddress. address={}", acct.getPrimaryAddress());
            if (acct.getIndex() != 0) entrySet.add(new XmrAddressEntry(acct.getIndex(), acct.getPrimaryAddress(), XmrAddressEntry.Context.AVAILABLE));
        });
       }

        // We add those listeners to get notified about potential new transactions and
        // add an address entry list in case it does not exist yet. This is mainly needed for restore from seed words
        // but can help as well in case the addressEntry list would miss an address where the wallet was received
        // funds (e.g. if the user sends funds to an address which has not been provided in the main UI - like from the
        // wallet details window).
        wallet.addListener(new MoneroWalletListener() {
          @Override public void onOutputReceived(MoneroOutputWallet output) { maybeAddNewAddressEntry(output); }
          @Override public void onOutputSpent(MoneroOutputWallet output) { maybeAddNewAddressEntry(output); }
        });

        requestPersistence();
    }

    public ImmutableList<XmrAddressEntry> getAddressEntriesAsListImmutable() {
        return ImmutableList.copyOf(entrySet);
    }

    public void addAddressEntry(XmrAddressEntry addressEntry) {
        boolean entryWithSameOfferIdAndContextAlreadyExist = entrySet.stream().anyMatch(e -> {
            if (addressEntry.getOfferId() != null) {
                return addressEntry.getOfferId().equals(e.getOfferId()) && addressEntry.getContext() == e.getContext();
            }
            return false;
        });
        if (entryWithSameOfferIdAndContextAlreadyExist) {
            log.error("We have an address entry with the same offer ID and context. We do not add the new one. " +
                    "addressEntry={}, entrySet={}", addressEntry, entrySet);
            return;
        }

        boolean setChangedByAdd = entrySet.add(addressEntry);
        if (setChangedByAdd)
            requestPersistence();
    }
    
    public void swapToAvailable(XmrAddressEntry addressEntry) {
        boolean setChangedByRemove = entrySet.remove(addressEntry);
        boolean setChangedByAdd = entrySet.add(new XmrAddressEntry(addressEntry.getAccountIndex(), addressEntry.getAddressString(),
                XmrAddressEntry.Context.AVAILABLE));
        if (setChangedByRemove || setChangedByAdd) {
            requestPersistence();
        }
    }

    public XmrAddressEntry swapAvailableToAddressEntryWithOfferId(XmrAddressEntry addressEntry,
                                                               XmrAddressEntry.Context context,
                                                               String offerId) {
        boolean setChangedByRemove = entrySet.remove(addressEntry);
        final XmrAddressEntry newAddressEntry = new XmrAddressEntry(addressEntry.getAccountIndex(), addressEntry.getAddressString(), context, offerId, null);
        boolean setChangedByAdd = entrySet.add(newAddressEntry);
        if (setChangedByRemove || setChangedByAdd)
            requestPersistence();

        return newAddressEntry;
    }

    public void requestPersistence() {
        persistenceManager.requestPersistence();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void maybeAddNewAddressEntry(MoneroOutputWallet output) {
      if (output.getAccountIndex() == 0) return;
      String address = wallet.getAddress(output.getAccountIndex(), output.getSubaddressIndex());
      if (!isAddressInEntries(address)) addAddressEntry(new XmrAddressEntry(output.getAccountIndex(), address, XmrAddressEntry.Context.AVAILABLE));
    }

    private boolean isAddressInEntries(String address) {
      for (XmrAddressEntry entry : entrySet) {
        if (entry.getAddressString().equals(address)) return true;
      }
      return false;
    }

    @Override
    public String toString() {
        return "XmrAddressEntryList{" +
                ",\n     entrySet=" + entrySet +
                "\n}";
    }
}
