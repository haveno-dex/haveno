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

package haveno.core.xmr.setup;

import com.google.common.io.Closeables;
import com.google.common.util.concurrent.AbstractIdleService;
import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import haveno.common.config.Config;
import haveno.common.file.FileUtil;
import haveno.core.api.LocalMoneroNode;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import lombok.Getter;
import lombok.Setter;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.bouncycastle.crypto.params.KeyParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static haveno.common.util.Preconditions.checkDir;

/**
 * <p>Utility class that wraps the boilerplate needed to set up a new SPV bitcoinj app. Instantiate it with a directory
 * and file prefix, optionally configure a few things, then use startAsync and optionally awaitRunning. The object will
 * construct and configure a {@link BlockChain}, {@link SPVBlockStore}, {@link Wallet} and {@link PeerGroup}.</p>
 *
 * <p>To add listeners and modify the objects that are constructed, you can either do that by overriding the
 * {@link #onSetupCompleted()} method (which will run on a background thread) and make your changes there,
 * or by waiting for the service to start and then accessing the objects from wherever you want. However, you cannot
 * access the objects this class creates until startup is complete.</p>
 *
 * <p>The asynchronous design of this class may seem puzzling (just use {@link #awaitRunning()} if you don't want that).
 * It is to make it easier to fit bitcoinj into GUI apps, which require a high degree of responsiveness on their main
 * thread which handles all the animation and user interaction. Even when blockingStart is false, initializing bitcoinj
 * means doing potentially blocking file IO, generating keys and other potentially intensive operations. By running it
 * on a background thread, there's no risk of accidentally causing UI lag.</p>
 *
 * <p>Note that {@link #awaitRunning()} can throw an unchecked {@link IllegalStateException}
 * if anything goes wrong during startup - you should probably handle it and use {@link Exception#getCause()} to figure
 * out what went wrong more precisely. Same thing if you just use the {@link #startAsync()} method.</p>
 */
public class WalletConfig extends AbstractIdleService {

    protected static final Logger log = LoggerFactory.getLogger(WalletConfig.class);

    protected final NetworkParameters params;
    protected final String filePrefix;
    protected volatile BlockChain vChain;
    protected volatile SPVBlockStore vStore;
    protected volatile Wallet vBtcWallet;
    protected volatile PeerGroup vPeerGroup;

    protected final File directory;
    protected volatile File vXmrWalletFile;
    protected volatile File vBtcWalletFile;

    protected PeerAddress[] peerAddresses;
    protected DownloadListener downloadListener;
    protected InputStream checkpoints;
    protected String userAgent, version;
    @Nullable
    protected DeterministicSeed restoreFromSeed;
    @Nullable
    protected PeerDiscovery discovery;

    protected volatile Context context;

    protected Config config;
    protected LocalMoneroNode localMoneroNode;
    protected Socks5Proxy socks5Proxy;
    protected int numConnectionsForBtc;
    @Getter
    @Setter
    private int minBroadcastConnections;
    @Getter
    private BooleanProperty migratedWalletToSegwit = new SimpleBooleanProperty(false);

    /**
     * Creates a new WalletConfig, with a newly created {@link Context}. Files will be stored in the given directory.
     */
    public WalletConfig(NetworkParameters params,
                        File directory,
                        String filePrefix) {
        this(new Context(params), directory, filePrefix);
    }

    /**
     * Creates a new WalletConfig, with the given {@link Context}. Files will be stored in the given directory.
     */
    private WalletConfig(Context context, File directory, String filePrefix) {
        this.context = context;
        this.params = checkNotNull(context.getParams());
        this.directory = checkDir(directory);
        this.filePrefix = checkNotNull(filePrefix);
    }

    public WalletConfig setSocks5Proxy(Socks5Proxy socks5Proxy) {
        checkState(state() == State.NEW, "Cannot call after startup");
        this.socks5Proxy = socks5Proxy;
        return this;
    }

    public WalletConfig setConfig(Config config) {
        checkState(state() == State.NEW, "Cannot call after startup");
        this.config = config;
        return this;
    }

    public WalletConfig setLocalMoneroNodeService(LocalMoneroNode localMoneroNode) {
        checkState(state() == State.NEW, "Cannot call after startup");
        this.localMoneroNode = localMoneroNode;
        return this;
    }

    public WalletConfig setNumConnectionsForBtc(int numConnectionsForBtc) {
        checkState(state() == State.NEW, "Cannot call after startup");
        this.numConnectionsForBtc = numConnectionsForBtc;
        return this;
    }


    /** Will only connect to the given addresses. Cannot be called after startup. */
    public WalletConfig setPeerNodes(PeerAddress... addresses) {
        checkState(state() == State.NEW, "Cannot call after startup");
        this.peerAddresses = addresses;
        return this;
    }

    /** Will only connect to localhost. Cannot be called after startup. */
    public WalletConfig connectToLocalHost() {
        final InetAddress localHost = InetAddress.getLoopbackAddress();
        return setPeerNodes(new PeerAddress(params, localHost, params.getPort()));
    }

    /**
     * If you want to learn about the sync process, you can provide a listener here. For instance, a
     * {@link DownloadProgressTracker} is a good choice.
     */
    public WalletConfig setDownloadListener(DownloadListener listener) {
        this.downloadListener = listener;
        return this;
    }

    /**
     * If set, the file is expected to contain a checkpoints file calculated with BuildCheckpoints. It makes initial
     * block sync faster for new users - please refer to the documentation on the bitcoinj website
     * (https://bitcoinj.github.io/speeding-up-chain-sync) for further details.
     */
    public WalletConfig setCheckpoints(InputStream checkpoints) {
        if (this.checkpoints != null)
            Closeables.closeQuietly(checkpoints);
        this.checkpoints = checkNotNull(checkpoints);
        return this;
    }

    /**
     * Sets the string that will appear in the subver field of the version message.
     * @param userAgent A short string that should be the name of your app, e.g. "My Wallet"
     * @param version A short string that contains the version number, e.g. "1.0-BETA"
     */
    public WalletConfig setUserAgent(String userAgent, String version) {
        this.userAgent = checkNotNull(userAgent);
        this.version = checkNotNull(version);
        return this;
    }

    /**
     * If a seed is set here then any existing wallet that matches the file name will be renamed to a backup name,
     * the chain file will be deleted, and the wallet object will be instantiated with the given seed instead of
     * a fresh one being created. This is intended for restoring a wallet from the original seed. To implement restore
     * you would shut down the existing appkit, if any, then recreate it with the seed given by the user, then start
     * up the new kit. The next time your app starts it should work as normal (that is, don't keep calling this each
     * time).
     */
    public WalletConfig restoreWalletFromSeed(DeterministicSeed seed) {
        this.restoreFromSeed = seed;
        return this;
    }

    /**
     * Sets the peer discovery class to use. If none is provided then DNS is used, which is a reasonable default.
     */
    public WalletConfig setDiscovery(@Nullable PeerDiscovery discovery) {
        this.discovery = discovery;
        return this;
    }

    /**
     * This method is invoked on a background thread after all objects are initialised, but before the peer group
     * or block chain download is started. You can tweak the objects configuration here.
     */
    protected void onSetupCompleted() {
        // Meant to be overridden by subclasses
    }

    @Override
    protected void startUp() throws Exception {
        onSetupCompleted();
    }

    protected void setupAutoSave(Wallet wallet, File walletFile) {
        wallet.autosaveToFile(walletFile, 5, TimeUnit.SECONDS, null);
    }

    @Override
    protected void shutDown() throws Exception {
    }

    public NetworkParameters params() {
        return params;
    }

    public BlockChain chain() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vChain;
    }

    public BlockStore store() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vStore;
    }

    public Wallet btcWallet() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vBtcWallet;
    }

    public PeerGroup peerGroup() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vPeerGroup;
    }

    public File directory() {
        return directory;
    }

    public void maybeAddSegwitKeychain(Wallet wallet, KeyParameter aesKey) {
        if (HavenoKeyChainGroupStructure.BIP44_BTC_NON_SEGWIT_ACCOUNT_PATH.equals(wallet.getActiveKeyChain().getAccountPath())) {
            if (wallet.isEncrypted() && aesKey == null) {
                // wait for the aesKey to be set and this method to be invoked again.
                return;
            }
            // Do a backup of the wallet
            File backup = new File(directory, WalletsSetup.PRE_SEGWIT_WALLET_BACKUP);
            try {
                FileUtil.copyFile(new File(directory, "haveno_BTC.wallet"), backup);
            } catch (IOException e) {
                log.error(e.toString(), e);
            }

            // Btc wallet does not have a native segwit keychain, we should add one.
            DeterministicSeed seed = wallet.getKeyChainSeed();
            if (aesKey != null) {
                // If wallet is encrypted, decrypt the seed.
                KeyCrypter keyCrypter = wallet.getKeyCrypter();
                seed = seed.decrypt(keyCrypter, DeterministicKeyChain.DEFAULT_PASSPHRASE_FOR_MNEMONIC, aesKey);
            }
            DeterministicKeyChain nativeSegwitKeyChain = DeterministicKeyChain.builder().seed(seed)
                    .outputScriptType(Script.ScriptType.P2WPKH)
                    .accountPath(new HavenoKeyChainGroupStructure().accountPathFor(Script.ScriptType.P2WPKH)).build();
            if (aesKey != null) {
                // If wallet is encrypted, encrypt the new keychain.
                KeyCrypter keyCrypter = wallet.getKeyCrypter();
                nativeSegwitKeyChain = nativeSegwitKeyChain.toEncrypted(keyCrypter, aesKey);
            }
            wallet.addAndActivateHDChain(nativeSegwitKeyChain);
        }
        migratedWalletToSegwit.set(true);
    }

    public boolean stateStartingOrRunning() {
        return state() == State.STARTING || state() == State.RUNNING;
    }
}
