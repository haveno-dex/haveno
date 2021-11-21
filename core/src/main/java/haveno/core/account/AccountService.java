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
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import haveno.core.account.exceptions.AccountException;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroUtils;

@Slf4j
public class AccountService {
	private final WalletsSetup walletsSetup;
	private final Config config;
	private boolean walletsInitialized = false;
	
	@Inject
	public AccountService(Config config,
						  WalletsSetup walletsSetup
						  ) {
		this.walletsSetup = walletsSetup;
		this.config = config;
	}
	
	public void createAccount(String password) throws AccountException{
		if (accountExists()) { 
			throw new AccountException("Account already exists!");
		}
		config.setHavenoWalletPassword(password);
		config.createSubDirectories();
		walletsSetup.addSetupCompletedHandler(() -> {
			walletsInitialized = true;
		});
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
		return walletsSetup.btcAccountExists() && walletsSetup.xmrAccountExists();
	}
	
	public boolean isAccountOpen() {
		try {
			if (!walletsInitialized) return false;
			var walletConfig = this.walletsSetup.getWalletConfig();
			if (walletConfig == null) return false;
			if (!walletConfig.isRunning()) return false;
			if(!walletConfig.stateStartingOrRunning()) return false;
			
			//if authenticated, should return balances
			this.walletsSetup.getXmrWallet().getBalance();
			this.walletsSetup.getBtcWallet().getBalance();
		} catch (Exception e) {
			log.error("Accounts have not been initialized " + e.getMessage());
			return false;
		}
		return true;
	}
	
	public void openAccount(String password) throws AccountException {
		if (!accountExists()) { 
			throw new AccountException("Account does not exists");
		}
		config.setHavenoWalletPassword(password);
		walletsSetup.addSetupCompletedHandler(() -> {
			walletsInitialized = true;
		});
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
	
	public void closeAccount() throws AccountException {
		if (!isAccountOpen()) { 
			throw new AccountException("Account is not open!");
		}
		this.walletsSetup.shutDown();
	}
	
	public BufferedInputStream backupAccount() throws AccountException {
		if (!accountExists()) { 
			throw new AccountException("Account does not exists");
		}

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
	
	public void restoreAccount() throws AccountException {
		if (accountExists()) { 
			throw new AccountException("Account already exists!");
		}
	}
	
	public void changePassword(String password) throws AccountException {
		if (!isAccountOpen()) { 
			throw new AccountException("Account already open!");
		}
	}
}
