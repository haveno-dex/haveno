package bisq.core.btc.wallet;

import java.io.File;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import bisq.core.btc.exceptions.AddressEntryException;
import bisq.core.btc.listeners.XmrBalanceListener;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.model.XmrAddressEntryList;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.util.ParsingUtils;
import javafx.application.Platform;
import lombok.Getter;
import monero.common.MoneroRpcConnection;
import monero.daemon.model.MoneroNetworkType;
import monero.wallet.MoneroWalletJni;
import monero.wallet.model.MoneroAccount;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroSubaddress;
import monero.wallet.model.MoneroTransfer;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletConfig;
import monero.wallet.model.MoneroWalletListener;
import monero.wallet.model.MoneroWalletListenerI;

public class XmrWalletService {
  private static final Logger log = LoggerFactory.getLogger(XmrWalletService.class);

  private File directory;
  private final XmrAddressEntryList addressEntryList;
  protected final CopyOnWriteArraySet<XmrBalanceListener> balanceListeners = new CopyOnWriteArraySet<>();
  protected final CopyOnWriteArraySet<MoneroWalletListenerI> walletListeners = new CopyOnWriteArraySet<>();
  private Map<String, MoneroWalletJni> openWallets;
  
  @Getter
  private MoneroWalletJni wallet;
  
  @Inject
  XmrWalletService(WalletsSetup walletsSetup,
                   XmrAddressEntryList addressEntryList) {
    
    this.addressEntryList = addressEntryList;
    this.openWallets = new HashMap<String, MoneroWalletJni>();

    walletsSetup.addSetupCompletedHandler(() -> {
      this.directory = walletsSetup.getWalletConfig().directory();
      wallet = walletsSetup.getXmrWallet();
      wallet.addListener(new MoneroWalletListener() {
        @Override
        public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) { }

        @Override
        public void onNewBlock(long height) { }
        
        @Override
        public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
          Platform.runLater(new Runnable() {  // jni wallet runs on separate thread which cannot update fx
            @Override public void run() {
              notifyBalanceListeners();
            }
          });
        }
      });
    });
  }
  
  // TODO (woodser): move hard-coded values to config
  public MoneroWalletJni getOrCreateMultisigWallet(String tradeId) {
    String path = new File(directory, "xmr_multisig_trade_" + tradeId).getPath();
    MoneroRpcConnection conn = new MoneroRpcConnection("http://localhost:38081", "superuser", "abctesting123");
    MoneroWalletJni multisigWallet = null;
    if (openWallets.containsKey(tradeId)) return openWallets.get(tradeId);
    else if (MoneroWalletJni.walletExists(path)) {
      multisigWallet = MoneroWalletJni.openWallet(new MoneroWalletConfig()
              .setPath(path)
              .setPassword("abctesting123")
              .setNetworkType(MoneroNetworkType.STAGENET)
              .setServer(conn));
    } else {
      multisigWallet = MoneroWalletJni.createWallet(new MoneroWalletConfig()
              .setPath(path)
              .setPassword("abctesting123")
              .setNetworkType(MoneroNetworkType.STAGENET)
              .setServer(conn));
    }
    openWallets.put(tradeId, multisigWallet);
    multisigWallet.startSyncing();
    return multisigWallet;
  }
  
  public XmrAddressEntry getArbitratorAddressEntry() {
      XmrAddressEntry.Context context = XmrAddressEntry.Context.ARBITRATOR;
      Optional<XmrAddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream()
              .filter(e -> context == e.getContext())
              .findAny();
      return getOrCreateAddressEntry(context, addressEntry);
  }
  
  public XmrAddressEntry recoverAddressEntry(String offerId, String address, XmrAddressEntry.Context context) {
      var available = findAddressEntry(address, XmrAddressEntry.Context.AVAILABLE);
      if (!available.isPresent())
          return null;
      return addressEntryList.swapAvailableToAddressEntryWithOfferId(available.get(), context, offerId);
  }
  
  public XmrAddressEntry getNewAddressEntry(String offerId, XmrAddressEntry.Context context) {
    if (context == XmrAddressEntry.Context.TRADE_PAYOUT) {
      XmrAddressEntry entry = new XmrAddressEntry(0, wallet.createSubaddress(0).getAddress(), context, offerId, null);
      addressEntryList.addAddressEntry(entry);
      return entry;
    } else {
      MoneroAccount account = wallet.createAccount();
      XmrAddressEntry entry = new XmrAddressEntry(account.getIndex(), account.getPrimaryAddress(), context, offerId, null);
      addressEntryList.addAddressEntry(entry);
      return entry;
    }
  }
  
  public XmrAddressEntry getOrCreateAddressEntry(String offerId, XmrAddressEntry.Context context) {
    Optional<XmrAddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream()
            .filter(e -> offerId.equals(e.getOfferId()))
            .filter(e -> context == e.getContext())
            .findAny();
    if (addressEntry.isPresent()) {
        return addressEntry.get();
    } else {
        // We try to use available and not yet used entries // TODO (woodser): "available" entries is not applicable in xmr which uses account 0 for main wallet and subsequent accounts for reserved trades, refactor address association for xmr?
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
  
  private XmrAddressEntry getOrCreateAddressEntry(XmrAddressEntry.Context context, Optional<XmrAddressEntry> addressEntry) {
      if (addressEntry.isPresent()) {
        return addressEntry.get();
      } else {
        if (context == XmrAddressEntry.Context.ARBITRATOR) {
          MoneroSubaddress subaddress = wallet.createSubaddress(0);
          XmrAddressEntry entry = new XmrAddressEntry(0, subaddress.getAddress(), context);
          addressEntryList.addAddressEntry(entry);
          return entry;
        } else {
          throw new RuntimeException("XmrWalletService.getOrCreateAddressEntry(context, addressEntry) not implemented for non-arbitrator context");	// TODO (woodser): this method used with non-arbitrator context?
        }
      }
  }
  
  public Optional<XmrAddressEntry> getAddressEntry(String offerId, XmrAddressEntry.Context context) {
    return getAddressEntryListAsImmutableList().stream()
            .filter(e -> offerId.equals(e.getOfferId()))
            .filter(e -> context == e.getContext())
            .findAny();
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
  
  public void resetAddressEntriesForOpenOffer(String offerId) {
    log.info("resetAddressEntriesForOpenOffer offerId={}", offerId);
    swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.OFFER_FUNDING);
    swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.RESERVED_FOR_TRADE);
  }
  
  public void resetAddressEntriesForPendingTrade(String offerId) {
      swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.MULTI_SIG);
      // We swap also TRADE_PAYOUT to be sure all is cleaned up. There might be cases where a user cannot send the funds
      // to an external wallet directly in the last step of the trade, but the funds are in the Bisq wallet anyway and
      // the dealing with the external wallet is pure UI thing. The user can move the funds to the wallet and then
      // send out the funds to the external wallet. As this cleanup is a rare situation and most users do not use
      // the feature to send out the funds we prefer that strategy (if we keep the address entry it might cause
      // complications in some edge cases after a SPV resync).
      swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.TRADE_PAYOUT);
  }
  
  private Optional<XmrAddressEntry> findAddressEntry(String address, XmrAddressEntry.Context context) {
      return getAddressEntryListAsImmutableList().stream()
              .filter(e -> address.equals(e.getAddressString()))
              .filter(e -> context == e.getContext())
              .findAny();
  }
  
  public List<XmrAddressEntry> getAvailableAddressEntries() {
    return getAddressEntryListAsImmutableList().stream()
            .filter(addressEntry -> XmrAddressEntry.Context.AVAILABLE == addressEntry.getContext())
            .collect(Collectors.toList());
}
  
  public List<XmrAddressEntry> getAddressEntriesForTrade() {
      return getAddressEntryListAsImmutableList().stream()
            .filter(addressEntry -> XmrAddressEntry.Context.MULTI_SIG == addressEntry.getContext() ||
                    XmrAddressEntry.Context.TRADE_PAYOUT == addressEntry.getContext())
            .collect(Collectors.toList());
  }
  
  public List<XmrAddressEntry> getAddressEntries(XmrAddressEntry.Context context) {
      return getAddressEntryListAsImmutableList().stream()
              .filter(addressEntry -> context == addressEntry.getContext())
              .collect(Collectors.toList());
  }
  
  public List<XmrAddressEntry> getFundedAvailableAddressEntries() {
      return getAvailableAddressEntries().stream()
              .filter(addressEntry -> getBalanceForAccount(addressEntry.getAccountIndex()).isPositive())
              .collect(Collectors.toList());
  }
  
  public List<XmrAddressEntry> getAddressEntryListAsImmutableList() {
    return ImmutableList.copyOf(addressEntryList.getList());
  }
  
  public boolean isAccountUnused(int accountIndex) {
    return accountIndex != 0 && getBalanceForAccount(accountIndex).value == 0;
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
    return wallet != null ? Coin.valueOf(wallet.getBalance(0).longValueExact()) : Coin.ZERO;
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
  
  public List<MoneroTxWallet> getTransactions(boolean includeDead) {
      return wallet.getTxs(new MoneroTxQuery().setIsFailed(includeDead ? null : false));
  }
  
  public void shutDown() {
    System.out.println("XmrWalletService.shutDown()");
    for (String openWalletKey : openWallets.keySet()) {
      MoneroWalletJni openWallet = openWallets.get(openWalletKey);
      new Thread(new Runnable() {
        @Override
        public void run() {
          openWallet.close(true);
        }
      }).run();
    }
  }
  
  ///////////////////////////////////////////////////////////////////////////////////////////
  // Withdrawal Send
  ///////////////////////////////////////////////////////////////////////////////////////////

  public String sendFunds(int fromAccountIndex,
                          String toAddress,
                          Coin receiverAmount,
                          @SuppressWarnings("SameParameterValue") XmrAddressEntry.Context context) throws AddressFormatException,
          AddressEntryException, InsufficientMoneyException {
      MoneroTxWallet tx = wallet.createTx(new MoneroTxConfig()
              .setAccountIndex(fromAccountIndex)
              .setAddress(toAddress)
              .setAmount(BigInteger.valueOf(receiverAmount.value).multiply(ParsingUtils.XMR_SATOSHI_MULTIPLIER))
              .setRelay(true));

      printTxs("sendFunds", tx);
      return tx.getHash();
  }
  
  ///////////////////////////////////////////////////////////////////////////////////////////
  // Util
  ///////////////////////////////////////////////////////////////////////////////////////////
  
  public static void printTxs(String tracePrefix, MoneroTxWallet... txs) {
    StringBuilder sb = new StringBuilder();
    for (MoneroTxWallet tx : txs) sb.append('\n' + tx.toString());
    log.info("\n" + tracePrefix + ":" + sb.toString());
  }
  
  private void notifyBalanceListeners() {
    for (XmrBalanceListener balanceListener : balanceListeners) {
      Coin balance;
      if (balanceListener.getAccountIndex() != null && balanceListener.getAccountIndex() != 0) {
        balance = getBalanceForAccount(balanceListener.getAccountIndex());
      } else {
        balance = getAvailableConfirmedBalance();
      }
      balanceListener.onBalanceChanged(BigInteger.valueOf(balance.value));
    }
  }
  
  /**
   * Wraps a MoneroWalletListener to notify the Misq application.
   */
  public class MisqWalletListener extends MoneroWalletListener {
    
    private MoneroWalletListener listener;
    
    public MisqWalletListener(MoneroWalletListener listener) {
      this.listener = listener;
    }
    
    @Override
    public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) {
      Platform.runLater(new Runnable() {  // jni wallet runs on separate thread which cannot update fx
        @Override public void run() {
          listener.onSyncProgress(height, startHeight, endHeight, percentDone, message);
        }
      });
    }

    @Override
    public void onNewBlock(long height) {
      Platform.runLater(new Runnable() {  // jni wallet runs on separate thread which cannot update fx
        @Override public void run() {
          listener.onNewBlock(height);
        }
      });
    }
    
    @Override
    public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
      Platform.runLater(new Runnable() {  // jni wallet runs on separate thread which cannot update fx
        @Override public void run() {
          listener.onBalancesChanged(newBalance, newUnlockedBalance);
        }
      });
    }

    @Override
    public void onOutputReceived(MoneroOutputWallet output) {
      Platform.runLater(new Runnable() {  // jni wallet runs on separate thread which cannot update fx
        @Override public void run() {
          listener.onOutputReceived(output);
        }
      });
    }

    @Override
    public void onOutputSpent(MoneroOutputWallet output) {
      Platform.runLater(new Runnable() {  // jni wallet runs on separate thread which cannot update fx
        @Override public void run() {
          listener.onOutputSpent(output);
        }
      });
    }
  }
}
