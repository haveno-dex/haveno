package haveno.common.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static haveno.common.config.Config.APP_DATA_DIR;
import static haveno.common.config.Config.APP_NAME;
import static haveno.common.config.Config.BANNED_XMR_NODES;
import static haveno.common.config.Config.CONFIG_FILE;
import static haveno.common.config.Config.DEFAULT_CONFIG_FILE_NAME;
import static haveno.common.config.Config.HELP;
import static haveno.common.config.Config.TORRC_FILE;
import static haveno.common.config.Config.USER_DATA_DIR;
import static java.io.File.createTempFile;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.nio.file.Files.createTempDirectory;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigTests {

    // Note: "DataDirProperties" in the test method names below represent the group of
    // configuration options that influence the location of a Haveno node's data directory.
    // These options include appName, userDataDir, appDataDir and configFile

    @Test
    public void whenNoArgCtorIsCalled_thenDefaultAppNameIsSetToTempValue() {
        Config config = new Config();
        String defaultAppName = config.defaultAppName;
        String regex = "Haveno\\d{2,}Temp";
        assertTrue(defaultAppName.matches(regex),
                format("Temp app name '%s' failed to match '%s'", defaultAppName, regex));
    }

    @Test
    public void whenAppNameOptionIsSet_thenAppNamePropertyDiffersFromDefaultAppNameProperty() {
        Config config = configWithOpts(opt(APP_NAME, "My-Haveno"));
        assertThat(config.appName, equalTo("My-Haveno"));
        assertThat(config.appName, not(equalTo(config.defaultAppName)));
    }

    @Test
    public void whenNoOptionsAreSet_thenDataDirPropertiesEqualDefaultValues() {
        Config config = new Config();
        assertThat(config.appName, equalTo(config.defaultAppName));
        assertThat(config.userDataDir, equalTo(config.defaultUserDataDir));
        assertThat(config.appDataDir, equalTo(config.defaultAppDataDir));
        assertThat(config.configFile, equalTo(config.defaultConfigFile));
    }

    @Test
    public void whenAppNameOptionIsSet_thenDataDirPropertiesReflectItsValue() {
        Config config = configWithOpts(opt(APP_NAME, "My-Haveno"));
        assertThat(config.appName, equalTo("My-Haveno"));
        assertThat(config.userDataDir, equalTo(config.defaultUserDataDir));
        assertThat(config.appDataDir, equalTo(new File(config.userDataDir, "My-Haveno")));
        assertThat(config.configFile, equalTo(new File(config.appDataDir, DEFAULT_CONFIG_FILE_NAME)));
    }

    @Test
    public void whenAppDataDirOptionIsSet_thenDataDirPropertiesReflectItsValue() throws IOException {
        File appDataDir = createTempDirectory("myapp").toFile();
        Config config = configWithOpts(opt(APP_DATA_DIR, appDataDir));
        assertThat(config.appName, equalTo(config.defaultAppName));
        assertThat(config.userDataDir, equalTo(config.defaultUserDataDir));
        assertThat(config.appDataDir, equalTo(appDataDir));
        assertThat(config.configFile, equalTo(new File(config.appDataDir, DEFAULT_CONFIG_FILE_NAME)));
    }

    @Test
    public void whenUserDataDirOptionIsSet_thenDataDirPropertiesReflectItsValue() throws IOException {
        File userDataDir = createTempDirectory("myuserdata").toFile();
        Config config = configWithOpts(opt(USER_DATA_DIR, userDataDir));
        assertThat(config.appName, equalTo(config.defaultAppName));
        assertThat(config.userDataDir, equalTo(userDataDir));
        assertThat(config.appDataDir, equalTo(new File(userDataDir, config.defaultAppName)));
        assertThat(config.configFile, equalTo(new File(config.appDataDir, DEFAULT_CONFIG_FILE_NAME)));
    }

    @Test
    public void whenAppNameAndAppDataDirOptionsAreSet_thenDataDirPropertiesReflectTheirValues() throws IOException {
        File appDataDir = createTempDirectory("myapp").toFile();
        Config config = configWithOpts(opt(APP_NAME, "My-Haveno"), opt(APP_DATA_DIR, appDataDir));
        assertThat(config.appName, equalTo("My-Haveno"));
        assertThat(config.userDataDir, equalTo(config.defaultUserDataDir));
        assertThat(config.appDataDir, equalTo(appDataDir));
        assertThat(config.configFile, equalTo(new File(config.appDataDir, DEFAULT_CONFIG_FILE_NAME)));
    }

    @Test
    public void whenOptionIsSetAtCommandLineAndInConfigFile_thenCommandLineValueTakesPrecedence() throws IOException {
        File configFile = createTempFile("haveno", "properties");
        try (PrintWriter writer = new PrintWriter(configFile)) {
            writer.println(new ConfigFileOption(APP_NAME, "Haveno-configFileValue"));
        }
        Config config = configWithOpts(opt(APP_NAME, "Haveno-commandLineValue"));
        assertThat(config.appName, equalTo("Haveno-commandLineValue"));
    }

    @Test
    public void whenUnrecognizedOptionIsSet_thenConfigExceptionIsThrown() {
        Exception exception = assertThrows(ConfigException.class, () -> configWithOpts(opt("bogus")));

        String expectedMessage = "problem parsing option 'bogus': bogus is not a recognized option";
        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    public void whenUnrecognizedOptionIsSetInConfigFile_thenNoExceptionIsThrown() throws IOException {
        File configFile = createTempFile("haveno", "properties");
        try (PrintWriter writer = new PrintWriter(configFile)) {
            writer.println(new ConfigFileOption("bogusOption", "bogusValue"));
            writer.println(new ConfigFileOption(APP_NAME, "HavenoTest"));
        }
        Config config = configWithOpts(opt(CONFIG_FILE, configFile.getAbsolutePath()));
        assertThat(config.appName, equalTo("HavenoTest"));
    }

    @Test
    public void whenOptionFileArgumentDoesNotExist_thenConfigExceptionIsThrown() {
        String filepath = getProperty("os.name").startsWith("Windows") ? "C:\\does\\not\\exist" : "/does/not/exist";
        Exception exception = assertThrows(ConfigException.class, () -> configWithOpts(opt(TORRC_FILE, filepath)));

        String expectedMessage = format("problem parsing option 'torrcFile': File [%s] does not exist", filepath);
        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    public void whenConfigFileOptionIsSetToNonExistentFile_thenConfigExceptionIsThrown() {
        String filepath = getProperty("os.name").startsWith("Windows") ? "C:\\no\\such\\haveno.properties" : "/no/such/haveno.properties";
        Exception exception = assertThrows(ConfigException.class, () -> configWithOpts(opt(CONFIG_FILE, filepath)));

        String expectedMessage = format("The specified config file '%s' does not exist", filepath);
        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    public void whenConfigFileOptionIsSetInConfigFile_thenConfigExceptionIsThrown() throws IOException {
        File configFile = createTempFile("haveno", "properties");
        try (PrintWriter writer = new PrintWriter(configFile)) {
            writer.println(new ConfigFileOption(CONFIG_FILE, "/tmp/other.haveno.properties"));
        }
        Exception exception = assertThrows(ConfigException.class, () -> configWithOpts(opt(CONFIG_FILE, configFile.getAbsolutePath())));

        String expectedMessage = format("The '%s' option is disallowed in config files", CONFIG_FILE);
        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    public void whenConfigFileOptionIsSetToExistingFile_thenConfigFilePropertyReflectsItsValue() throws IOException {
        File configFile = createTempFile("haveno", "properties");
        Config config = configWithOpts(opt(CONFIG_FILE, configFile.getAbsolutePath()));
        assertThat(config.configFile, equalTo(configFile));
    }

    @Test
    public void whenConfigFileOptionIsSetToRelativePath_thenThePathIsPrefixedByAppDataDir() throws IOException {
        File configFile = Files.createTempFile("my-haveno", ".properties").toFile();
        File appDataDir = configFile.getParentFile();
        String relativeConfigFilePath = configFile.getName();
        Config config = configWithOpts(opt(APP_DATA_DIR, appDataDir), opt(CONFIG_FILE, relativeConfigFilePath));
        assertThat(config.configFile, equalTo(configFile));
    }

    @Test
    public void whenAppNameIsSetInConfigFile_thenDataDirPropertiesReflectItsValue() throws IOException {
        File configFile = createTempFile("haveno", "properties");
        try (PrintWriter writer = new PrintWriter(configFile)) {
            writer.println(new ConfigFileOption(APP_NAME, "My-Haveno"));
        }
        Config config = configWithOpts(opt(CONFIG_FILE, configFile.getAbsolutePath()));
        assertThat(config.appName, equalTo("My-Haveno"));
        assertThat(config.userDataDir, equalTo(config.defaultUserDataDir));
        assertThat(config.appDataDir, equalTo(new File(config.userDataDir, config.appName)));
        assertThat(config.configFile, equalTo(configFile));
    }

    @Test
    public void whenBannedXmrNodesOptionIsSet_thenBannedXmrNodesPropertyReturnsItsValue() {
        Config config = configWithOpts(opt(BANNED_XMR_NODES, "foo.onion:8333,bar.onion:8333"));
        assertThat(config.bannedXmrNodes, contains("foo.onion:8333", "bar.onion:8333"));
    }

    @Test
    public void whenHelpOptionIsSet_thenIsHelpRequestedIsTrue() {
        assertFalse(new Config().helpRequested);
        assertTrue(configWithOpts(opt(HELP)).helpRequested);
    }

    @Test
    public void whenConfigIsConstructed_thenNoConsoleOutputSideEffectsShouldOccur() {
        PrintStream outOrig = System.out;
        PrintStream errOrig = System.err;
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        try (PrintStream outTest = new PrintStream(outBytes);
             PrintStream errTest = new PrintStream(errBytes)) {
            System.setOut(outTest);
            System.setErr(errTest);
            new Config();
            assertThat(outBytes.toString(), is(emptyString()));
            assertThat(errBytes.toString(), is(emptyString()));
        } finally {
            System.setOut(outOrig);
            System.setErr(errOrig);
        }
    }

    @Test
    public void whenConfigIsConstructed_thenAppDataDirAndSubdirsAreCreated() {
        Config config = new Config();
        assertTrue(config.appDataDir.exists());
        assertTrue(config.keyStorageDir.exists());
        assertTrue(config.storageDir.exists());
        assertTrue(config.torDir.exists());
        assertTrue(config.walletDir.exists());
    }

    @Test
    public void whenAppDataDirCannotBeCreatedThenUncheckedIoExceptionIsThrown() {
        // set a userDataDir that is actually a file so appDataDir cannot be created
        Exception exception = assertThrows(UncheckedIOException.class, () -> {
            File aFile = Files.createTempFile("A", "File").toFile();
            configWithOpts(opt(USER_DATA_DIR, aFile));
        });

        String expectedMessage = "could not be created";
        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    public void whenAppDataDirIsSymbolicLink_thenAppDataDirCreationIsNoOp() throws IOException {
        Path parentDir = createTempDirectory("parent");
        Path targetDir = parentDir.resolve("target");
        Path symlink = parentDir.resolve("symlink");
        Files.createDirectory(targetDir);
        try {
            Files.createSymbolicLink(symlink, targetDir);
        } catch (Throwable ex) {
            // An error occurred trying to create a symbolic link, likely because the
            // operating system (e.g. Windows) does not support it, so we abort the test.
            return;
        }
        configWithOpts(opt(APP_DATA_DIR, symlink));
    }


    // == TEST SUPPORT FACILITIES ========================================================

    static Config configWithOpts(Opt... opts) {
        String[] args = new String[opts.length];
        for (int i = 0; i < opts.length; i++)
            args[i] = opts[i].toString();
        return new Config(args);
    }

    static Opt opt(String name) {
        return new Opt(name);
    }

    static Opt opt(String name, Object arg) {
        return new Opt(name, arg.toString());
    }

    static class Opt {
        private final String name;
        private final String arg;

        public Opt(String name) {
            this(name, null);
        }

        public Opt(String name, String arg) {
            this.name = name;
            this.arg = arg;
        }

        @Override
        public String toString() {
            return format("--%s%s", name, arg != null ? ("=" + arg) : "");
        }
    }
}
