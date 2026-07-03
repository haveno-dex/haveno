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

package haveno.desktop.main.overlays.windows.downloadupdate;

import com.google.common.collect.Lists;
import haveno.common.util.Utilities;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class HavenoInstaller {

    /**
     * Full (40 hex char) primary-key fingerprints of the keys trusted to sign releases. Empty in the
     * upstream reference repo (no verification; users verify manually). Operational forks enable
     * in-app update verification by adding each signer's fingerprint here (uppercase, no spaces) and
     * bundling the matching public key as {@code desktop/src/main/resources/keys/<FINGERPRINT>.asc}.
     * The bundled key is the trust anchor: {@link #verifySignature} rejects any signature not made by
     * a pinned key, so a compromised download host cannot substitute its own key/signature/installer.
     */
    private static final List<String> PINNED_SIGNING_KEY_FINGERPRINTS = List.of(
            // e.g. "1DC3C8C4316A698AC494039CF5B84436F379A1C6"
    );
    private static final String DOWNLOAD_HOST_URL = "https://haveno.exchange/downloads/";

    public boolean isSupportedOS() {
        return Utilities.isOSX() || Utilities.isWindows() || Utilities.isDebianLinux() || Utilities.isRedHatLinux();
    }

    public Optional<DownloadTask> download(String version) {
        String partialUrl = DOWNLOAD_HOST_URL + "v" + version + "/";

        // Get installer filename on all platforms
        FileDescriptor installerFileDescriptor = getInstallerDescriptor(version, partialUrl);

        // tells us which key was used for signing
        FileDescriptor signingKeyDescriptor = getSigningKeyDescriptor(partialUrl);

        // Hash of jar file inside of the binary
        FileDescriptor jarHashDescriptor = getJarHashDescriptor(version, partialUrl);

        List<FileDescriptor> keyFileDescriptors = getKeyFileDescriptors();
        List<FileDescriptor> sigFileDescriptors = getSigFileDescriptors(installerFileDescriptor, keyFileDescriptors);

        List<FileDescriptor> allFiles = Lists.newArrayList();
        allFiles.add(installerFileDescriptor);
        allFiles.add(signingKeyDescriptor);
        allFiles.add(jarHashDescriptor);
        allFiles.addAll(keyFileDescriptors);
        allFiles.addAll(sigFileDescriptors);

        // Download keys, sigs and Installer
        return getDownloadTask(allFiles);
    }

    public VerifyTask verify(List<FileDescriptor> fileDescriptors) {
        VerifyTask verifyTask = new VerifyTask(fileDescriptors);
        new Thread(verifyTask, "HavenoInstaller VerifyTask").start();
        // TODO: check for problems when creating task
        return verifyTask;
    }

    private Optional<DownloadTask> getDownloadTask(List<FileDescriptor> fileDescriptors) {
        try {
            return Optional.of(downloadFiles(fileDescriptors, Utilities.getDownloadOfHomeDir()));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    /**
     * Creates and starts a Task for background downloading
     *
     * @param fileDescriptors
     * @param saveDir         Directory to save file to
     * @return The task handling the download
     * @throws IOException
     */
    private static DownloadTask downloadFiles(List<FileDescriptor> fileDescriptors, String saveDir) throws IOException {
        if (saveDir == null)
            saveDir = Utilities.getDownloadOfHomeDir();
        DownloadTask task = new DownloadTask(fileDescriptors, saveDir);
        new Thread(task, "HavenoInstaller DownloadTask").start();
        // TODO: check for problems when creating task
        return task;
    }

    /**
     * Verifies detached PGP signatures against GPG/openPGP RSA public keys. Does currently not work with openssl or JCA/JCE keys.
     *
     * @param pubKeyFile          Path to file providing the public key to use
     * @param sigFile             Path to detached signature file
     * @param dataFile            Path to signed data file
     * @param expectedFingerprint Full (40 hex char) primary-key fingerprint the signing key is pinned to.
     *                            Verification fails unless the key which produced the signature belongs to a
     *                            key ring whose primary key matches this fingerprint. This prevents a
     *                            compromised download host from supplying a self-consistent
     *                            key/signature/installer that validates against itself.
     * @return {@code OK} if the signature is valid and made by the pinned key, {@code FAIL} otherwise
     * @throws Exception throws various exceptions in case something went wrong. Main reason should be that key or
     *                   signature could be extracted from the provided files due to a "bad" format.<br>
     *                   <code>FileNotFoundException, IOException, SignatureException, PGPException</code>
     */
    public static VerifyStatusEnum verifySignature(File pubKeyFile, File sigFile, File dataFile, String expectedFingerprint) throws Exception {
        InputStream inputStream;
        int bytesRead;
        PGPPublicKey publicKey;
        PGPSignature pgpSignature;
        boolean result;

        // Read keys from file
        inputStream = PGPUtil.getDecoderStream(new FileInputStream(pubKeyFile));
        PGPPublicKeyRingCollection publicKeyRingCollection = new PGPPublicKeyRingCollection(inputStream, new JcaKeyFingerprintCalculator());
        inputStream.close();

        Iterator<PGPPublicKeyRing> iterator = publicKeyRingCollection.getKeyRings();
        PGPPublicKeyRing pgpPublicKeyRing;
        if (iterator.hasNext()) {
            pgpPublicKeyRing = iterator.next();
        } else {
            throw new PGPException("Could not find public keyring in provided key file");
        }

        // Would be the solution for multiple keys in one file
        //        Iterator<PGPPublicKey> kIt;
        //        kIt = pgpPublicKeyRing.getPublicKeys();
        //        publicKey = pgpPublicKeyRing.getPublicKey(0xF5B84436F379A1C6L);

        // Read signature from file
        inputStream = PGPUtil.getDecoderStream(new FileInputStream(sigFile));
        PGPObjectFactory pgpObjectFactory = new PGPObjectFactory(inputStream, new JcaKeyFingerprintCalculator());
        Object o = pgpObjectFactory.nextObject();
        if (o instanceof PGPSignatureList) {
            PGPSignatureList signatureList = (PGPSignatureList) o;
            checkArgument(!signatureList.isEmpty(), "signatureList must not be empty");
            pgpSignature = signatureList.get(0);
        } else if (o instanceof PGPSignature) {
            pgpSignature = (PGPSignature) o;
        } else {
            throw new SignatureException("Could not find signature in provided signature file");
        }
        inputStream.close();
        log.debug("KeyID used in signature: %X\n", pgpSignature.getKeyID());
        publicKey = pgpPublicKeyRing.getPublicKey(pgpSignature.getKeyID());

        // If signature is not matching the key used for signing we fail
        if (publicKey == null)
            return VerifyStatusEnum.FAIL;

        // Pin the trust anchor: the key which produced the signature must belong to a key ring whose
        // primary-key fingerprint equals the pinned fingerprint.
        String actualFingerprint = HexFormat.of().withUpperCase().formatHex(pgpPublicKeyRing.getPublicKey().getFingerprint());
        String normalizedExpected = expectedFingerprint == null ? "" : expectedFingerprint.replaceAll("\\s", "");
        if (normalizedExpected.isEmpty() || !actualFingerprint.equalsIgnoreCase(normalizedExpected)) {
            log.warn("Public key fingerprint {} does not match the pinned fingerprint {}", actualFingerprint, normalizedExpected);
            return VerifyStatusEnum.FAIL;
        }

        log.debug("The ID of the selected key is %X\n", publicKey.getKeyID());
        pgpSignature.init(new BcPGPContentVerifierBuilderProvider(), publicKey);

        // Read file to verify
        byte[] data = new byte[1024];
        inputStream = new DataInputStream(new BufferedInputStream(new FileInputStream(dataFile)));
        while (true) {
            bytesRead = inputStream.read(data, 0, 1024);
            if (bytesRead == -1)
                break;
            pgpSignature.update(data, 0, bytesRead);
        }
        inputStream.close();

        // Verify the signature
        result = pgpSignature.verify();
        return result ? VerifyStatusEnum.OK : VerifyStatusEnum.FAIL;
    }

    @NotNull
    private FileDescriptor getInstallerDescriptor(String version, String partialUrl) {
        String fileName;
        String prefix = "Haveno-";
        // https://github.com/bisq-network/exchange/releases/download/v0.5.1/Haveno-0.5.1.dmg
        if (Utilities.isOSX())
            fileName = prefix + version + ".dmg";
        else if (Utilities.isWindows())
            fileName = prefix + Utilities.getOSArchitecture() + "bit-" + version + ".exe";
        else if (Utilities.isDebianLinux())
            fileName = prefix + Utilities.getOSArchitecture() + "bit-" + version + ".deb";
        else if (Utilities.isRedHatLinux())
            fileName = prefix + Utilities.getOSArchitecture() + "bit-" + version + ".rpm";
        else
            throw new RuntimeException("No suitable install package available for your OS.");

        return FileDescriptor.builder()
                .type(DownloadType.INSTALLER)
                .fileName(fileName)
                .id(fileName)
                .loadUrl(partialUrl.concat(fileName))
                .build();
    }

    @NotNull
    private FileDescriptor getSigningKeyDescriptor(String url) {
        String fileName = "signingkey.asc";
        return FileDescriptor.builder()
                .type(DownloadType.SIGNING_KEY)
                .fileName(fileName)
                .id(fileName)
                .loadUrl(url.concat(fileName))
                .build();
    }

    @NotNull
    private FileDescriptor getJarHashDescriptor(String version, String partialUrl) {
        String fileName = "Haveno-" + version + ".jar.txt";
        return FileDescriptor.builder()
                .type(DownloadType.JAR_HASH)
                .fileName(fileName)
                .id(fileName)
                .loadUrl(partialUrl.concat(fileName))
                .build();
    }

    /**
     * The public keys, bundled with the app, of the release signers pinned in
     * {@link #PINNED_SIGNING_KEY_FINGERPRINTS}. The bundled key is the trust anchor; each
     * verification is checked against the pinned fingerprint in {@link #verifySignature}. A pinned
     * fingerprint with no bundled key is skipped with an error so verification fails safely rather
     * than crashing.
     *
     * @return list of keys to check against corresponding sigs (empty when no signers are pinned).
     */
    private List<FileDescriptor> getKeyFileDescriptors() {
        List<FileDescriptor> list = new ArrayList<>();
        for (String fingerprint : PINNED_SIGNING_KEY_FINGERPRINTS) {
            if (getClass().getResource("/keys/" + fingerprint + ".asc") == null) {
                log.error("Pinned signing key {} has no bundled public key at /keys/{}.asc; skipping", fingerprint, fingerprint);
                continue;
            }
            list.add(getLocalKeyFileDescriptor(fingerprint));
        }
        return list;
    }

    private FileDescriptor getLocalKeyFileDescriptor(String fingerPrint) {
        return FileDescriptor.builder()
                .type(DownloadType.KEY)
                .fileName(fingerPrint + ".asc-local")
                .id(fingerPrint)
                .loadUrl(getClass().getResource("/keys/" + fingerPrint + ".asc").toExternalForm())
                .build();
    }

    /**
     * There is one installer file, X keys and X sigs. The id links the sig to its key.
     * If we switch to multiple keys, the filename should also be key-dependent (filename.F1234.asc).
     *
     * @param installerFileDescriptor which installer file should this signatures be linked to?
     * @return
     */
    public List<FileDescriptor> getSigFileDescriptors(FileDescriptor installerFileDescriptor, List<FileDescriptor> keys) {
        String suffix = ".asc";
        List<FileDescriptor> result = Lists.newArrayList();

        for (FileDescriptor key : keys) {
            if (result.stream().noneMatch(e -> e.getId().equals(key.getId()))) {
                result.add(FileDescriptor.builder()
                        .type(DownloadType.SIG)
                        .fileName(installerFileDescriptor.getFileName().concat(suffix))
                        .id(key.getId())
                        .loadUrl(installerFileDescriptor.getLoadUrl().concat(suffix))
                        .build());
            } else {
                log.debug("We have already a file with the key: {}", key.getId());
            }
        }
        return result;
    }

    @Data
    @Builder
    public static class FileDescriptor {
        private String id;
        private DownloadType type;
        private String loadUrl;
        private String fileName;
        private File saveFile;
        @Builder.Default
        private DownloadStatusEnum downloadStatus = DownloadStatusEnum.UNKNOWN;
    }

    @Data
    @Builder
    public static class VerifyDescriptor {
        private File keyFile;
        private File sigFile;
        @Builder.Default
        private VerifyStatusEnum verifyStatusEnum = VerifyStatusEnum.UNKNOWN;
    }

    public enum DownloadStatusEnum {
        OK, FAIL, TIMEOUT, UNKNOWN
    }

    public enum VerifyStatusEnum {
        OK, FAIL, UNKNOWN
    }

    public enum DownloadType {
        INSTALLER,
        KEY,
        SIG,
        SIGNING_KEY,
        MISC,
        JAR_HASH
    }
}

