/*
 * This file is part of Haveno.
 * See LICENSE for licensing information.
 */
package haveno.common.crypto;

import haveno.common.app.Version;
import java.nio.ByteBuffer;
import java.util.Base64;
import javax.crypto.SecretKey;

/** Explicit compatibility boundary. Local storage must never use these legacy send defaults. */
public final class NetworkEncryption {
    private NetworkEncryption() {}

    public static byte[] encryptMessage(byte[] payload, SecretKey key, byte[] wrappedKey, byte[] senderKey) throws CryptoException {
        return encryptMessage(payload, key, wrappedKey, senderKey, Version.NETWORK_ENCRYPTION_VERSION);
    }

    static byte[] encryptMessage(byte[] payload, SecretKey key, byte[] wrappedKey, byte[] senderKey, int version) throws CryptoException {
        return switch (version) {
            case 1 -> Encryption.encryptPayloadWithHmac(payload, key);
            case 2 -> AuthenticatedEncryption.encrypt(payload, key, messageContext(wrappedKey, senderKey));
            default -> throw new CryptoException("Unsupported network encryption version");
        };
    }

    public static byte[] decryptMessage(byte[] payload, SecretKey key, byte[] wrappedKey, byte[] senderKey) throws CryptoException {
        return AuthenticatedEncryption.hasEnvelope(payload)
                ? AuthenticatedEncryption.decrypt(payload, key, messageContext(wrappedKey, senderKey))
                : Encryption.decryptPayloadWithHmac(payload, key);
    }

    public static byte[] encryptPaymentAccount(byte[] payload, SecretKey key, String tradeId) throws CryptoException {
        return encryptPaymentAccount(payload, key, tradeId, Version.NETWORK_ENCRYPTION_VERSION);
    }

    static byte[] encryptPaymentAccount(byte[] payload, SecretKey key, String tradeId, int version) throws CryptoException {
        return switch (version) {
            case 1 -> Encryption.encrypt(payload, key);
            case 2 -> AuthenticatedEncryption.encrypt(payload, key, "payment-account/" + tradeId);
            default -> throw new CryptoException("Unsupported network encryption version");
        };
    }

    /** Callers must also verify the payment account hash from the signed trade contract. */
    public static byte[] decryptPaymentAccount(byte[] payload, SecretKey key, String tradeId) throws CryptoException {
        return AuthenticatedEncryption.hasEnvelope(payload)
                ? AuthenticatedEncryption.decrypt(payload, key, "payment-account/" + tradeId)
                : Encryption.decrypt(payload, key);
    }

    private static String messageContext(byte[] wrappedKey, byte[] senderKey) {
        byte[] boundKeys = ByteBuffer.allocate(8 + wrappedKey.length + senderKey.length)
                .putInt(wrappedKey.length).put(wrappedKey).putInt(senderKey.length).put(senderKey).array();
        return "network-message/" + Base64.getEncoder().encodeToString(Hash.getSha256Hash(boundKeys));
    }
}
