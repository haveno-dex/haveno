package bisq.core.api;

import java.io.BufferedInputStream;

import com.google.inject.Inject;

import bisq.core.btc.setup.WalletsSetup;
import haveno.core.account.AccountService;
import haveno.core.account.exceptions.AccountException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CoreAccountService {
	private final AccountService accountService;
	@Inject
	public CoreAccountService(AccountService accountService) {
		this.accountService = accountService;
	}
	
	public void createAccount(String password) throws AccountException {
		accountService.createAccount(password);
	}
	
	public boolean accountExists() {
		return accountService.accountExists();
	}
	
	public boolean isAccountOpen() {
		return accountService.isAccountOpen();
	}
	
	public void openAccount(String password) throws AccountException {
		accountService.openAccount(password);
	}
	
	public void closeAccount() throws AccountException {
		accountService.closeAccount();
	}
	
	public BufferedInputStream backupAccount() throws AccountException {
		return accountService.backupAccount();
	}
	
	public void deleteAccount() {
		accountService.deleteAccount();
	}
	
	public void restoreAccount() throws AccountException {
		accountService.restoreAccount();
	}
	
	public void changePassword(String password) throws AccountException {
		accountService.changePassword(password);
	}
}
