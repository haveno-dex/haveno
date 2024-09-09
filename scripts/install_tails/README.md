# Install Haveno on Tails

Install Haveno on Tails by following these steps:

1. Enable persistent storage dotfiles and admin password before starting tails.
2. Download [haveno-install.sh](haveno-install.sh):
    
    ```
    curl -fsSLO https://github.com/haveno-dex/haveno/raw/master/scripts/install_tails/haveno-install.sh
    ```
    
3. Execute installation script:
    
    ```
    bash haveno-install.sh "<REPLACE_WITH_BINARY_ZIP_URL>" "<REPLACE_WITH_PGP_FINGERPRINT>"
    ```
    
    For example:
  
    ```
    bash haveno-install.sh "https://github.com/havenoexample/haveno-example/releases/download/v1.0.11/haveno_amd64_deb-latest.zip" "FAA2 4D87 8B8D 36C9 0120 A897 CA02 DAC1 2DAE 2D0F"
    ```
    
4. Upon successful execution of the script (no errors), the Haveno release will be installed to persistent storage and can be launched via the desktop shortcut in the 'Other' section of the start menu.
