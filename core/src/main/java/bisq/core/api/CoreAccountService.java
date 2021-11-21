package bisq.core.api;

import com.google.inject.Inject;

import bisq.core.support.dispute.Attachment;

public class CoreAccountService {
    
    private final AccountService accountService;

    @Inject
    public CoreAccountService(AccountService accountService) {
        this.accountService = accountService;
    }

    public void createAccount(String password) throws Exception {
        accountService.createAccount(password);
    }

    public boolean accountExists() {
        return accountService.accountExists();
    }

    public boolean isAccountOpen() {
        return accountService.isAccountOpen();
    }
    
    public void openAccount(String password) throws Exception {
        accountService.openAccount(password);
    }

    public void closeAccount() throws Exception {
        accountService.closeAccount();
    }

    public Attachment backupAccount() throws Exception {
        return accountService.backupAccount();
    }

    public void deleteAccount() {
        accountService.deleteAccount();
    }

    public void restoreAccount(Attachment attachment) throws Exception {
        accountService.restoreAccount(attachment);
    }

    public void changePassword(String newPassword) throws Exception {
        accountService.changePassword(newPassword);
    }
}
