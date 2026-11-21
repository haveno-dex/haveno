/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.network.p2p.network;

import com.google.common.util.concurrent.Uninterruptibles;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.proto.ProtobufferException;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.network.p2p.ExtendedDataSizePermission;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ConnectionTest {

    private static final int TIMEOUT_MS = 10_000;
    private static final int LARGE = Connection.getPermittedMessageSize() + 1;

    private final MessageListener messageListener = mock(MessageListener.class);
    private final ConnectionListener connectionListener = mock(ConnectionListener.class);
    private final NetworkProtoResolver resolver = mock(NetworkProtoResolver.class);
    private final NetworkEnvelope envelope = mock(NetworkEnvelope.class, withSettings().extraInterfaces(ExtendedDataSizePermission.class));
    private final CountDownLatch endOfTest = new CountDownLatch(1);

    @BeforeEach
    public void setUp() throws Exception {
        Version.setBaseCryptoNetworkId(1);

        // the connection throttlers read a config which the app injects statically
        Field config = Connection.class.getDeclaredField("config");
        config.setAccessible(true);
        if (config.get(null) == null) config.set(null, new Config());

        when(resolver.fromProto(any(protobuf.NetworkEnvelope.class))).thenReturn(envelope);
    }

    @AfterEach
    public void tearDown() {
        endOfTest.countDown();
    }

    @Test
    public void testDeliversFramesWithoutUnknownFields() throws Exception {
        Connection connection = openConnection(frame(1, 10), frame(2, 0));

        verify(messageListener, timeout(TIMEOUT_MS).times(2)).onMessage(same(envelope), same(connection));
        verify(resolver).fromProto(ping(1));
        verify(resolver).fromProto(ping(2));
        assertNull(connection.getRuleViolation());
    }

    @Test
    public void testEndOfStreamInsideFrameIsDisconnect() throws Exception {
        Connection connection = connection(new ByteArrayInputStream(concat(varint(100), new byte[10])));

        verify(connectionListener, timeout(TIMEOUT_MS)).onDisconnect(eq(CloseConnectionReason.NO_PROTO_BUFFER_ENV), same(connection));
        assertNull(connection.getRuleViolation());
        verify(resolver, never()).fromProto(any(protobuf.NetworkEnvelope.class));
    }

    @Test
    public void testMalformedFrameClosesConnection() throws Exception {
        Connection connection = openConnection(varint(10), new byte[10], frame(1, 0));

        verify(connectionListener, timeout(TIMEOUT_MS)).onDisconnect(eq(CloseConnectionReason.RULE_VIOLATION), same(connection));
        assertEquals(RuleViolation.INVALID_DATA_TYPE, connection.getRuleViolation());
        verify(resolver, never()).fromProto(any(protobuf.NetworkEnvelope.class));
    }

    @Test
    public void testLargeFramesAreDeliveredAfterFailedOnes() throws Exception {
        when(resolver.fromProto(ping(2))).thenThrow(new ProtobufferException("invalid"));

        // a large frame which cannot be parsed
        Connection malformed = openConnection(varint(LARGE), new byte[LARGE]);
        verify(connectionListener, timeout(TIMEOUT_MS)).onDisconnect(eq(CloseConnectionReason.RULE_VIOLATION), same(malformed));

        // a large frame which cannot be converted, then valid ones on this and on another connection
        Connection first = openConnection(frame(2, LARGE), frame(1, LARGE));
        Connection second = openConnection(frame(1, LARGE));
        verify(messageListener, timeout(TIMEOUT_MS)).onMessage(same(envelope), same(first));
        verify(messageListener, timeout(TIMEOUT_MS)).onMessage(same(envelope), same(second));
    }

    // stays open after the frames until the test ends, since a closing connection can drop a delivery
    private Connection openConnection(byte[]... frames) throws IOException {
        InputStream open = new InputStream() {
            @Override
            public int read() {
                Uninterruptibles.awaitUninterruptibly(endOfTest);
                return -1;
            }
        };
        return connection(new SequenceInputStream(new ByteArrayInputStream(concat(frames)), open));
    }

    private Connection connection(InputStream inputStream) throws IOException {
        Socket socket = mock(Socket.class);
        when(socket.getInputStream()).thenReturn(inputStream);
        when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        return new Connection(socket, messageListener, connectionListener, null, resolver, null);
    }

    private static protobuf.NetworkEnvelope ping(int nonce) {
        return protobuf.NetworkEnvelope.newBuilder()
                .setMessageVersion(Version.getP2PMessageVersion())
                .setPing(protobuf.Ping.newBuilder().setNonce(nonce))
                .build();
    }

    // a ping with unknown fields of the given size in the envelope and in the ping
    private static byte[] frame(int nonce, int unknownBytes) throws IOException {
        UnknownFieldSet unknownFields = UnknownFieldSet.newBuilder()
                .addField(999, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFrom(new byte[unknownBytes])).build())
                .build();
        protobuf.NetworkEnvelope proto = ping(nonce);
        if (unknownBytes > 0) proto = proto.toBuilder()
                .setPing(proto.getPing().toBuilder().setUnknownFields(unknownFields))
                .setUnknownFields(unknownFields)
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        proto.writeDelimitedTo(out);
        return out.toByteArray();
    }

    private static byte[] varint(int value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CodedOutputStream codedOut = CodedOutputStream.newInstance(out);
        codedOut.writeUInt32NoTag(value);
        codedOut.flush();
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] array : arrays) out.writeBytes(array);
        return out.toByteArray();
    }
}
