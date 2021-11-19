package haveno.core.account;

import com.google.inject.Inject;

import bisq.core.btc.setup.WalletsSetup;

public class AccountService {
	private final WalletsSetup walletsSetup;
	
	@Inject
	public AccountService(WalletsSetup walletsSetup) {
		this.walletsSetup = walletsSetup;
	}
	
	public void createAccount() {
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
	}
	
	public void backupAccount() {
		
	}
	
	public void deleteAccount() {
	}
	
	public void restoreAccount() {
		
	}
	
	public void changePassword() {
		
	}
}
