package bisq.core.api;

/**
 * Default account listener (takes no action).
 */
public class AccountServiceListener {
    public void onAppInitialized() {}
    public void onAccountCreated() {}
    public void onAccountOpened() {}
    public void onAccountClosed() {}
    public void onAccountRestored(Runnable onShutDown) {}
    public void onAccountDeleted(Runnable onShutDown) {}
    public void onPasswordChanged(String oldPassword, String newPassword) {}
}
