package haveno.core.account;

import java.io.File;
import java.io.IOException;

import com.google.inject.Inject;

import bisq.common.config.Config;
import bisq.common.file.FileUtil;
import bisq.core.btc.setup.WalletsSetup;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AccountService {
	private final WalletsSetup walletsSetup;
	private final Config config;
	
	@Inject
	public AccountService(Config config,
						  WalletsSetup walletsSetup) {
		this.walletsSetup = walletsSetup;
		this.config = config;
	}
	
	public void createAccount() {
		config.createSubDirectories();
		this.walletsSetup.initialize(null,               
				() -> {
	                // We only check one wallet as we apply encryption to all or none
//	                if (walletsManager.areWalletsEncrypted() && !coreContext.isApiUser()) {
//	                    walletPasswordHandler.run();
//	                } else {
//	                    if (isSpvResyncRequested && !coreContext.isApiUser()) {
//	                        if (showFirstPopupIfResyncSPVRequestedHandler != null)
//	                            showFirstPopupIfResyncSPVRequestedHandler.run();
//	                    } else {
//	                        walletInitializedHandler.run();
//	                    }
//	                }
	            },
	            exception -> {
//	                if (exception instanceof InvalidHostException && showPopupIfInvalidBtcConfigHandler != null) {
//	                    showPopupIfInvalidBtcConfigHandler.run();
//	                } else {
//	                    walletServiceException.set(exception);
//	                }
	            });
	}
	
	public boolean accountExists() {
		return true;
	}
	
	public boolean isAccountOpen() {
		return true;
	}
	
	public void openAccount() {
	}
	
	public void closeAccount() {
		this.walletsSetup.shutDown();
	}
	
	public void backupAccount() {
		try {
			this.walletsSetup.backupWallets();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
	public void deleteAccount() {
		try {
			this.walletsSetup.shutDown();
			var dir = new File(config.appDataDir(), config.baseCurrencyNetwork().name().toLowerCase());
			FileUtil.deleteDirectory(dir);
		} catch (IOException e) {
			log.error("Could not delete directory " + e.getMessage());
			e.printStackTrace();
		}
				
	}
	
	public void restoreAccount() {
		
	}
	
	public void changePassword() {
		
	}
}
