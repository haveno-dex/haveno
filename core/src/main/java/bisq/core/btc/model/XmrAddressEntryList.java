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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.inject.Inject;
import com.google.protobuf.Message;

import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.proto.persistable.UserThreadMappedPersistableEnvelope;
import bisq.common.storage.Storage;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroAccount;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroWalletListener;

/**
 * The List supporting our persistence solution.
 */
@ToString
@Slf4j
public final class XmrAddressEntryList implements UserThreadMappedPersistableEnvelope, PersistedDataHost {
    transient private Storage<XmrAddressEntryList> storage;
    transient private MoneroWalletRpc wallet;
    @Getter
    private List<XmrAddressEntry> list;

    @Inject
    public XmrAddressEntryList(Storage<XmrAddressEntryList> storage) {
        this.storage = storage;
    }

    @Override
    public void readPersisted() {
        XmrAddressEntryList persisted = storage.initAndGetPersisted(this, 50);
        if (persisted != null)
            list = new ArrayList<>(persisted.getList());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private XmrAddressEntryList(List<XmrAddressEntry> list) {
        this.list = list;
    }

    public static XmrAddressEntryList fromProto(protobuf.XmrAddressEntryList proto) {
        return new XmrAddressEntryList(new ArrayList<>(proto.getXmrAddressEntryList().stream().map(XmrAddressEntry::fromProto).collect(Collectors.toList())));
    }

    @Override
    public Message toProtoMessage() {
        // We clone list as we got ConcurrentModificationExceptions
        List<XmrAddressEntry> clone = new ArrayList<>(list);
        List<protobuf.XmrAddressEntry> addressEntries = clone.stream()
                .map(XmrAddressEntry::toProtoMessage)
                .collect(Collectors.toList());

        return protobuf.PersistableEnvelope.newBuilder()
                .setXmrAddressEntryList(protobuf.XmrAddressEntryList.newBuilder()
                        .addAllXmrAddressEntry(addressEntries))
                .build();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onWalletReady(MoneroWalletRpc wallet) {
        this.wallet = wallet;

        if (list != null) {
          
        } else {
            list = new ArrayList<>();
            MoneroAccount account = wallet.createAccount();
            add(new XmrAddressEntry(account.getIndex(), account.getPrimaryAddress(), XmrAddressEntry.Context.ARBITRATOR));  // TODO (woodser): what is this?

            // In case we restore from seed words and have balance we need to add the relevant addresses to our list.
            // IssuedReceiveAddresses does not contain all addresses where we expect balance so we need to listen to
            // incoming txs at blockchain sync to add the rest.
            if (wallet.getBalance().compareTo(new BigInteger("0")) > 0) {
               wallet.getAccounts().forEach(acct -> {
                 log.info("Create XmrAddressEntry for IssuedReceiveAddress. address={}", account.getPrimaryAddress());
                 if (acct.getIndex() != 0) add(new XmrAddressEntry(acct.getIndex(), acct.getPrimaryAddress(), XmrAddressEntry.Context.AVAILABLE));
             });
            }
            persist();
        }

        // We add those listeners to get notified about potential new transactions and
        // add an address entry list in case it does not exist yet. This is mainly needed for restore from seed words
        // but can help as well in case the XmrAddressEntry list would miss an address where the wallet was received
        // funds (e.g. if the user sends funds to an address which has not been provided in the main UI - like from the
        // wallet details window).
//        wallet.addListener(new MoneroWalletListener() {
//          @Override public void onOutputReceived(MoneroOutputWallet output) { updateList(output); }
//          @Override public void onOutputSpent(MoneroOutputWallet output) { updateList(output); }
//        });
    }
    
    private void updateList(MoneroOutputWallet output) {
      if (output.getAccountIndex() == 0) return;
      String address = wallet.getAddress(output.getAccountIndex(), output.getSubaddressIndex());
      if (!listContainsEntryWithAddress(address)) list.add(new XmrAddressEntry(output.getAccountIndex(), address, XmrAddressEntry.Context.AVAILABLE));
    }

    private boolean listContainsEntryWithAddress(String addressString) {
        return list.stream().anyMatch(xmrAddressEntry -> Objects.equals(xmrAddressEntry.getAddressString(), addressString));
    }

    private boolean add(XmrAddressEntry xmrAddressEntry) {
        return list.add(xmrAddressEntry);
    }

    private boolean remove(XmrAddressEntry xmrAddressEntry) {
        return list.remove(xmrAddressEntry);
    }

    public XmrAddressEntry addAddressEntry(XmrAddressEntry xmrAddressEntry) {
        boolean changed = add(xmrAddressEntry);
        if (changed)
            persist();
        return xmrAddressEntry;
    }

    public void swapTradeToSavings(String offerId) {
        list.stream().filter(xmrAddressEntry -> offerId.equals(xmrAddressEntry.getOfferId()))
                .findAny().ifPresent(this::swapToAvailable);
    }

    public void swapToAvailable(XmrAddressEntry xmrAddressEntry) {
        boolean changed1 = remove(xmrAddressEntry);
        boolean changed2 = add(new XmrAddressEntry(xmrAddressEntry.getAccountIndex(), xmrAddressEntry.getAddressString(), XmrAddressEntry.Context.AVAILABLE));
        if (changed1 || changed2)
            persist();
    }

    public XmrAddressEntry swapAvailableToAddressEntryWithOfferId(XmrAddressEntry xmrAddressEntry, XmrAddressEntry.Context context, String offerId) {
        boolean changed1 = remove(xmrAddressEntry);
        final XmrAddressEntry newAddressEntry = new XmrAddressEntry(xmrAddressEntry.getAccountIndex(), xmrAddressEntry.getAddressString(), context, offerId, null);
        boolean changed2 = add(newAddressEntry);
        if (changed1 || changed2)
            persist();

        return newAddressEntry;
    }

    public void persist() {
        storage.queueUpForSave(50);
    }

    public Stream<XmrAddressEntry> stream() {
        return list.stream();
    }
}
