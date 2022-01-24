package bisq.core.api.model;

import bisq.common.proto.persistable.PersistablePayload;

import com.google.protobuf.ByteString;

import lombok.Builder;
import lombok.Value;


@Value
@Builder(toBuilder = true)
public class EncryptedConnection implements PersistablePayload {

    String uri;
    String username;
    byte[] encryptedPassword;
    byte[] encryptionSalt;
    int priority;

    @Override
    public protobuf.EncryptedConnection toProtoMessage() {
        return protobuf.EncryptedConnection.newBuilder()
                .setUri(uri)
                .setUsername(username)
                .setEncryptedPassword(ByteString.copyFrom(encryptedPassword))
                .setEncryptionSalt(ByteString.copyFrom(encryptionSalt))
                .setPriority(priority)
                .build();
    }

    public static EncryptedConnection fromProto(protobuf.EncryptedConnection encryptedConnection) {
        return new EncryptedConnection(
                encryptedConnection.getUri(),
                encryptedConnection.getUsername(),
                encryptedConnection.getEncryptedPassword().toByteArray(),
                encryptedConnection.getEncryptionSalt().toByteArray(),
                encryptedConnection.getPriority());
    }
}
