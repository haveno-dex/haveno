package bisq.core.btc.wallet;

import javax.inject.Inject;

import bisq.core.btc.setup.WalletsSetup;
import lombok.Getter;
import monero.wallet.MoneroWalletJni;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroWalletListener;

public class XmrWalletService {
  
  @Getter
  private MoneroWalletJni wallet;
  
  @Inject
  XmrWalletService(WalletsSetup walletsSetup) {
    walletsSetup.addSetupCompletedHandler(() -> {
      wallet = walletsSetup.getXmrWallet();
      wallet.addListener(new MoneroWalletListener() { // TODO: notify
        @Override
        public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) { }

        @Override
        public void onNewBlock(long height) { }

        @Override
        public void onOutputReceived(MoneroOutputWallet output) { }

        @Override
        public void onOutputSpent(MoneroOutputWallet output) { }
      });
  });
  }
}
