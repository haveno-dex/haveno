# Running a local Haveno test network

These are the steps needed to set up a local Haveno test network.

## 1. Download dependencies

1. Install git, git-lfs, and Java. On Ubuntu: `sudo apt install git git-lfs openjdk-11-jdk`
2. Download the Monero binaries. To make things easier, we provide two custom Monero binaries: `monerod` and `monero-wallet-rpc`. You can directly [download the software (Linux and Mac)](https://github.com/haveno-dex/monero/releases/tag/testing) or build the binaries yourself by cloning and building the `release-v0.17` branch of this repository: https://github.com/haveno-dex/monero
3. Download and install [Bitcoin-Qt](https://bitcoin.org/en/download).

## 2. Build Haveno

1. Download this repository. Run `git clone https://github.com/haveno-dex/haveno.git`
2. Navigate inside the repository (`cd haveno`) and start the build: `./gradlew build`. It will take some minutes. If you know what you are doing you can modify [WalletConfig.java](core/src/main/java/bisq/core/btc/setup/WalletConfig.java) with custom settings before building.

## 3. Run Monero

1. Unpack the downloaded or built archives from step 1.2 and copy `monero-wallet-rpc` to the root of this repository. Make sure it has the right permissions (if you are in doubt, just run `chmod 777 monero-wallet-rpc`).
2. Now join our shared stagenet (skip this command and see the next section if you prefer to test Haveno using a local private stagenet). Navigate into the folder containing `monerod` and run:

```
./monerod --stagenet --no-igd --hide-my-port --data-dir stagenet --add-exclusive-node 136.244.105.131:38080 --rpc-login superuser:abctesting123 --rpc-access-control-origins http://localhost:8080 --fixed-difficulty 10
```

Your daemon will connect to our private Monero stagenet and will be ready to be used by Haveno.

### Run private stagenet

If you prefer you can use a local private stagenet for testing instead of our shared one. Run in a terminal window:

```
./monerod --stagenet --no-igd --hide-my-port --data-dir node1 --p2p-bind-ip 127.0.0.1 --p2p-bind-port 48080 --rpc-bind-port 48081 --zmq-rpc-bind-port 48082 --add-exclusive-node 127.0.0.1:38080 --rpc-login superuser:abctesting123 --rpc-access-control-origins http://localhost:8080
```

and in a second window:

```
./monerod --stagenet --no-igd --hide-my-port --data-dir node2 --p2p-bind-ip 127.0.0.1 --rpc-bind-ip 0.0.0.0 --confirm-external-bind --add-exclusive-node 127.0.0.1:48080 --rpc-login superuser:abctesting123 --rpc-access-control-origins http://localhost:8080
```

Now mine the first 130 blocks to a random address before using so wallets only use the latest output type. For example, in a daemon: 

```
start_mining 56k9Yra1pxwcTYzqKcnLip8mymSQdEfA6V7476W9XhSiHPp1hAboo1F6na7kxTxwvXU6JjDQtu8VJdGj9FEcjkxGJfsyyah 1
```

## 4. Run Bitcoin

Haveno still relies on Bitcoin for its infrastructure. We will remove it soon. In the meantime, to run Haveno locally, you'll need to download Bitcoin QT and run it in regtest mode. Which means you won't need to download the Bitcoin blockchain.

1. Download and install [Bitcoin-Qt](https://bitcoin.org/en/download)
2. Run Bitcoin-Qt in regtest mode, e.g.: `./Bitcoin-Qt -regtest -peerbloomfilters=1`
3. Click on the *Console* tab and mine regtest blocks all at once. Run: `generatetoaddress 101 bcrt1q6j90vywv8x7eyevcnn2tn2wrlg3vsjlsvt46qz`

## 5. Run Haveno

Now that you have both Bitcoin and Monero running, it's time to spin up your testing instances. You'll need to open 4 terminal windows: 1 for the seed node, 1 for the arbitrator, 1 for Alice (trader 1) and 1 for Bob (trader 2).

It could be useful to rename the terminal to have a better overview of what terminal belongs to whom (on Ubuntu: echo -en "\033]0;NEW_TITLE\a").

1. Run the seed node in one terminal:

```
./haveno-seednode --baseCurrencyNetwork=XMR_STAGENET --useLocalhostForP2P=true --useDevPrivilegeKeys=true --nodePort=2002 --appName=haveno-XMR_STAGENET_Seed_2002 --daoActivated=false
```

2. Run the Arbitrator instance:

```
./haveno-desktop --baseCurrencyNetwork=XMR_STAGENET --useLocalhostForP2P=true --useDevPrivilegeKeys=true --nodePort=4444 --appName=haveno-XMR_STAGENET_arbitrator --daoActivated=false --apiPassword=apitest --apiPort=9998
```

The (temporary) Haveno user interface will open. Once Haveno launches:

3. Click on the *Account* tab and press `cmd+n`. Confirm the registration of the arbitrator.
4. From the *Account* tab press `cmd+d` and confirm the registration of the mediator.

Make sure to register arbitrator and mediator. Without them it's not possible to initiate a trade on the PoC.

5. Run Alice:

```
./haveno-desktop --baseCurrencyNetwork=XMR_STAGENET --useLocalhostForP2P=true --useDevPrivilegeKeys=true --nodePort=5555 --appName=haveno-XMR_STAGENET_Alice --daoActivated=false --apiPassword=apitest --apiPort=9999
```

6. Run Bob:

```
./haveno-desktop --baseCurrencyNetwork=XMR_STAGENET --useLocalhostForP2P=true --useDevPrivilegeKeys=true --nodePort=6666 --appName=haveno-XMR_STAGENET_Bob  --daoActivated=false --apiPassword=apitest --apiPort=10000
```

## 5. Get yourself some stagenet coins

Alice and Bob's receiving address is printed to their terminal on startup.

Mining is the recommended method to receive new stagenet coins. The difficulty is locked to 10, so mining would be very fast. Go to your monerod instance and run `start_mining ADDRESS 1`. Monerod will start mining stagenet coins on your device using one thread. Replace `ADDRESS` with the address of Alice first, and then Bob's.

Alternatively, you can contact ErCiccione on Matrix (`@ErCiccione:haveno.network`) and give him a stagenet address.

Remember to send funds to both Alice and Bob.

## 6. Start trading

Now you can open and take offers using the Haveno PoC (which is still mostly Bisq in the user interface. The redesign is in progress.)