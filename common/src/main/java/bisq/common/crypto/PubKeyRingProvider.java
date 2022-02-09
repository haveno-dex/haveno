package bisq.common.crypto;

import com.google.inject.Inject;
import com.google.inject.Provider;

/**
 * Allows User's static PubKeyRing to be injected into constructors without having to
 * open the account yet. Once its opened, PubKeyRingProvider will return non-null PubKeyRing.
 * Originally used via bind(PubKeyRing.class).toProvider(PubKeyRingProvider.class);
 */
public class PubKeyRingProvider implements Provider<PubKeyRing> {

    private final KeyRing keyRing;

    @Inject
    public PubKeyRingProvider(KeyRing keyRing) {
        this.keyRing = keyRing;
    }

    @Override
    public PubKeyRing get() {
        return keyRing.getPubKeyRing();
    }
}
