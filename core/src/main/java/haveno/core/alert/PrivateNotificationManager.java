/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.alert;

import com.google.common.base.Charsets;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.app.DevEnv;
import haveno.common.config.Config;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.SendMailboxMessageListener;
import haveno.network.p2p.mailbox.MailboxMessageService;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.MessageListener;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.peers.keepalive.messages.Ping;
import haveno.network.p2p.peers.keepalive.messages.Pong;
import java.math.BigInteger;
import java.security.SignatureException;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javax.annotation.Nullable;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;
import static org.bitcoinj.core.Utils.HEX;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrivateNotificationManager implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(PrivateNotificationManager.class);

    private final P2PService p2PService;
    private final MailboxMessageService mailboxMessageService;
    private final KeyRing keyRing;
    private final ObjectProperty<PrivateNotificationPayload> privateNotificationMessageProperty = new SimpleObjectProperty<>();
    private final boolean useDevPrivilegeKeys;

    private ECKey privateNotificationSigningKey;
    @Nullable
    private PrivateNotificationMessage privateNotificationMessage;
    private final NetworkNode networkNode;
    private Consumer<String> pingResponseHandler = null;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public PrivateNotificationManager(P2PService p2PService,
                                      NetworkNode networkNode,
                                      MailboxMessageService mailboxMessageService,
                                      KeyRing keyRing,
                                      @Named(Config.IGNORE_DEV_MSG) boolean ignoreDevMsg,
                                      @Named(Config.USE_DEV_PRIVILEGE_KEYS) boolean useDevPrivilegeKeys) {
        this.p2PService = p2PService;
        this.networkNode = networkNode;
        this.mailboxMessageService = mailboxMessageService;
        this.keyRing = keyRing;
        this.useDevPrivilegeKeys = useDevPrivilegeKeys;

        if (!ignoreDevMsg) {
            this.p2PService.addDecryptedDirectMessageListener(this::handleMessage);
            this.mailboxMessageService.addDecryptedMailboxListener(this::handleMessage);
        }
    }

    protected List<String> getPubKeyList() {
        if (useDevPrivilegeKeys) return List.of(DevEnv.DEV_PRIVILEGE_PUB_KEY);
        switch (Config.baseCurrencyNetwork()) {
        case XMR_LOCAL:
            return List.of(
                    "027a381b5333a56e1cc3d90d3a7d07f26509adf7029ed06fc997c656621f8da1ee",
                    "024baabdba90e7cc0dc4626ef73ea9d722ea7085d1104491da8c76f28187513492");
        case XMR_STAGENET:
            return List.of(
                    "02ba7c5de295adfe57b60029f3637a2c6b1d0e969a8aaefb9e0ddc3a7963f26925",
                    "026c581ad773d987e6bd10785ac7f7e0e64864aedeb8bce5af37046de812a37854",
                    "025b058c9f2c60d839669dbfa5578cf5a8117d60e6b70e2f0946f8a691273c6a36");
        case XMR_MAINNET:
            return List.of(
                    "02d8ac0fbe4e25f4a1d68b95936f25fc2e1b218e161cb5ed6661c7ab4c85f1fd4f",
                    "02e9dc14edddde19cc9f829a0739d0ab0c7310154ad94a15d477b51d85991b5a8a",
                    "021c798eb224ba23bd91ed7710a85d9b9a6439c29f4f29c1a14b96750a0da36aa7",
                    "029da09bc04dea33cd11a31bc1c05aa830b9180acb84e5370ee7fde60cae9f3d03");
        default:
            throw new RuntimeException("Unhandled base currency network: " + Config.baseCurrencyNetwork());
        }
    }

    private void handleMessage(DecryptedMessageWithPubKey decryptedMessageWithPubKey, NodeAddress senderNodeAddress) {
        NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
        if (networkEnvelope instanceof PrivateNotificationMessage) {
            privateNotificationMessage = (PrivateNotificationMessage) networkEnvelope;
            log.info("Received PrivateNotificationMessage from {} with uid={}",
                    senderNodeAddress, privateNotificationMessage.getUid());
            if (privateNotificationMessage.getSenderNodeAddress().equals(senderNodeAddress)) {
                final PrivateNotificationPayload privateNotification = privateNotificationMessage.getPrivateNotificationPayload();
                if (verifySignature(privateNotification))
                    privateNotificationMessageProperty.set(privateNotification);
            } else {
                log.warn("Peer address not matching for privateNotificationMessage");
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ReadOnlyObjectProperty<PrivateNotificationPayload> privateNotificationProperty() {
        return privateNotificationMessageProperty;
    }

    public boolean sendPrivateNotificationMessageIfKeyIsValid(PrivateNotificationPayload privateNotification,
                                                              PubKeyRing pubKeyRing,
                                                              NodeAddress peersNodeAddress,
                                                              String privKeyString,
                                                              SendMailboxMessageListener sendMailboxMessageListener) {
        boolean isKeyValid = isKeyValid(privKeyString);
        if (isKeyValid) {
            signAndAddSignatureToPrivateNotificationMessage(privateNotification);

            PrivateNotificationMessage message = new PrivateNotificationMessage(privateNotification,
                    p2PService.getNetworkNode().getNodeAddress(),
                    UUID.randomUUID().toString());
            log.info("Send {} to peer {}. uid={}",
                    message.getClass().getSimpleName(), peersNodeAddress, message.getUid());
            mailboxMessageService.sendEncryptedMailboxMessage(peersNodeAddress,
                    pubKeyRing,
                    message,
                    sendMailboxMessageListener);
        }

        return isKeyValid;
    }

    public void removePrivateNotification() {
        if (privateNotificationMessage != null) {
            mailboxMessageService.removeMailboxMsg(privateNotificationMessage);
        }
    }

    private boolean isKeyValid(String privKeyString) {
        try {
            privateNotificationSigningKey = ECKey.fromPrivate(new BigInteger(1, HEX.decode(privKeyString)));
            return getPubKeyList().contains(Utils.HEX.encode(privateNotificationSigningKey.getPubKey()));
        } catch (Throwable t) {
            return false;
        }
    }

    private void signAndAddSignatureToPrivateNotificationMessage(PrivateNotificationPayload privateNotification) {
        String privateNotificationMessageAsHex = Utils.HEX.encode(privateNotification.getMessage().getBytes(Charsets.UTF_8));
        String signatureAsBase64 = privateNotificationSigningKey.signMessage(privateNotificationMessageAsHex);
        privateNotification.setSigAndPubKey(signatureAsBase64, keyRing.getSignatureKeyPair().getPublic());
    }

    private boolean verifySignature(PrivateNotificationPayload privateNotification) {
        String privateNotificationMessageAsHex = Utils.HEX.encode(privateNotification.getMessage().getBytes(Charsets.UTF_8));
        for (String pubKeyAsHex : getPubKeyList()) {
            try {
                ECKey.fromPublicOnly(HEX.decode(pubKeyAsHex)).verifyMessage(privateNotificationMessageAsHex, privateNotification.getSignatureAsBase64());
                return true;
            } catch (SignatureException e) {
                // ignore
            }
        }
        log.warn("verifySignature failed");
        return false;
    }

    public void sendPing(NodeAddress peersNodeAddress, Consumer<String> resultHandler) {
        Ping ping = new Ping(new Random().nextInt(), 0);
        log.info("Send Ping to peer {}, nonce={}", peersNodeAddress, ping.getNonce());
        SettableFuture<Connection> future = networkNode.sendMessage(peersNodeAddress, ping);
        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(Connection connection) {
                connection.addMessageListener(PrivateNotificationManager.this);
                pingResponseHandler = resultHandler;
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                String errorMessage = "Sending ping to " + peersNodeAddress.getAddressForDisplay() +
                        " failed. That is expected if the peer is offline.\n\tping=" + ping +
                        ".\n\tException=" + throwable.getMessage();
                log.info(errorMessage);
                resultHandler.accept(errorMessage);
            }
        }, MoreExecutors.directExecutor());
    }

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        if (networkEnvelope instanceof Pong) {
            Pong pong = (Pong) networkEnvelope;
            String key = connection.getPeersNodeAddressOptional().get().getFullAddress();
            log.info("Received Pong! {} from {}", pong, key);
            connection.removeMessageListener(this);
            if (pingResponseHandler != null) {
                pingResponseHandler.accept("SUCCESS");
            }
        }
    }
}
