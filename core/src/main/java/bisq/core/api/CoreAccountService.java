package bisq.core.api;

import com.google.inject.Inject;


import haveno.core.account.AccountService;

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
	
	public void backupAccount() {
		accountService.backupAccount();
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
