package haveno.core.api;

/**
 * Default account listener (takes no action).
 */
public class AccountServiceListener {
    public void onAppInitialized() {}
    public void onAccountCreating() {}
    public void onAccountCreated() {}
    public void onAccountOpened() {}
    public void onAccountClosed() {}
    public void onAccountRestored(Runnable onShutDown) {}
    public void onWalletRestored(Runnable onShutDown) {}
    public void onAccountDeleted(Runnable onShutDown) {}
    public void onPasswordChanged(String oldPassword, String newPassword) {}
    public void onPasswordChangeFailed() {}
}
