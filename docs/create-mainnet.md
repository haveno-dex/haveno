# Create Haveno network quick start guide

These instructions describe how to quickly start a public Haveno network running on Monero's main network from your local machine, which is useful for demonstration and testing.

For a more robust and decentralized deployment to VPS for reliable uptime, see the [deployment guide](./deployment-guide.md).

## Clone and build Haveno

```
git clone https://github.com/haveno-dex/haveno.git
cd haveno
git checkout master
make clean && make
```

## Start a Monero node

In a new terminal window, run `make monerod` to start and sync a Monero node on mainnet.

Seed nodes and arbitrators require a local, unrestricted Monero node for performance and functionality.

## Start and register seed nodes

In a new terminal window, run: `make seednode`.

The seed node's onion address will print to the screen (denoted by `Hidden service`). Record the seed node's URL to xmr_mainnet.seednodes with port 1002. For example, `4op7nzb65z4xg2taqmt2uhih7uwi3ya25yx5bvskbkjisnq7rwepzvad.onion:1002`.

In a new terminal window, run: `make seednode2`.

The seed node's onion address will print to the screen (denoted by `Hidden service`). Record the seed node's URL to xmr_mainnet.seednodes with port 1003. For example, `abwyc7ccjq4oyiej5z3dpwupzql34nnedaft5jc5l2dbocko7naosrjqd.onion:1003`.

Stop both seed nodes.

## Register public key(s) for various roles

Run `./gradlew generateKeypairs`. A list of public/private keypairs will print to the screen which can be used for different roles like arbitration, sending private notifications, etc.

For demonstration, we can use the first generated public/private keypair for all roles, but you can customize as desired.

Hardcode the public key(s) in these files:

- [AlertManager.java](https://github.com/haveno-dex/haveno/blob/2ff149b1ebcfd1a4c40d77d05d4ee9981353a8a6/core/src/main/java/haveno/core/alert/AlertManager.java#L112)
- [ArbitratorManager.java](https://github.com/haveno-dex/haveno/blob/2ff149b1ebcfd1a4c40d77d05d4ee9981353a8a6/core/src/main/java/haveno/core/support/dispute/arbitration/arbitrator/ArbitratorManager.java#L81)
- [FilterManager.java](https://github.com/haveno-dex/haveno/blob/2ff149b1ebcfd1a4c40d77d05d4ee9981353a8a6/core/src/main/java/haveno/core/filter/FilterManager.java#L135)
- [PrivateNotificationManager.java](https://github.com/haveno-dex/haveno/blob/2ff149b1ebcfd1a4c40d77d05d4ee9981353a8a6/core/src/main/java/haveno/core/alert/PrivateNotificationManager.java#L111)

## Change the default folder name for Haveno application data

To avoid user data corruption when using multiple Haveno networks, change the default folder name for Haveno's application data on your network:

- Change `DEFAULT_APP_NAME` in [HavenoExecutable.java](https://github.com/haveno-dex/haveno/blob/64acf86fbea069b0ae9f9bce086f8ecce1e91b87/core/src/main/java/haveno/core/app/HavenoExecutable.java#L85).
- Change `appName` throughout the [Makefile](https://github.com/haveno-dex/haveno/blob/64acf86fbea069b0ae9f9bce086f8ecce1e91b87/Makefile#L479) accordingly.

For example, change "Haveno" to "HavenoX", which will use this application folder:

- Linux: ~/.local/share/HavenoX/
- macOS: ~/Library/Application Support/HavenoX/
- Windows: ~\AppData\Roaming\HavenoX\

## Change the P2P network version

To avoid interference with other networks, change `P2P_NETWORK_VERSION` in [Version.java](https://github.com/haveno-dex/haveno/blob/a7e90395d24ec3d33262dd5d09c5faec61651a51/common/src/main/java/haveno/common/app/Version.java#L83).

For example, change it to `"B"`.

## Start the seed nodes

Rebuild for the previous changes to the source code to take effect: `make skip-tests`.

In a new terminal window, run: `make seednode`.

In a new terminal window, run: `make seednode2`.

> **Notes**
> * Avoid all seed nodes going offline at the same time. If all seed nodes go offline at the same time, the network will be reset, including registered arbitrators, the network filter object, and trade history. In that case, arbitrators need to restart or re-register, and the network filter object needs to be re-applied. This should be done immediately or clients will cancel their offers due to the signing arbitrators being unregistered and no replacements being available to re-sign.
> * At least 2 seed nodes should be run because the seed nodes restart once per day.

## Start and register the arbitrator

In a new terminal window, run: `make arbitrator-desktop-mainnet`.

Ignore the error about not receiving a filter object.

Go to the `Account` tab and then press `ctrl + r`. A prompt will open asking to enter the key to register the arbitrator. Enter your private key.

## Set a network filter on mainnet

On mainnet, the p2p network is expected to have a filter object for offers, onions, currencies, payment methods, etc.

To set the network's filter object:

1. Enter `ctrl + f` in the arbitrator or other Haveno instance to open the Filter window.
2. Enter a developer private key from the previous steps and click "Add Filter" to register.

## Other configuration

### Set the network's release date

Set the network's approximate release date by setting `RELEASE_DATE` in HavenoUtils.java.

This will prevent posting sell offers which no buyers can take before any buyer accounts are signed and aged, while the network bootstraps.

After a period (default 60 days), the limit is lifted and sellers can post offers exceeding unsigned buy limits, but they will receive an informational warning for an additional period (default 6 months after release).

The defaults can be adjusted with the related constants in HavenoUtils.java.

### Optionally configure trade fees

Trade fees can be configured in HavenoUtils.java. The maker and taker fee percents can be adjusted.

Set `ARBITRATOR_ASSIGNS_TRADE_FEE_ADDRESS` to `true` for the arbitrator to assign the trade fee address, which defaults to their own wallet.

Otherwise set `ARBITRATOR_ASSIGNS_TRADE_FEE_ADDRESS` to `false` and set the XMR address in `getGlobalTradeFeeAddress()` to collect all trade fees to a single address (e.g. a multisig wallet shared among network administrators).

### Optionally start a price node

The price node is separated from Haveno and is run as a standalone service. To deploy a pricenode on both TOR and clearnet, see the instructions on the repository: https://github.com/haveno-dex/haveno-pricenode.

After the price node is built and deployed, add the price node to `DEFAULT_NODES` in [ProvidersRepository.java](https://github.com/haveno-dex/haveno/blob/3cdd88b56915c7f8afd4f1a39e6c1197c2665d63/core/src/main/java/haveno/core/provider/ProvidersRepository.java#L50).

### Update the download URL

Change every instance of `https://haveno.exchange/downloads` to your download URL. For example, `https://havenoexample.com/downloads`.

## Review all local changes

For comparison, placeholders to run on mainnet are marked [here on this branch](https://github.com/haveno-dex/haveno/tree/mainnet_placeholders).

## Start users for testing

Optionally set `--ignoreLocalXmrNode` to `true` in Makefile for the user applications to use public nodes and ignore the locally running Monero node, in order test real network conditions.

Start user1: `make user1-desktop-mainnet`.

Start user2: `make user2-desktop-mainnet`.

Test trades among the users and arbitrator over Monero's mainnet.

## Share your git repository for others to test

To share your network for others to use, commit your local changes and share your *git repository's URL*.

It is not sufficient to share only your seed node addresses, because their application must be built with the same public keys and other configuration to work properly.

After sharing your git repository, others can build and start their application with:

```
git clone <your repo url>
cd haveno
make skip-tests
make user1-desktop-mainnet
```

However a [more robust VPS setup](./deployment-guide.md) should be used for actual trades.

## Build the installers for distribution

To build the installers for distribution, first change `XMR_STAGENET` to `XMR_MAINNET` in [package.gradle](https://github.com/haveno-dex/haveno/blob/1bf83ecb8baa06b6bfcc30720f165f20b8f77025/desktop/package/package.gradle#L278).

Then [follow instructions](https://github.com/haveno-dex/haveno/blob/master/desktop/package/README.md) to build the installers for distribution.

Alternatively, the installers are built automatically by GitHub.