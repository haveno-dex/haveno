Follow these instructions to create installers for the Haveno Java desktop application on each platform.

## Build Haveno binaries

`make clean && make` (or `make clean && make skip-tests`)

## Create installer and install on each platform

> **Note**
> These steps will delete the previously built Haveno binaries, so they'll need rebuilt after.

#### Linux

1. `./gradlew packageInstallers`
2. Confirm prompts.
3. Path to installer printed at end. Execute to install: `sudo dpkg -i <path>.deb`

Haveno data folder on Linux: `/home/<username>/.local/share/Haveno/`

#### Mac

1. `./gradlew packageInstallers`
2. Confirm prompts.
3. Path to installer printed at end.
4. `open <path>`
5. Open installer and drag Haveno.app to Applications.
6. `sudo xattr -rd com.apple.quarantine /Applications/Haveno.app`
7. Right click /Applications/Haveno.app > Open. Repeat again if necessary, despite being "damaged".

Haveno data folder on Mac: `/Users/<username>/Library/Application Support/Haveno/`

#### Windows

1. Enable .NET Framework 3.5:
    1. Open the Control Panel on your Windows system.
    2. Click on "Programs and Features" or "Uninstall a Program."
    3. On the left-hand side, click on "Turn Windows features on or off."
    4. In the "Windows Features" dialog box, scroll down and find the ".NET Framework 3.5 (includes .NET 2.0 and 3.0)" option.
    5. Check the box next to it to select it.
    6. Click "OK" to save the changes and exit the dialog box.
    7. Windows will download and install the required files and components to enable the .NET Framework 3.5. This may take several minutes, depending on your internet connection speed and system configuration.
    8. Once the installation is complete, you will need to restart your computer to apply the changes.
2. Install wix: https://wixtoolset.org
3. Open MSYS2 for the following commands.
4. `export PATH=$PATH:$JAVA_HOME/bin:"C:\Program Files (x86)\WiX Toolset v3.11\bin"`
5. `./gradlew packageInstallers`
6. Confirm prompts.
7. Path to installer printed at end. Execute to install.

Haveno data folder on Windows: `~\AppData\Roaming\Haveno\`

## Copy installer and rebuild Haveno binaries

1. Copy the installer to a safe location because it will be deleted in the next step.
2. `make clean && make` (or `make clean && make skip-tests`) to rebuild Haveno apps.


## Additional Notes

### Icons

Icons (Haveno.zip) were obtained from https://github.com/haveno-dex/haveno-meta/issues/1#issuecomment-819741689.

#### Linux

The linux package requires the correct packaging tools installed. You may run into the following errors:

```
Error: Invalid or unsupported type: [deb]
```
```
Error: Invalid or unsupported type: [rpm]
```

On Ubuntu, resolve by running `sudo apt install rpm`. For deb, ensure dpkg is installed.

```
Exception in thread "main" java.io.IOException: Failed to rename /tmp/Haveno-stripped15820156885694375398.tmp to /storage/src/haveno/desktop/build/libs/fatJar/desktop-1.0.0-SNAPSHOT-all.jar
	at haveno.tools.Utils.renameFile(Utils.java:36)
	at io.github.zlika.reproducible.StipZipFile.strip(StipZipFile.java:35)
	at haveno.tools.DeterministicBuildTool.main(DeterministicBuildTool.java:24)

```

This may happen if the source folder is on a different hard drive than the system `tmp` folder. The tools-1.0.jar calls renameTo to rename the deterministic jar back to the fat jar location. You can temporarily change your temp directory on linux:

```
export _JAVA_OPTIONS="-Djava.io.tmpdir=/storage/tmp"
```

#### MacOs

Svg was converted into a 1024x1024 pixel PNG using https://webkul.github.io/myscale/, then converted to icns for macosx
here https://cloudconvert.com/png-to-icns

##### Known Issues

Signing is not implemented.

#### Windows

Pngs were resized and pasted into the WixUi images using paint. [CloudConvert](https://cloudconvert.com) was used to convert the Haveno png icon to ico.

#### Known Issues

The installer's final step "Launch Haveno" has a different background color. The setup executable does not have an icon.
