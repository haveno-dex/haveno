# Running a local Haveno test network

These are the steps needed to set up Haveno test instances.

## 1. Install dependencies

On Ubuntu: `sudo apt install make wget git git-lfs openjdk-11-jdk`. The Bitcoin and Monero binaries will be downloaded and verified automatically in the next step.

## 2. Build Haveno

1. Download this repository: `git clone https://github.com/haveno-dex/haveno.git`
2. Navigate to the root of the repository (`cd haveno`) and build the repository: run `make` in the terminal and wait until the process is completed (this will also download and verify the Monero and Bitcoin binaries).

## 3. Run a local XMR testnet

1. In a new terminal window run `make monerod-local1`
1. In a new terminal window run `make monerod-local2`
3. Now mine the first 130 blocks to a random address before using, so wallets only use the latest output type. Run in one of the terminal windows opened above:

`start_mining 9tsUiG9bwcU7oTbAdBwBk2PzxFtysge5qcEsHEpetmEKgerHQa1fDqH7a4FiquZmms7yM22jdifVAD7jAb2e63GSJMuhY75 1`

## 4. Deploy

If you are a *screen* user, simply run `make deploy`. This command will open all needed Haveno instances (seednode, Alice, Bob, arbitrator) using *screen*. If this is the first time launching the arbitrator desktop application, register the arbitrator as explained in step 3 below.

If you don't use *screen*, open 4 terminal windows and run in each one of them:

  1. `make seednode-local`
  2. `make arbitrator-desktop-local`  
  3. If this is the first time launching the arbitrator desktop application, register the arbitrator after the interface opens. Go to the *Account* tab and press `cmd+r`. Confirm the registration of the arbitrator.
  4. `make alice-desktop-local` or if you want to run Alice as a daemon: `make alice-daemon-local`
  5. `make bob-desktop-local` or if you want to run Bob as a daemon: `make bob-daemon-local`

## 5. Fund your wallets

When running Alice and Bob, you'll see a Monero address prompted in the terminal. Send local testnet XMR to the addresses of both Alice and Bob to be able to initiate a trade.

You can fund the two wallets by mining some testnet XMR coins to those addresses. To do so, open a terminal where you ran monerod and run: `start_mining ADDRESS 1`.

monerod will start mining local testnet coins on your device using one thread. Replace `ADDRESS` with the address of Alice first, and then Bob's. Run `stop_mining` to stop mining.

## 6. Start testing

You are all set. Now that everything is running and your wallets are funded, you can create test trades between Alice and Bob. Remember to mine a few blocks after opening and accepting the test trade so the transaction will be confirmed.
