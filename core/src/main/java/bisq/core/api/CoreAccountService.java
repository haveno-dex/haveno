package bisq.core.api;

import javax.inject.Singleton;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @deprecated Should be replaced by actual implementation once it is available
 */
@Singleton
@Deprecated
public class CoreAccountService {


    private static final String DEFAULT_PASSWORD = "abctesting123";

    private String password = DEFAULT_PASSWORD;

    private final List<PasswordChangeListener> listeners = new CopyOnWriteArrayList<>();


    public String getPassword() {
        return password;
    }

    public void setPassword(String newPassword) {
        String oldPassword = password;
        password = newPassword;
        notifyListenerAboutPasswordChange(oldPassword, newPassword);
    }

    public void addPasswordChangeListener(PasswordChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
    }

    private void notifyListenerAboutPasswordChange(String oldPassword, String newPassword) {
        for (PasswordChangeListener listener : listeners) {
            listener.onPasswordChange(oldPassword, newPassword);
        }
    }

    public interface PasswordChangeListener {

        void onPasswordChange(String oldPassword, String newPassword);

    }
}
