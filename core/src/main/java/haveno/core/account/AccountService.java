package haveno.core.account;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import com.google.inject.Inject;

import bisq.common.config.Config;
import bisq.common.file.FileUtil;
import bisq.common.util.Zip;
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
	
	public BufferedInputStream backupAccount() {
//		try {
			//this.walletsSetup.backupWallets();
			File sourceDirectory = new File(config.appDataDir.getAbsolutePath());
			var zipName = config.appDataDir.getAbsolutePath() + "/xmr_stagenet.zip";
			File z = new File(zipName);
			if (z.exists())
				z.delete();
			new Zip().compressDirectory(config.appDataDir.getAbsolutePath(), zipName);
			
          	 FileInputStream fis = null;
			try {
				fis = new FileInputStream(zipName);
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return new BufferedInputStream(fis);
			
//		} catch (IOException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
		
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
