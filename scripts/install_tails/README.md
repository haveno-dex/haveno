# Install Haveno on Tails

Install Haveno on Tails by following these steps:

1. Enable persistent storage dotfiles and admin password before starting Tails.
2. Execute a one-line installation command to download and install Haveno:
    
    ```
    curl -x socks5h://127.0.0.1:9050 -fsSLO https://github.com/haveno-dex/haveno/raw/master/scripts/install_tails/haveno-install.sh && bash haveno-install.sh "<REPLACE_WITH_BINARY_ZIP_URL>" "<REPLACE_WITH_PGP_FINGERPRINT>"
    ```
    
    Replace the binary zip URL and PGP fingerprint for the network you're using. For example:
    
    ```
    curl -x socks5h://127.0.0.1:9050 -fsSLO https://github.com/haveno-dex/haveno/raw/master/scripts/install_tails/haveno-install.sh && bash haveno-install.sh "https://github.com/havenoexample/haveno-example/releases/download/v1.0.11/haveno_amd64_deb-latest.zip" "FAA2 4D87 8B8D 36C9 0120 A897 CA02 DAC1 2DAE 2D0F"
    ```
    
3. Upon successful execution of the script (no errors), the Haveno release will be installed to persistent storage and can be launched via the desktop shortcut in the 'Other' section of the start menu.

> [!note]
> If you have already installed Haveno on Tails, we recommend moving your data directory (/home/amnesia/Persistent/Haveno-example) to the new default location (/home/amnesia/Persistent/haveno/Data/Haveno-example), to retain your history and for future support.