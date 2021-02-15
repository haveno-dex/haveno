# Haveno

## What is Haveno?

Haveno is a private and decentralized way to exchange Monero for national currencies or other cryptocurrencies.  Haveno uses peer-to-peer networking and multi-signature escrow to facilitate trading without a trusted third party custodian.  Disputes can be resolved using non-custodial arbitration.

## Running a local test network

1. Download [Monero CLI](https://www.getmonero.org/downloads/) for your system
2. Sync stagenet: `./monerod --stagenet`, or alternatively, start a local stagenet network:
    * `./monerod --stagenet --no-igd --hide-my-port --data-dir node1 --p2p-bind-ip 127.0.0.1 --p2p-bind-port 48080 --rpc-bind-port 48081 --zmq-rpc-bind-port 48082 --add-exclusive-node 127.0.0.1:38080 --rpc-login superuser:abctesting123 --rpc-access-control-origins http://localhost:8080`<br>
    * `./monerod --stagenet --no-igd --hide-my-port --data-dir node2 --p2p-bind-ip 127.0.0.1 --rpc-bind-ip 0.0.0.0 --confirm-external-bind --add-exclusive-node 127.0.0.1:48080 --rpc-login superuser:abctesting123 --rpc-access-control-origins http://localhost:8080`
3. Download and install [Bitcoin-Qt](https://bitcoin.org/en/download)
4. Run Bitcoin-Qt in regtest mode, e.g.: `/Applications/Bitcoin-Qt.app/Contents/MacOS/Bitcoin-Qt -regtest -peerbloomfilters=1`
5. In Bitcoin-Qt console, mine BTC regtest blocks: `generatetoaddress 101 bcrt1q6j90vywv8x7eyevcnn2tn2wrlg3vsjlsvt46qz`
6. Install [git lfs](https://git-lfs.github.com) for your system<br>
 Ubuntu: `sudo apt install git-lfs`
7. `git clone https://github.com/Haveno-Dex/haveno`
8. Copy monero-wallet-rpc downloaded in step 1 to the haveno project root
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