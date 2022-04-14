# Running a local Haveno test network

These are the steps needed to set up a local Haveno test network. You'll have the possibility to either connect to our shared Monero stagenet node or to create your own private stagenet.

We also provide instructions and dockerfiles for [running a test network using Docker and docker-compose](https://github.com/haveno-dex/haveno/tree/docker/docker)

## 1. Install dependencies

On Ubuntu: `sudo apt install make wget git git-lfs openjdk-11-jdk`. The Bitcoin and Monero binaries will be downloaded and verified automatically in the next step.

## 2. Build Haveno

1. Download this repository: `git clone https://github.com/haveno-dex/haveno.git`
2. Navigate to the root of the repository (`cd haveno`) and build the repository: run `make` in the terminal and wait until the process is completed (this will also download and verify the Monero and Bitcoin binaries).

## 3. Connect to Monero stagenet

The quickest way to get a Monero stagenet running is by connecting to our own shared instance (3a) so you won't have to do anything except mine coins for testing (step 5). If you prefer to have total control over the testing instance, you might prefer running your own private Monero stagenet (3b).

### 3a. Join our shared stagenet

Run `make monero-shared`

### 3b. Run your own private stagenet

1. In a new terminal window run `make monero-private1`;
2. In a new terminal window run `make monero-private2`;
3. Now mine the first 130 blocks to a random address before using, so wallets only use the latest output type. Run in one of the terminal windows opened above:

`start_mining 56k9Yra1pxwcTYzqKcnLip8mymSQdEfA6V7476W9XhSiHPp1hAboo1F6na7kxTxwvXU6JjDQtu8VJdGj9FEcjkxGJfsyyah 1`

## 4. Deploy

If you are a *screen* user, simply run `make deploy`. This command will open all needed Haveno instances (seednode, Alice, Bob, arbitrator) using *screen*. If this is the first time launching the arbitrator desktop application, register the arbitrator and mediator as explained in steps `5.3.1` and `5.3.2`.

If you don't use *screen*, open 4 terminal windows and run in each one of them:

  1. `make seednode`
  2. `make arbitrator-desktop`  
  3. If this is the first time launching the arbitrator desktop application, register the arbitrator after the interface opens:
      1. Go to the *Account* tab and press `cmd+r`. Confirm the registration of the arbitrator.
  4. `make alice-desktop` or if you want to run Alice as a daemon: `make alice-daemon`
  5. `make bob-desktop` or if you want to run Bob as a daemon: `make bob-daemon`

## 5. Fund your wallets

When running Alice and Bob, you'll see a Monero address prompted in the terminal. Send stagenet XMR to the addresses of both Alice and Bob to be able to initiate a trade.

You can fund the two wallets by mining some stagenet XMR coins to those addresses. To do so, open a terminal where you ran monerod and run: `start_mining ADDRESS 1`.

monerod will start mining stagenet coins on your device using one thread. Replace `ADDRESS` with the address of Alice first, and then Bob's. Run `stop_mining` to stop mining.

## 6. Start testing

You are all set. Now that everything is running and your wallets are funded, you can create test trades between Alice and Bob. Remember to mine a few blocks after opening and accepting the test trade so the transaction will be confirmed.
