package bisq.core.btc.wallet;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.bitcoinj.core.Coin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import bisq.core.btc.listeners.XmrBalanceListener;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.model.XmrAddressEntryList;
import bisq.core.btc.setup.WalletsSetup;
import javafx.application.Platform;
import lombok.Getter;
import monero.wallet.MoneroWalletJni;
import monero.wallet.model.MoneroAccount;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroTransfer;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;

public class XmrWalletService {
  private static final Logger log = LoggerFactory.getLogger(XmrWalletService.class);

  private final XmrAddressEntryList addressEntryList;
  protected final CopyOnWriteArraySet<XmrBalanceListener> balanceListeners = new CopyOnWriteArraySet<>();
  
  @Getter
  private MoneroWalletJni wallet;
  
  @Inject
  XmrWalletService(WalletsSetup walletsSetup,
                   XmrAddressEntryList addressEntryList) {
    
    this.addressEntryList = addressEntryList;

    walletsSetup.addSetupCompletedHandler(() -> {
      wallet = walletsSetup.getXmrWallet();
      wallet.addListener(new MoneroWalletListener() { // TODO: notify
        @Override
        public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) { }

        @Override
        public void onNewBlock(long height) { }

        @Override
        public void onOutputReceived(MoneroOutputWallet output) {
          Platform.runLater(new Runnable() {  // jni wallet runs on separate thread which cannot update fx
            @Override public void run() {
              notifyBalanceListeners(output);
            }
          });
        }

        @Override
        public void onOutputSpent(MoneroOutputWallet output) {
          Platform.runLater(new Runnable() {
            @Override public void run() {
              notifyBalanceListeners(output);
            }
          });
        }
      });
    });
  }
  
  public XmrAddressEntry getOrCreateAddressEntry(String offerId, XmrAddressEntry.Context context) {
    Optional<XmrAddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream()
            .filter(e -> offerId.equals(e.getOfferId()))
            .filter(e -> context == e.getContext())
            .findAny();
    if (addressEntry.isPresent()) {
        return addressEntry.get();
    } else {
        // We try to use available and not yet used entries
        Optional<XmrAddressEntry> emptyAvailableAddressEntry = getAddressEntryListAsImmutableList().stream()
                .filter(e -> XmrAddressEntry.Context.AVAILABLE == e.getContext())
                .filter(e -> isAccountUnused(e.getAccountIndex()))
                .findAny();
        if (emptyAvailableAddressEntry.isPresent()) {
            return addressEntryList.swapAvailableToAddressEntryWithOfferId(emptyAvailableAddressEntry.get(), context, offerId);
        } else {
            MoneroAccount account = wallet.createAccount();          
            XmrAddressEntry entry = new XmrAddressEntry(account.getIndex(), account.getPrimaryAddress(), context, offerId, null);
            addressEntryList.addAddressEntry(entry);
            return entry;
        }
    }
  }
  
  public void resetAddressEntriesForOpenOffer(String offerId) {
    log.info("resetAddressEntriesForOpenOffer offerId={}", offerId);
    swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.OFFER_FUNDING);
    swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.RESERVED_FOR_TRADE);
  }
  
  public void swapTradeEntryToAvailableEntry(String offerId, XmrAddressEntry.Context context) {
    Optional<XmrAddressEntry> addressEntryOptional = getAddressEntryListAsImmutableList().stream()
            .filter(e -> offerId.equals(e.getOfferId()))
            .filter(e -> context == e.getContext())
            .findAny();
    addressEntryOptional.ifPresent(e -> {
        log.info("swap addressEntry with address {} and offerId {} from context {} to available",
                e.getAddressString(), e.getOfferId(), context);
        addressEntryList.swapToAvailable(e);
        saveAddressEntryList();
    });
  }
  
  public List<XmrAddressEntry> getAddressEntryListAsImmutableList() {
    return ImmutableList.copyOf(addressEntryList.getList());
  }
  
  public boolean isAccountUnused(int accountIndex) {
    return getBalanceForAccount(accountIndex).value == 0;
    //return !wallet.getSubaddress(accountIndex, 0).isUsed(); // TODO: isUsed() does not include unconfirmed funds
  }
  
  public Coin getBalanceForAccount(int accountIndex) {
    
    // get wallet balance
    BigInteger balance = wallet.getBalance(accountIndex);
    
    // balance from xmr wallet does not include unconfirmed funds, so add them  // TODO: support lower in stack?
    for (MoneroTxWallet unconfirmedTx : wallet.getTxs(new MoneroTxQuery().setIsConfirmed(false))) {
      for (MoneroTransfer transfer : unconfirmedTx.getTransfers()) {
        if (transfer.getAccountIndex() == accountIndex) {
          balance = transfer.isIncoming() ? balance.add(transfer.getAmount()) : balance.subtract(transfer.getAmount());
        }
      }
    }
    
    System.out.println("Returning balance for account " + accountIndex + ": " + balance.longValueExact());
    
    return Coin.valueOf(balance.longValueExact());
  }
  
  public Coin getAvailableConfirmedBalance() {
    return wallet != null ? Coin.valueOf(wallet.getUnlockedBalance(0).longValueExact()) : Coin.ZERO;
  }
  
  public Coin getSavingWalletBalance() {
    return Coin.valueOf(getFundedAvailableAddressEntries().stream()
            .mapToLong(addressEntry -> getBalanceForAccount(addressEntry.getAccountIndex()).value)
            .sum());
  }
  
  public List<XmrAddressEntry> getFundedAvailableAddressEntries() {
    return getAvailableAddressEntries().stream()
            .filter(addressEntry -> getBalanceForAccount(addressEntry.getAccountIndex()).value > 0)
            .collect(Collectors.toList());
  }
  
  public List<XmrAddressEntry> getAvailableAddressEntries() {
    return getAddressEntryListAsImmutableList().stream()
            .filter(addressEntry -> XmrAddressEntry.Context.AVAILABLE == addressEntry.getContext())
            .collect(Collectors.toList());
  }
  
  public void addBalanceListener(XmrBalanceListener listener) {
    balanceListeners.add(listener);
  }

  public void removeBalanceListener(XmrBalanceListener listener) {
    balanceListeners.remove(listener);
  }
  
  public void saveAddressEntryList() {
    addressEntryList.persist();
  }
  
  private void notifyBalanceListeners(MoneroOutputWallet output) {
    for (XmrBalanceListener balanceListener : balanceListeners) {
      Coin balance;
      if (balanceListener.getAccountIndex() != null && balanceListener.getAccountIndex() != 0) {
        balance = getBalanceForAccount(balanceListener.getAccountIndex());
      } else {
        balance = getAvailableConfirmedBalance();
      }
      balanceListener.onBalanceChanged(BigInteger.valueOf(balance.value), output);
    }
  }
}
