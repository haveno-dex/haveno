package haveno.common.crypto;

import haveno.common.app.Version;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkEncryptionTest {
    private final SecretKey key = Encryption.generateSecretKey(256);
    private final byte[] payload = new byte[]{1, 2, 3};
    private final byte[] wrapped = new byte[]{4, 5, 6};
    private final byte[] sender = new byte[]{7, 8, 9};

    @Test
    void productionWritersRemainByteCompatibleWithLegacyReaders() throws Exception {
        assertEquals(1, Version.NETWORK_ENCRYPTION_VERSION, "Changing the rollout gate requires a network deployment review");
        byte[] message = NetworkEncryption.encryptMessage(payload, key, wrapped, sender);
        assertArrayEquals(Encryption.encryptPayloadWithHmac(payload, key), message);
        assertArrayEquals(payload, Encryption.decryptPayloadWithHmac(message, key));
        byte[] account = NetworkEncryption.encryptPaymentAccount(payload, key, "trade");
        assertArrayEquals(Encryption.encrypt(payload, key), account);
        assertArrayEquals(payload, Encryption.decrypt(account, key));
    }

    @Test
    void bothReadersAcceptBothVersions() throws Exception {
        for (int version : new int[]{1, 2}) {
            assertArrayEquals(payload, NetworkEncryption.decryptMessage(
                    NetworkEncryption.encryptMessage(payload, key, wrapped, sender, version), key, wrapped, sender));
            assertArrayEquals(payload, NetworkEncryption.decryptPaymentAccount(
                    NetworkEncryption.encryptPaymentAccount(payload, key, "trade", version), key, "trade"));
        }
    }

    @Test
    void modernMessagesBindSenderAndWrappedKeyAndPaymentAccountsBindTrade() throws Exception {
        byte[] encrypted = NetworkEncryption.encryptMessage(payload, key, wrapped, sender, 2);
        assertThrows(CryptoException.class, () -> NetworkEncryption.decryptMessage(encrypted, key, sender, sender));
        assertThrows(CryptoException.class, () -> NetworkEncryption.decryptMessage(encrypted, key, wrapped, wrapped));
        byte[] account = NetworkEncryption.encryptPaymentAccount(payload, key, "trade", 2);
        assertThrows(CryptoException.class, () -> NetworkEncryption.decryptPaymentAccount(account, key, "other-trade"));
        encrypted[7] = 3;
        assertThrows(CryptoException.class, () -> NetworkEncryption.decryptMessage(encrypted, key, wrapped, sender));
        assertThrows(CryptoException.class, () -> NetworkEncryption.encryptMessage(payload, key, wrapped, sender, 3));
        assertThrows(CryptoException.class, () -> NetworkEncryption.encryptPaymentAccount(payload, key, "trade", 3));
    }
}
