# Haveno Trade Protocol

Described here is the desired protocol for Haveno. Note that the discussion is still ongoing, so this protocol could be changed/updated in the future.

## Roles

- **Buyer** - person buying XMR
- **Seller** - person selling XMR
- **Maker** - person making offer
- **Taker** - person taking offer
- **Arbitrator** - entity resolving possible disputes

For each trade, a trader is a buyer or seller and a maker or taker.

## Protocol

1. Maker deposits funds to Haveno wallet and waits ~20 minutes. (optional)
2. Maker initiates offer. If maker does not have available or pending outputs to cover trade funds, prompt to first deposit to Haveno wallet and wait ~20 minutes.
3. Maker creates but does not relay "reserve transaction" which pays trade fee and returns multisig deposit amount to maker. Maker freezes input funds and submits offer to arbitrator to sign.
4. Arbitrator verifies maker reserve transaction and signs offer. If maker breaks protocol, arbitrator can punish maker by relaying reserve transaction to pay trade fee (unless maker spends reserved inputs which incurs mining fee).
5. Offer is posted and available to be taken immediately.
6. Taker deposits funds to Haveno wallet and waits ~20 minutes. (optional)
7. Taker verifies offer's arbitrator signature and initiates taking offer. If taker does not have available or pending outputs to cover trade funds, prompt to first deposit to Haveno wallet and wait ~20 minutes.
8. Taker creates but does not relay "reserve transaction" which pays trade fee and returns multisig deposit amount to taker. Taker freezes input funds and submits request to take offer to arbitrator.
9. Arbitrator verifies taker reserve transaction. If taker breaks protocol, arbitrator can punish taker by relaying reserve transaction to pay trade fee (unless taker spends reserved inputs which incurs mining fee).
10. Maker, taker, and arbitrator create 2/3 multisig wallet.
11. Maker and taker create but do not relay "deposit transactions" to pay trade fees and send deposit amounts to multisig. Buyer deposits trade amount + security deposit whereas seller only deposits security deposit.
12. Maker and taker sign contract with trade terms and ids of deposit trasactions.
13. Maker and taker submit signed contract and deposit transactions to arbitrator.
14. Arbitrator verifies contract and deposit transactions then relays deposit transactions to commit funds to multisig.
15. Buyer pays seller (e.g. sends ETH) outside of Haveno after at least a few confirmations.
16. When the multisig deposits are available (after ~20 minutes) and payment is acknowledged, maker and taker sign to release funds from multisig to payout addresses, or one trader opens a dispute with the arbitrator.
17. Arbitrator resolves dispute if applicable.

Note: all steps involving the arbitrator are automatic except resolving disputes.

## Protocol Messaging

See [trade-protocol.pdf](trade-protocol.pdf) for documentation of network messages used by the trade protocol.