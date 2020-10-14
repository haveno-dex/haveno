# Haveno

## What is Haveno?

Haveno is a private and decentralized way to exchange Monero for national currencies or other cryptocurrencies.  Haveno uses peer-to-peer networking and multi-signature escrow to facilitate trading without a trusted third party custodian.  Disputes can be resolved using human arbitration.

## Running a local test network

1. Download and install [Bitcoin-Qt](https://bitcoin.org/en/download)
2. Run Bitcoin-Qt in regtest mode, e.g.: `/Applications/Bitcoin-Qt.app/Contents/MacOS/Bitcoin-Qt -regtest -peerbloomfilters=1`
3. In Bitcoin-Qt console, mine BTC regtest blocks: `generatetoaddress 101 bcrt1q6j90vywv8x7eyevcnn2tn2wrlg3vsjlsvt46qz`
4. Build [monero-java shared libraries with JNI bindings](https://github.com/monero-ecosystem/monero-java#building-jni-shared-libraries-from-source) for your system
5. Copy the 2 built shared libraries in monero-java/build to the Haveno project root
7. Download and install [Monero CLI](https://www.getmonero.org/downloads/)
8. Sync stagenet: `./monerod --stagenet`
9. Install [git lfs](https://git-lfs.github.com) for your system
10. `git clone https://github.com/Haveno-Dex/Haveno`
11. `cd Haveno`
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
