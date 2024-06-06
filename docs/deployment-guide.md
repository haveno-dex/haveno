# Deployment Guide

This guide describes how to deploy a Haveno network:

- Manage services on a VPS
- Fork and build Haveno
- Start a Monero node
- Build and start price nodes
- Add seed nodes
- Add arbitrators
- Configure trade fees and other configuration
- Build Haveno installers for distribution
- Send alerts to update the application and other maintenance

## Manage services on a VPS

Haveno's services should be run on a VPS for reliable uptime.

The seed node, price node, and Monero node can be run as system services. Scripts are available for reference in [scripts/deployment](scripts/deployment) to customize and run system services.

Arbitrators can be started in a Screen session and then detached to run in the background.

Some good hints about how to secure a VPS are in [Monero's meta repository](https://github.com/monero-project/meta/blob/master/SERVER_SETUP_HARDENING.md).

## Fork and build Haveno

First fork Haveno to a public repository. Then build Haveno:

```
git clone <your fork url>
cd haveno
git checkout <latest tag>
make clean && make
```

## Start a Monero node

Seed nodes and arbitrators must use a local, unrestricted Monero node for performance and functionality.

To run a private Monero node as a system service, customize and deploy private-stagenet.service and private-stagenet.conf.

Optionally customize and deploy monero-stagenet.service and monero-stagenet.conf to run a public Monero node as a system service for Haveno clients to use.

You can also start the Monero node in your current terminal session by running `make monerod` for mainnet or `make monerod-stagenet` for stagenet.

## Build and start price nodes

The price node is separated from Haveno and is run as a standalone service. To deploy a pricenode on both TOR and clearnet, see the instructions on the repository: https://github.com/haveno-dex/haveno-pricenode.

After the price node is built and deployed, add the price node to `DEFAULT_NODES` in [ProvidersRepository.java](https://github.com/haveno-dex/haveno/blob/3cdd88b56915c7f8afd4f1a39e6c1197c2665d63/core/src/main/java/haveno/core/provider/ProvidersRepository.java#L50).

Customize and deploy haveno-pricenode.env and haveno-pricenode.service to run as a system service.

## Add seed nodes

For each seed node:

1. [Build the Haveno repository](#fork-and-build-haveno).
2. [Start a local Monero node](#start-a-local-monero-node).
3. Run `make seednode` to run a seednode on Monero's mainnet or `make seednode-stagenet` to run a seednode on Monero's stagenet.
4. The node will print its onion address to the console. Record the onion address in `core/src/main/resources/xmr_<network>.seednodes`. Be careful to record full addresses correctly.
5. Update all seed nodes, arbitrators, and user applications for the change to take effect.

Customize and deploy haveno-seednode.service to run a seed node as a system service.

Each seed node requires a locally running Monero node. You can use the default port or configure it manually with `--xmrNode`, `--xmrNodeUsername`, and `--xmrNodePassword`.

Rebuild all seed nodes any time the list of registered seed nodes changes.

> **Notes**
> * Avoid all seed nodes going offline at the same time. If all seed nodes go offline at the same time, the network will be reset, including registered arbitrators, the network filter object, and trade history. In that case, arbitrators need to restart or re-register, and the network filter object needs to be re-applied. This should be done immediately or clients will cancel their offers due to the signing arbitrators being unregistered and no replacements being available to re-sign.
> * At least 2 seed nodes should be run because the seed nodes restart once per day.

## Register keypairs with privileges

### Register keypair(s) with developer privileges

1. [Build the Haveno repository](#fork-and-build-haveno).
2. Generate public/private keypairs for developers: `./gradlew generateKeypairs`
3. Add the developer public keys in the constructor of FilterManager.java.
4. Update all seed nodes, arbitrators, and user applications for the change to take effect.

### Register keypair(s) with alert privileges

1. [Build the Haveno repository](#fork-and-build-haveno).
2. Generate public/private keypairs for alerts: `./gradlew generateKeypairs`
2. Add the public keys in the constructor of AlertManager.java.
4. Update all seed nodes, arbitrators, and user applications for the change to take effect.

### Register keypair(s) with private notification privileges

1. [Build the Haveno repository](#fork-and-build-haveno).
2. Generate public/private keypairs for private notifications: `./gradlew generateKeypairs`
2. Add the public keys in the constructor of PrivateNotificationManager.java.
4. Update all seed nodes, arbitrators, and user applications for the change to take effect.

## Add arbitrators

For each arbitrator:

1. [Build the Haveno repository](#fork-and-build-haveno).
2. Generate a public/private keypair for the arbitrator: `./gradlew generateKeypairs`
3. Add the public key to `getPubKeyList()` in [ArbitratorManager.java](https://github.com/haveno-dex/haveno/blob/3cdd88b56915c7f8afd4f1a39e6c1197c2665d63/core/src/main/java/haveno/core/support/dispute/arbitration/arbitrator/ArbitratorManager.java#L62).
4. Update all seed nodes, arbitrators, and user applications for the change to take effect.
5. [Start a local Monero node](#start-a-local-monero-node).
6. Start the Haveno desktop application using the application launcher or e.g. `make arbitrator-desktop-mainnet`
7. Go to the `Account` tab and then press `ctrl + r`. A prompt will open asking to enter the key to register the arbitrator. Enter your private key.

The arbitrator is now registered and ready to accept requests for dispute resolution.

**Notes**
- Arbitrators must use a local Monero node with unrestricted RPC in order to submit and flush transactions from the pool.
- Arbitrators should remain online as much as possible in order to balance trades and avoid clients spending time trying to contact offline arbitrators. A VPS or dedicated machine running 24/7 is highly recommended.
- Remember that for the network to run correctly and people to be able to open and accept trades, at least one arbitrator must be registered on the network.
- IMPORTANT: Do not reuse keypairs on multiple arbitrator instances.

## Remove an arbitrator

> **Note**
> Ensure the arbitrator's trades are completed before retiring the instance.

1. Start the arbitrator's desktop application using the application launcher or e.g. `make arbitrator-desktop-mainnet` from the root of the repository.
2. Go to the `Account` tab and click the button to unregister the arbitrator.

## Set a network filter on mainnet

On mainnet, the p2p network is expected to have a filter object for offers, onions, currencies, payment methods, etc.

To set the network's filter object:

1. Enter `ctrl + f` in the arbitrator or other Haveno instance to open the Filter window.
2. Enter a developer private key from the previous steps and click "Add Filter" to register.

> **Note**
> If all seed nodes are restarted at the same time, arbitrators and the filter object will become unregistered and will need to be re-registered.

## Change the default folder name for Haveno application data

To avoid user data corruption when using multiple Haveno networks, change the default folder name for Haveno's application data on your network:

- Change `DEFAULT_APP_NAME` in [HavenoExecutable.java](https://.com/haveno-dex/haveno/blob/1aa62863f49a15e8322a8d96e58dc0ed37dec4eb/core/src/main/java/haveno/core/app/HavenoExecutable.java#L85).
- Change `appName` throughout the [Makefile](https://github.com/haveno-dex/haveno/blob/64acf86fbea069b0ae9f9bce086f8ecce1e91b87/Makefile#L479) accordingly.

For example, change "Haveno" to "HavenoX", which will use this application folder:

- Linux: ~/.local/share/HavenoX/
- macOS: ~/Library/Application Support/HavenoX/
- Windows: ~\AppData\Roaming\HavenoX\

## Set the network's release date

Optionally set the network's approximate release date by setting `RELEASE_DATE` in HavenoUtils.java.

This will prevent posting sell offers which no buyers can take before any buyer accounts are signed and aged, while the network bootstraps.

After a period (default 60 days), the limit is lifted and sellers can post offers exceeding unsigned buy limits, but they will receive an informational warning for an additional period (default 6 months after release).

The defaults can be adjusted with the related constants in HavenoUtils.java.

## Configure trade fees

Trade fees can be configured in HavenoUtils.java. The maker and taker fee percents can be adjusted.

Set `ARBITRATOR_ASSIGNS_TRADE_FEE_ADDRESS` to `true` for the arbitrator to assign the trade fee address, which defaults to their own wallet.

Otherwise set `ARBITRATOR_ASSIGNS_TRADE_FEE_ADDRESS` to `false` and set the XMR address in `getGlobalTradeFeeAddress()` to collect all trade fees to a single address (e.g. a multisig wallet shared among network administrators).

## Start users for testing

Start user1 on Monero's mainnet using `make user1-desktop-mainnet` or Monero's stagenet using `make user1-desktop-stagenet`.

Similarly, start user2 on Monero's mainnet using `make user2-desktop-mainnet` or Monero's stagenet using `make user2-desktop-stagenet`.

Test trades among the users and arbitrator.

## Build Haveno installers for distribution

For mainnet, first modify [package.gradle](https://github.com/haveno-dex/haveno/blob/aeb0822f9fc72bd5a0e23d0c42c2a8f5f87625bb/desktop/package/package.gradle#L252) to `--arguments --baseCurrencyNetwork=XMR_MAINNET`.

Then follow these instructions: https://github.com/haveno-dex/haveno/blob/master/desktop/package/README.md.

## Send alerts to update the application

<b>Upload updated installers for download</b>

* In https://<domain>/downloads/<version>/, upload the installer files: Haveno-<version>.jar.txt, signingkey.asc, Haveno-<version>.dmg, Haveno-<version>.dmg.asc, and files for Linux and Windows.
* In https://<domain>/pubkey/, upload pub key files, e.g. F379A1C6.asc.

<b>Set the mandatory minimum version for trading (optional)</b>

If applicable, update the mandatory minimum version for trading, by entering `ctrl + f` to open the Filter window, enter a private key with developer privileges, and enter the minimum version (e.g. 1.0.16) in the field labeled "Min. version required for trading".

<b>Send update alert</b>

Enter `ctrl + m` to open the window to send an update alert.

Enter a private key which is registered to send alerts.

Enter the alert message and new version number, then click the button to send the notification.

## Manually sign payment accounts as the arbitrator

Arbitrators can manually sign payment accounts. First open the legacy UI.

### Sign payment account after trade is completed

1. Go to Portfolio > History > open trade details > click 'DETAIL DATA' button.
2. Copy the `<witness hash>,<pub key hash>` string for the buyer or seller.
3. Go to Account > `ctrl + i` > `ctrl + p`.
5. Paste the buyer or seller's `<witness hash>,<pub key hash>` string.
6. Click the "Import unsigned account age witness" button to confirm.

### Sign payment account from dispute

1. Go to Account > `ctrl + i` > `ctrl + s`.
2. Select payment accounts to sign from disputes.

### Sign unsigned witness pub keys

1. Go to Account > `ctrl + i` > `ctrl + o`.

## Other tips

* If a dispute does not open properly, try manually reopening the dispute with a keyboard shortcut: `ctrl + o`.
* To send a private notification to a peer: click the user icon and enter `alt + r`. Enter a private key which is registered to send private notifications.