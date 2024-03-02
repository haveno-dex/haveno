# Deployment Guide

This guide describes how to deploy a Haveno network:

- Manage services on a VPS
- Build Haveno
- Start a Monero node
- Build and start price nodes
- Create and register seed nodes
- Register keypairs with administrative privileges
- Create and register arbitrators
- Set a network filter
- Build Haveno installers for distribution
- Send alerts to update the application

## Manage services on a VPS

Haveno's services should be run on a VPS for reliable uptime.

The seed node, price node, and Monero node can be run as system services. Scripts are available for reference in [scripts/deployment](scripts/deployment) to customize and run system services.

Arbitrators can be started in a Screen session and then detached to run in the background.

Some good hints about how to secure a VPS are in [Monero's meta repository](https://github.com/monero-project/meta/blob/master/SERVER_SETUP_HARDENING.md).

## Build Haveno

```
git clone https://github.com/haveno-dex/haveno.git
cd haveno
git checkout <latest tag>
make clean && make
```

See [installing.md](installing.md) for more detail.

## Start a Monero node

Seed nodes and arbitrators should use a local, trusted Monero node for performance and function.

Arbitrators require a trusted node in order to submit and flush transactions from the pool.

Customize and deploy private-stagenet.service and private-stagenet.conf to run a private Monero node as a system service for the seed node and arbitrator to use locally.

Optionally customize and deploy monero-stagenet.service and monero-stagenet.conf to run a public Monero node as a system service for Haveno clients to use.

You can also start the Monero node in your current terminal session by running `make monerod` for mainnet or `make monerod-stagenet` for stagenet.

## Build and start price nodes

The price node is separated from Haveno and is to be run as a standalone service. To deploy a pricenode on both TOR and clearnet, see the instructions on the repository: https://github.com/haveno-dex/haveno-pricenode

After the price node is built and deployed, add the price node to `DEFAULT_NODES` in ProvidersRepository.java.

Customize and deploy haveno-pricenode.env and haveno-pricenode.service to run as a system service.

## Create and register seed nodes

From the root of the repository, run `make seednode` to run a seednode on Monero's mainnet or `make seednode-stagenet` to run a seednode on Monero's stagenet.

The node will print its onion address to the console. Record the onion address in `core/src/main/resources/xmr_<network>.seednodes` and remove unused seed nodes from `xmr_<network>.seednodes`. Be careful to record full addresses correctly.

Customize and deploy haveno-seednode.service to run a seed node as a system service.

Each seed node requires a locally running Monero node. You can use the default port or configure it manually with `--xmrNode`, `--xmrNodeUsername`, and `--xmrNodePassword`.

Rebuild all seed nodes any time the list of registered seed nodes changes.

## Register keypairs with arbitrator privileges

1. Run core/src/test/java/haveno/core/util/GenerateKeypairs.java to generate public/private keypairs for arbitrator privileges.
2. Add arbitrator public keys to the corresponding network type in ArbitratorManager.java `getPubKeyList()`.

## Register keypairs with developer privileges

Keypairs with developer privileges are able to set the network's filter object, which can filter out offers, onions, currencies, payment methods, etc.

1. Run core/src/test/java/haveno/core/util/GenerateKeypairs.java to generate public/private keypairs for developer privileges.
2. Set developer public keys in the constructor of FilterManager.java.

## Register keypair with alert privileges

A keypair with alert privileges is able to send alerts, e.g. to update the application.

1. Run core/src/test/java/haveno/core/util/GenerateKeypairs.java to generate a public/private keypair for alert privileges.
2. Set alert public keys in the constructor of AlertManager.java.

## Register keypairs with private notification privileges

1. Run core/src/test/java/haveno/core/util/GenerateKeypairs.java to generate public/private keypairs for private notification privileges.
2. Set public keys in the constructor of PrivateNotificationManager.java.

## Set XMR address to collect trade fees

Set the XMR address to collect trade fees in `getTradeFeeAddress()` in HavenoUtils.java.

## Set the network's release date

Optionally set the network's approximate release date by setting `RELEASE_DATE` in HavenoUtils.java.

This will prevent posting sell offers which no buyers can take before any buyer accounts are signed and aged, while the network bootstraps.

After a period (default 60 days), the limit is lifted and sellers can post offers exceeding unsigned buy limits, but they will receive an informational warning for an additional period (default 6 months after release).

The defaults can be adjusted with the related constants in HavenoUtils.java.

## Create and register arbitrators

Before running the arbitrator, remember that at least one seednode should already be deployed and its address listed in `core/src/main/resources/xmr_<network>.seednodes`.

First rebuild Haveno: `make skip-tests`.

Run `make arbitrator-desktop` to run an arbitrator on Monero's mainnet or `make arbitrator-desktop-stagenet` to run an arbitrator on Monero's stagenet.

The Haveno GUI will open. If on mainnet, ignore the error about not receiving a filter object which is not added yet. Click on the `Account` tab and then press `ctrl + r`. A prompt will open asking to enter the key to register the arbitrator. Use a key generated in the previous steps and complete the registration. The arbitrator is now registered and ready to accept requests of dispute resolution.

Arbitrators should remain online as much as possible in order to balance trades and avoid clients spending time trying to contact offline arbitrators. A VPS or dedicated machine running 24/7 is highly recommended.

Remember that for the network to run correctly and people to be able to open and accept trades, at least one arbitrator must be registered on the network.

IMPORTANT: Do not reuse keypairs, and remember to revoke the private keypair to terminate the arbitrator.

## Set a network filter on mainnet

On mainnet, the p2p network is expected to have a filter object for offers, onions, currencies, payment methods, etc.

To set the network's filter object:

1. Enter `ctrl + f` in the arbitrator or other Haveno instance to open the Filter window.
2. Enter a developer private key from the previous steps and click "Add Filter" to register.

> **Note**
> If all seed nodes are restarted at the same time, arbitrators and the filter object will become unregistered and will need to be re-registered.

## Start users for testing

Start user1 on Monero's mainnet using `make user1-desktop` or Monero's stagenet using `make user1-desktop-stagenet`.

Similarly, start user2 on Monero's mainnet using `make user2-desktop` or Monero's stagenet using `make user2-desktop-stagenet`.

Test trades among the users and arbitrator.

## Build Haveno installers for distribution

For mainnet, first modify [package.gradle](https://github.com/haveno-dex/haveno/blob/aeb0822f9fc72bd5a0e23d0c42c2a8f5f87625bb/desktop/package/package.gradle#L252) to remove the line with ` --arguments --baseCurrencyNetwork=XMR_STAGENET` (also remove the `+` on the preceding line).

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

* Avoid all seed nodes going offline at the same time. If all seed nodes go offline at the same time, arbitrator registration and the network filter will be fully reset, so all arbitrators will need to be re-registered, and the network filter will need to be recreated. This should be done immediately or clients will cancel their offers due to the signing arbitrators being unregistered and no replacements being available to re-sign.
* If a dispute does not open properly, try manually reopening the dispute with a keyboard shortcut: `ctrl + o`.
* To send a private notification to a peer: click the user icon and enter `alt + r`. Enter a private key which is registered to send private notifications.