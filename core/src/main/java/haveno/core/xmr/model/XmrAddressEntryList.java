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

package haveno.core.xmr.model;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.protobuf.Message;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.PersistableEnvelope;
import haveno.common.proto.persistable.PersistedDataHost;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;



/**
 * The AddressEntries was previously stored as list, now as hashSet. We still keep the old name to reflect the
 * associated protobuf message.
 */
@Slf4j
public final class XmrAddressEntryList implements PersistableEnvelope, PersistedDataHost {
    transient private PersistenceManager<XmrAddressEntryList> persistenceManager;
    private final Set<XmrAddressEntry> entrySet = new CopyOnWriteArraySet<>();

    @Inject
    public XmrAddressEntryList(PersistenceManager<XmrAddressEntryList> persistenceManager) {
        this.persistenceManager = persistenceManager;

        this.persistenceManager.initialize(this, PersistenceManager.Source.PRIVATE);
    }

    @Override
    public void readPersisted(Runnable completeHandler) {
        persistenceManager.readPersisted(persisted -> {
            entrySet.clear();
            entrySet.addAll(persisted.entrySet);
            completeHandler.run();
        },
        completeHandler);
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

    public ImmutableList<XmrAddressEntry> getAddressEntriesAsListImmutable() {
        return ImmutableList.copyOf(entrySet);
    }

    public boolean addAddressEntry(XmrAddressEntry addressEntry) {
        boolean entryWithSameOfferIdAndContextAlreadyExist = entrySet.stream().anyMatch(e -> {
            if (addressEntry.getOfferId() != null) {
                return addressEntry.getOfferId().equals(e.getOfferId()) && addressEntry.getContext() == e.getContext();
            }
            return false;
        });
        if (entryWithSameOfferIdAndContextAlreadyExist) {
            throw new IllegalArgumentException("We have an address entry with the same offer ID and context. We do not add the new one. addressEntry=" + addressEntry);
        }

        boolean setChangedByAdd = entrySet.add(addressEntry);
        if (setChangedByAdd) requestPersistence();
        return setChangedByAdd;
    }

    public void swapToAvailable(XmrAddressEntry addressEntry) {
        boolean setChangedByRemove = entrySet.remove(addressEntry);
        boolean setChangedByAdd = entrySet.add(new XmrAddressEntry(addressEntry.getSubaddressIndex(), addressEntry.getAddressString(),
                XmrAddressEntry.Context.AVAILABLE));
        if (setChangedByRemove || setChangedByAdd) {
            requestPersistence();
        }
    }

    public XmrAddressEntry swapAvailableToAddressEntryWithOfferId(XmrAddressEntry addressEntry,
                                                               XmrAddressEntry.Context context,
                                                               String offerId) {
        // remove old entry
        boolean setChangedByRemove = entrySet.remove(addressEntry);

        // add new entry
        final XmrAddressEntry newAddressEntry = new XmrAddressEntry(addressEntry.getSubaddressIndex(), addressEntry.getAddressString(), context, offerId, null);
        boolean setChangedByAdd = false;
        try {
            setChangedByAdd = addAddressEntry(newAddressEntry);
        } catch (Exception e) {
            entrySet.add(addressEntry); // undo change if error
            throw e;
        }
        
        if (setChangedByRemove || setChangedByAdd)
            requestPersistence();

        return newAddressEntry;
    }

    public void requestPersistence() {
        persistenceManager.requestPersistence();
    }

    @Override
    public String toString() {
        return "XmrAddressEntryList{" +
                ",\n     entrySet=" + entrySet +
                "\n}";
    }
}
