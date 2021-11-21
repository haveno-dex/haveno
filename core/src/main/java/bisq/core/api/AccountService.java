package bisq.core.api;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.apache.commons.io.FileUtils;

import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.google.inject.name.Named;

import bisq.common.config.Config;
import bisq.common.file.FileUtil;
import bisq.common.util.ZipUtils;
import bisq.core.btc.Balances;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.support.dispute.Attachment;
import bisq.network.p2p.P2PService;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroUtils;

@Slf4j
public class AccountService {

    private final Config config;
    private final CoreWalletsService coreWalletsService;
    public static String DEFAULT_PASSWORD = "abctesting123";
    private final WalletsSetup walletsSetup;
    private final File walletDir;
    private final P2PService p2pService;
    private final Balances balances;
    private boolean isAccountOpen = false;
    private boolean isDeleting = false;

    @Inject
    public AccountService(Config config,
                          CoreWalletsService coreWalletsService,
                          WalletsSetup walletsSetup,
                          P2PService p2pService,
                          Balances balances,
                          @Named(Config.WALLET_DIR) File walletDir) {
        this.config = config;
        this.coreWalletsService = coreWalletsService;
        this.walletsSetup = walletsSetup;
        this.walletDir = walletDir;
        this.p2pService = p2pService;
        this.balances = balances;
        this.walletsSetup.addSetupCompletedHandler(() -> {
            isAccountOpen = true;
            balances.onAllServicesInitialized();
        });

        walletsSetup.shutDownComplete.addListener((ov, o, n) -> {
            log.info("1WalletsSetup shutdown completed");
            if (isDeleting) {
                try {
                        File dir = new File(Config.appDataDir(), Config.baseCurrencyNetwork().name().toLowerCase());
                        if (dir.exists())
                            FileUtil.deleteDirectory(dir);
                } catch (IOException e) {
                    log.error("Could not delete directory " + e.getMessage());
                } finally {
                	isDeleting = false;
                }
            }
            isAccountOpen = false;
        });
    }

    public void createAccount(String password) throws Exception {
        if (accountExists()) { 
            throw new Exception("Account already exists!");
        }
        AccountService.DEFAULT_PASSWORD = password;
        config.createSubDirectories();
        ObjectProperty<Throwable> accountServiceException = new SimpleObjectProperty<>();
        this.walletsSetup.initialize(null,
                    () -> {},
                    exception -> {
                        accountServiceException.set(exception);
                });
    }

    public boolean accountExists() {
        return MoneroUtils.walletExists(new File(this.walletDir, "haveno" + "_XMR").getPath()) && !isDeleting;
    }

    public boolean isAccountOpen() {
        return isAccountOpen;
    }

    public void openAccount(String password) throws Exception {
        if (!accountExists()) { 
            throw new Exception("Account does not exists");
        }
        AccountService.DEFAULT_PASSWORD = password;
        ObjectProperty<Throwable> accountServiceException = new SimpleObjectProperty<>();
        this.walletsSetup.initialize(null,
                    () -> {},
                    exception -> {
                        accountServiceException.set(exception);
                    });
    }

    public void closeAccount() throws Exception {
        if (!isAccountOpen()) { 
            throw new Exception("Account is not open!");
        }
        
        coreWalletsService.shutDown();
    }

    public Attachment backupAccount() throws Exception {
        if (!accountExists()) {
            throw new Exception("Account does not exists");
        }
        var zipName = config.appDataDir.getAbsolutePath() + File.separator + Config.baseCurrencyNetwork().name().toLowerCase() + ".zip";
        File zipFile = new File(zipName);
        if (zipFile.exists())
            zipFile.delete();
        walletsSetup.clearBackups();
        ZipUtils.compressDirectory(config.appDataDir, zipFile);
        try (FileInputStream inputStream = new FileInputStream(zipName)){
        	byte[] zippedBackupAsBytes = ByteStreams.toByteArray(inputStream);
            return new Attachment(zipFile.getName(), zippedBackupAsBytes); 
        } catch (java.io.IOException e) {
            log.error("Could not load" + zipName + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    public void deleteAccount() {
        if (this.isAccountOpen()) {
            isDeleting = true;
            coreWalletsService.shutDown();
        }
        else {
            try {
                File dir = new File(Config.appDataDir(), Config.baseCurrencyNetwork().name().toLowerCase());
                if (dir.exists())
                    FileUtil.deleteDirectory(dir);
            } catch (IOException e) {
                log.error("Could not delete directory " + e.getMessage());
                e.printStackTrace();
            } finally {
                isDeleting = false;
            }
            isAccountOpen = false;
        }
    }

    public void restoreAccount(Attachment attachment) throws Exception {
        if (accountExists()) {
            throw new Exception("Account already exists!");
        }
        String destinationDir = config.appDataDir.getAbsolutePath();
        File zipFile = new File(destinationDir + File.separator + attachment.getFileName());
        if (zipFile.exists())
            zipFile.delete();
        FileUtils.writeByteArrayToFile(zipFile, attachment.getBytes());
        ZipUtils.decompress(zipFile, new File(destinationDir));
        if (zipFile.exists())
            zipFile.delete();
    }

    public void changePassword(String newPassword) throws Exception {
        if (isAccountOpen()) { 
            throw new Exception("Account is not open!");
        }
        coreWalletsService.setWalletPassword(AccountService.DEFAULT_PASSWORD, newPassword);
    }
}
