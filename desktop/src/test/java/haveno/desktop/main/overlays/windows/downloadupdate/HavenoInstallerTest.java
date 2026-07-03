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
import haveno.desktop.main.overlays.windows.downloadupdate.HavenoInstaller.FileDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
public class HavenoInstallerTest {
    @Test
    public void call() throws Exception {
    }

    // Full primary-key fingerprint of the /downloadUpdate/F379A1C6.asc test key.
    private static final String TEST_KEY_FINGERPRINT = "1DC3C8C4316A698AC494039CF5B84436F379A1C6";

    @Test
    public void verifySignature() throws Exception {
        URL url = this.getClass().getResource("/downloadUpdate/test.txt");
        File dataFile = new File(url.toURI().getPath());
        url = this.getClass().getResource("/downloadUpdate/test.txt.asc");
        File sigFile = new File(url.toURI().getPath());
        url = this.getClass().getResource("/downloadUpdate/F379A1C6.asc");
        File pubKeyFile = new File(url.toURI().getPath());

        // valid signature made by the pinned key
        assertEquals(HavenoInstaller.VerifyStatusEnum.OK, HavenoInstaller.verifySignature(pubKeyFile, sigFile, dataFile, TEST_KEY_FINGERPRINT));

        // valid signature but the key's fingerprint does not match the pinned fingerprint must fail
        String wrongFingerprint = TEST_KEY_FINGERPRINT.replace('F', 'A');
        assertEquals(HavenoInstaller.VerifyStatusEnum.FAIL, HavenoInstaller.verifySignature(pubKeyFile, sigFile, dataFile, wrongFingerprint));

        // no pinned fingerprint provided must fail
        assertEquals(HavenoInstaller.VerifyStatusEnum.FAIL, HavenoInstaller.verifySignature(pubKeyFile, sigFile, dataFile, null));

        url = this.getClass().getResource("/downloadUpdate/test_bad.txt");
        dataFile = new File(url.toURI().getPath());
        url = this.getClass().getResource("/downloadUpdate/test_bad.txt.asc");
        sigFile = new File(url.toURI().getPath());
        url = this.getClass().getResource("/downloadUpdate/F379A1C6.asc");
        pubKeyFile = new File(url.toURI().getPath());

        // tampered data does not match the signature, even with the correct pinned fingerprint
        assertEquals(HavenoInstaller.VerifyStatusEnum.FAIL, HavenoInstaller.verifySignature(pubKeyFile, sigFile, dataFile, TEST_KEY_FINGERPRINT));
    }

    @Test
    public void getFileName() throws Exception {
    }

    @Test
    public void getDownloadType() throws Exception {
    }

    @Test
    public void getIndex() throws Exception {
    }

    @Test
    public void getSigFileDescriptors() throws Exception {
        HavenoInstaller havenoInstaller = new HavenoInstaller();
        FileDescriptor installerFileDescriptor = FileDescriptor.builder().fileName("filename.txt").id("filename").loadUrl("url://filename.txt").build();
        FileDescriptor key1 = FileDescriptor.builder().fileName("key1").id("key1").loadUrl("").build();
        FileDescriptor key2 = FileDescriptor.builder().fileName("key2").id("key2").loadUrl("").build();
        List<FileDescriptor> sigFileDescriptors = havenoInstaller.getSigFileDescriptors(installerFileDescriptor, Lists.newArrayList(key1));
        assertEquals(1, sigFileDescriptors.size());
        sigFileDescriptors = havenoInstaller.getSigFileDescriptors(installerFileDescriptor, Lists.newArrayList(key1, key2));
        assertEquals(2, sigFileDescriptors.size());
        log.info("test");

    }
}
