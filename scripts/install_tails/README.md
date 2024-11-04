# Install Haveno on Tails

After you already have a [Tails USB](https://tails.net/install/linux/index.en.html#download):

1. Enable [persistent storage](https://tails.net/doc/persistent_storage/index.en.html).
2. Set up [administration password](https://tails.net/doc/first_steps/welcome_screen/administration_password/).
3. Activate dotfiles in persistent storage settings.
4. Execute the following command in the terminal to download and execute the installation script. Enter the administration password when requested.

    ```
    curl -fsSLO https://github.com/haveno-dex/haveno/raw/master/scripts/install_tails/haveno-install.sh && bash haveno-install.sh <REPLACE_WITH_BINARY_ZIP_URL> <REPLACE_WITH_PGP_FINGERPRINT>
    ```
    
    Replace the binary zip URL and PGP fingerprint for the network you're using. For example:
    
    ```
    curl -fsSLO https://github.com/haveno-dex/haveno/raw/master/scripts/install_tails/haveno-install.sh && bash haveno-install.sh https://github.com/havenoexample/haveno-example/releases/download/v1.0.12/haveno-linux-deb.zip FAA24D878B8D36C90120A897CA02DAC12DAE2D0F
    ```
4. Start Haveno by finding the icon in the launcher under **Applications > Other**.

> [!note]
> If you have already installed Haveno on Tails, we recommend moving your data directory (/home/amnesia/Persistent/Haveno-example) to the new default location (/home/amnesia/Persistent/haveno/Data/Haveno-example), to retain your history and for future support.