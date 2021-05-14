# Haveno Trade Protocol

Described here is the desired protocol for Haveno. Note that the discussion is still ongoing, so this protocol could be changed/updated in the future.

## Roles

- **Buyer** - person buying XMR
- **Seller** - person selling XMR
- **Maker** - person making offer
- **Taker** - person taking offer
- **Arbitrator** - person resolving possible disputes

For each trade, a trader is a buyer or seller and a maker or taker.

## Protocol

Haveno will use 2 accounts for trading: a "main account" to cover deposits to multisig and a "trade fee account" to cover trade fees.  To make or take an offer, outputs must be available in each account to cover deposit amount and the trade fee.

1. Maker deposits to main account and trade fee account and waits ~20 minutes for each output to become available.
2. Maker creates offer which pays the trade fee from the trade fee account and reserves needed outputs in the main account to deposit to multisig.  The offer is available to take immediately.
3. Taker deposits to main account and trade fee account and waits ~20 minutes for each output to become available.
4. Taker takes offer which pays trade fee from the trade fee account.
5. Maker, taker, and arbitrator create 2/3 multisig.
6. Both traders fund multisig.  Seller sends trade amount + security deposit whereas buyer only sends security deposit.
7. When both multisig deposits are available, buyer pays seller (e.g. sends ETH) outside of Haveno.
8. When payment is received, both parties sign to release funds from multisig to complete the trade, or one opens a dispute with the arbitrator to resolve

## What is needed (help wanted)

In order to accomplish the above protocol, functionality needs to be added to monero-wallet-rpc:

- [Ability to freeze and thaw outputs](https://github.com/monero-project/monero/issues/7720)
- [Ability to get key images or output indices from non-relayed transactions](https://github.com/monero-project/monero/issues/7721)