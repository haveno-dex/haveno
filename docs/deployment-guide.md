# Deployment Guide

This guide describes how to deploy a Haveno network:

- Build Haveno
- Start a Monero node
- Build and start price nodes
- Create and register seed nodes
- Generate keypairs with privileges for arbitrators and developers
- Create and register arbitrators
- Set a network filter
- Build Haveno installers for distribution
- Manage services on a VPS (WIP)

## Build Haveno

```
git clone https://github.com/haveno-dex/haveno.git
cd haveno
git checkout <latest tag>
make clean && make
```

See [installing.md](installing.md) for more detail.

## Start a Monero node

Seed nodes and arbitrators should use a local, trusted Monero node.

Arbitrators require a trusted node in order to submit and flush transactions from the pool.

Start a Monero node by running `make monerod` for mainnet or `make monerod-stagenet` for stagenet.

## Build and start price nodes

The price node is separated from Haveno and is to be run as a standalone service. To deploy a pricenode on both Tor and clearnet, see the instructions on the repository: https://github.com/haveno-dex/haveno-pricenode

## Create and register seed nodes

From the root of the repository, run `make seednode` to run a seednode on Monero's mainnet or `make seednode-stagenet` to run a seednode on Monero's stagenet.

The node will print its onion address to the console.

If you are building a network from scratch: for each seednode, record the onion address in `core/src/main/resources/xmr_<network>.seednodes` and remove unused seed nodes from `xmr_<network>.seednodes`. Be careful to record full addresses correctly.

Rebuild the seed nodes any time the list of registered seed nodes changes.

Each seed node requires a locally running Monero node. You can use the default port or configure it manually with `--xmrNode`, `--xmrNodeUsername`, and `--xmrNodePassword`.

## Generate keypairs with arbitrator privileges

1. Run core/src/test/java/haveno/core/util/GenerateKeypairs.java to generate public/private keypairs for arbitrator privileges.
2. Add arbitrator public keys to the corresponding network type in ArbitratorManager.java `getPubKeyList()`.
3. Rebuild using `make skip-tests`.

## Generate keypairs with developer privileges

Developer keypairs are able to set the network's filter object, which can filter out offers, onions, currencies, payment methods, etc.

1. Run core/src/test/java/haveno/core/util/GenerateKeypairs.java to generate public/private keypairs for developer privileges.
2. Set developer public keys in the constructor of FilterManager.java.
3. Rebuild using `make skip-tests`.

## Set XMR address to collect trade fees

Set the XMR address to collect trade fees in /Users/woodser/git/haveno/core/src/main/java/haveno/core/trade/HavenoUtils.java `getTradeFeeAddress()`.

## Create and register arbitrators

Before running the arbitrator, remember that at least one seednode should already be deployed and its address listed in `core/src/main/resources/xmr_<network>.seednodes`.

Run `make arbitrator-desktop` to run an arbitrator on Monero's mainnet or `make arbitrator-desktop-stagenet` to run an arbitrator on Monero's stagenet.

The Haveno GUI will open. If on mainnet, ignore the error about not receiving a filter object which is not added yet. Click on the `Account` tab and then press `ctrl + r`. A prompt will open asking to enter the key to register the arbitrator. Use a key generated in the previous steps and complete the registration. The arbitrator is now registered and ready to accept requests of dispute resolution.

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

## Deploy to a VPS

Haveno's services should be deployed to a VPS for reliable uptime.

Seednodes can be installed as a system service.

Arbitrators can be started in a Screen session and then detached to run in the background.

Some good hints about how to secure a VPS are in [Monero's meta repository](https://github.com/monero-project/meta/blob/master/SERVER_SETUP_HARDENING.md).

TODO: gather and document scripts for VPS management