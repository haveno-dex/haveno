# Package Installers

Run `./gradlew packageInstallers` on the corresponding platform. Wix must be installed for Windows packaging.

## Icons

Icons (Haveno.zip) were obtained from https://github.com/haveno-dex/haveno-meta/issues/1#issuecomment-819741689.

### Linux

The linux package requires the correct packaging tools installed. You may run into the following errors:

```
Error: Invalid or unsupported type: [deb]
```
```
Error: Invalid or unsupported type: [rpm]
```

On Ubuntu, resolve by running `sudo apt install rpm`. For deb, ensure dpkg is installed.

```
Exception in thread "main" java.io.IOException: Failed to rename /tmp/Bisq-stripped15820156885694375398.tmp to /storage/src/haveno/desktop/build/libs/fatJar/desktop-1.0.0-SNAPSHOT-all.jar
	at bisq.tools.Utils.renameFile(Utils.java:36)
	at io.github.zlika.reproducible.StipZipFile.strip(StipZipFile.java:35)
	at bisq.tools.DeterministicBuildTool.main(DeterministicBuildTool.java:24)

```

This may happen if the source folder is on a different hard drive than the system `tmp` folder. The tools-1.0.jar calls renameTo to rename the deterministic jar back to the fat jar location. You can temporarily change your temp directory on linux:

```
export _JAVA_OPTIONS="-Djava.io.tmpdir=/storage/tmp"
```

### MacOs

Svg was converted into a 1024x1024 pixel PNG using https://webkul.github.io/myscale/, then converted to icns for macosx
here https://cloudconvert.com/png-to-icns

#### Known Issues

Signing is not implemented.

### Windows

Pngs were resized and pasted into the WixUi images using paint. [CloudConvert](https://cloudconvert.com) was used to convert the Haveno png icon to ico.

#### Known Issues

The installer's final step "Launch Haveno" has a different background color. The setup executable does not have an icon.
