package bisq.core.api;

import java.io.BufferedInputStream;

import com.google.inject.Inject;

import bisq.core.btc.setup.WalletsSetup;
import haveno.core.account.AccountService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CoreAccountService {
	private final AccountService accountService;
	@Inject
	public CoreAccountService(AccountService accountService) {
		this.accountService = accountService;
	}
	
	public void createAccount() {
		accountService.createAccount();
	}
	
	public boolean accountExists() {
		return accountService.accountExists();
	}
	
	public boolean isAccountOpen() {
		return true;
	}
	
	public void openAccount() {
		accountService.openAccount();
	}
	
	public void closeAccount() {
		accountService.closeAccount();
	}
	
	public BufferedInputStream backupAccount() {
		return accountService.backupAccount();
	}
	
	public void deleteAccount() {
		accountService.deleteAccount();
	}
	
	public void restoreAccount() {
		accountService.restoreAccount();
	}
	
	public void changePassword() {
		accountService.changePassword();
	}
}
