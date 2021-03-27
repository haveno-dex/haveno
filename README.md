# Haveno

## What is Haveno?

Haveno is a private and decentralized way to exchange Monero for national currencies or other cryptocurrencies. Haveno uses peer-to-peer networking and multi-signature escrow to facilitate trading without a trusted third party custodian. Disputes can be resolved using non-custodial arbitration. Everything is built around Monero and Tor.

Haveno is the Esperanto word for "Harbor". The project is stewarded by a core Team, currently formed by 2 people: ErCiccione and Woodser.

## Why a new platform?

Haveno is a fork of Bisq, the Bitcoin based decentralized exchange. We believe Bisq is not enough for Monero users, which badly need a private way to exchange Monero for other (crypto)currencies.

Haveno is built on Monero, which means all transactions between users are obfuscated by default. Bisq's system is based on Bitcoin and inherits all its design vulnerabilities, for example:

- All Bisq's in-platform transactions are based on Bitcoin, which make them slow and fully traceable.
- Bisq transactions are unique and easily visible on the blockchain. This means it's trivial to check which Bitcoin transactions are the result of a trade on Bisq.

Even if XMR transactions compose the vast majority of Bisq's activity, Bisq's team haven't displayed much interest in improving their Monero support. The important privacy issues mentioned above will be solved by simply having Monero as a base currency instead of Bitcoin.

We acknowledge and thank Bisq for their efforts, but we think the Monero community needs a native, private way to exchange XMR for other currencies without passing through Bitcoin first and Haveno is here to fill that gap! We commit to contribute back to Bisq when possible.

## Status of the project

At the moment Haveno is only a Proof of Concept. It's already possible to initiate a crypto <-> XMR trade, but the platform still needs a lot of work before being available for public use.

There is a lot in progress and a lot to do. To make contributions easier, we use some of github's tools, like labels and projects. We set up a [labelling system](https://github.com/haveno-dex/haveno/wiki/Labelling-system) which should make easier for people to contribute. Problems and requests about the Haveno platform are tracked on this repository. For general discussions and proposals that affect the entire Haveno ecosystem, please open an issue in the [haveno-meta repository](https://github.com/haveno-dex/haveno-meta).

These are the main priorities for the near future:

- We need a logo. Right now it's a random stock image ([#1](https://github.com/haveno-dex/haveno-meta/issues/1))
- The User Interface is basically still Bisq. Needs to be completely reworked and adapted for Monero as base currency (https://github.com/haveno-dex/haveno/projects/2)
- Cleanup the repository from Bisq-specific content (https://github.com/haveno-dex/haveno/projects/1)

## Keep in touch and help out!

Haveno is a community-driven project. For it to be succesful it's fundamental to have the support and help of the Monero community. We have our own Matrix server. Registrations are not open at the moment, but the rooms are public and can be joined from any matrix client (like Element). We look forward to hearing from you!

- General discussions: **Haveno** (`#haveno:haveno.network`) relayed on Freenode (`#haveno`)
- Development discussions: **Haveno Development** (`#haveno-dev:haveno.network`) relayed on Freenode (`#haveno-dev`)

Temporary email: havenodex@protonmail.com

## FAQ

We are building up a wiki for this repository. At the moment there is only one entry:

- [Labelling system](https://github.com/haveno-dex/haveno/wiki/Labelling-system)

## Running a local Haveno test network

1. Download [Monero CLI](https://www.getmonero.org/downloads/) for your system and sync Monero stagenet: `./monerod --stagenet --rpc-login superuser:abctesting123`, or alternatively, [set up a local Monero stagenet network](#running-a-local-monero-stagenet-network)
3. Download and install [Bitcoin-Qt](https://bitcoin.org/en/download)
4. Run Bitcoin-Qt in regtest mode, e.g.: `/Applications/Bitcoin-Qt.app/Contents/MacOS/Bitcoin-Qt -regtest -peerbloomfilters=1`
5. In Bitcoin-Qt console, mine BTC regtest blocks: `generatetoaddress 101 bcrt1q6j90vywv8x7eyevcnn2tn2wrlg3vsjlsvt46qz`
6. Install [git lfs](https://git-lfs.github.com) for your system<br>
 Ubuntu: `sudo apt install git-lfs`
7. `git clone https://github.com/Haveno-Dex/haveno`
8. Copy monero-wallet-rpc from step 1 to the haveno project root
9. Apply permission to run monero-wallet-rpc, e.g. `chmod 777 monero-wallet-rpc`
10. Optionally modify [WalletConfig.java](core/src/main/java/bisq/core/btc/setup/WalletConfig.java) with custom settings
11. `cd haveno`
12. `./gradlew build`
13. Start seed node, arbitrator, Alice, and Bob:
    1. `./bisq-seednode --baseCurrencyNetwork=BTC_REGTEST --useLocalhostForP2P=true --useDevPrivilegeKeys=true --nodePort=2002 --appName=bisq-BTC_REGTEST_Seed_2002 --daoActivated=false`
    2. `./bisq-desktop --baseCurrencyNetwork=BTC_REGTEST --useLocalhostForP2P=true --useDevPrivilegeKeys=true --nodePort=4444 --appName=bisq-BTC_REGTEST_arbitrator --daoActivated=false`
    3. `./bisq-desktop --baseCurrencyNetwork=BTC_REGTEST --useLocalhostForP2P=true --useDevPrivilegeKeys=true --nodePort=5555 --appName=bisq-BTC_REGTEST_Alice --daoActivated=false`
    4. `./bisq-desktop --baseCurrencyNetwork=BTC_REGTEST --useLocalhostForP2P=true --useDevPrivilegeKeys=true --nodePort=6666 --appName=bisq-BTC_REGTEST_Bob  --daoActivated=false`
14. Arbitrator window > Account > cmd+n to register a new arbitrator
15. Arbitrator window > Account > cmd+d to register a new mediator
16. Deposit stagenet XMR to Alice and Bob's Haveno wallets (wallet address printed to terminal)
17. When deposited XMR is available, proceed to post offers, etc

### Running a local Monero stagenet network

1. Build [monero-project](https://github.com/monero-project/monero) with the following modification to the bottom of hardforks.cpp:
    ```c++
    const hardfork_t stagenet_hard_forks[] = {
      // version 1 from the start of the blockchain
      { 1, 1, 0, 1341378000 },
      
      // versions 2-7 in rapid succession from March 13th, 2018
      { 2, 10, 0, 1521000000 },
      { 3, 20, 0, 1521120000 },
      { 4, 30, 0, 1521240000 },
      { 5, 40, 0, 1521360000 },
      { 6, 50, 0, 1521480000 },
      { 7, 60, 0, 1521600000 },
      { 8, 70, 0, 1537821770 },
      { 9, 80, 0, 1537821771 },
      { 10, 90, 0, 1550153694 },
      { 11, 100, 0, 1550225678 },
      { 12, 110, 0, 1571419280 },
      { 13, 120, 0, 1598180817 },
      { 14, 130, 0, 1598180818 }
    };
    ```
2. Using the executables built in step 1:
    * `./monerod --stagenet --no-igd --hide-my-port --data-dir node1 --p2p-bind-ip 127.0.0.1 --p2p-bind-port 48080 --rpc-bind-port 48081 --zmq-rpc-bind-port 48082 --add-exclusive-node 127.0.0.1:38080 --rpc-login superuser:abctesting123 --rpc-access-control-origins http://localhost:8080`
    * `./monerod --stagenet --no-igd --hide-my-port --data-dir node2 --p2p-bind-ip 127.0.0.1 --rpc-bind-ip 0.0.0.0 --confirm-external-bind --add-exclusive-node 127.0.0.1:48080 --rpc-login superuser:abctesting123 --rpc-access-control-origins http://localhost:8080`
4. Mine the first 130 blocks to a random address before using so wallets only use the latest output type.  For example, in a daemon: `start_mining 56k9Yra1pxwcTYzqKcnLip8mymSQdEfA6V7476W9XhSiHPp1hAboo1F6na7kxTxwvXU6JjDQtu8VJdGj9FEcjkxGJfsyyah 1`

## Support

To bring Haveno to life, we need resources. If you have the possibility, please consider donating to the project. At this stage, donations are fundamental:

`42sjokkT9FmiWPqVzrWPFE5NCJXwt96bkBozHf4vgLR9hXyJDqKHEHKVscAARuD7in5wV1meEcSTJTanCTDzidTe2cFXS1F`

![Qr code](https://raw.githubusercontent.com/haveno-dex/haveno/master/media/qrhaveno.png)

If you are using a wallet that supports Openalias (like the 'official' CLI and GUI wallets), you can simply put `donations@haveno.network` as the "receiver" address.