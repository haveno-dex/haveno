## Overview

Haveno is a decentralized network where people meet to exchange XMR for fiat or other cryptocurrencies. There are no central entities involved and trades happen directly between traders, which are both required to deposit and lock some XMR until the trade is completed. In case of disagreement between traders, both of them can request the involvement of an arbitrator, which will resolve the dispute.

This document provides a simplified overview of Haveno's trade protocol, but a PDF with the technical details is available: [trade-protocol.pdf](trade-protocol.pdf).

## Protocol

### Roles

- **Maker** - person making offer
- **Taker** - person taking offer
- **Arbitrator** - entity resolving possible disputes

### Overview of a trade

We assume the maker is selling XMR and the taker is buying them in exchange for fiat:

1. (optional) Maker **deposits funds** to Haveno wallet and waits for them to be unlocked (~20 minutes).
2. Maker **creates an offer** which reserves funds for the security deposit + amount to be sent to the taker. Also creates a penalty transaction which penalizes the maker if they break protocol.
3. Taker **accepts offer** which reserves funds for the security deposit + amount to be sent to the maker. As for the maker in the step above, a penalty transaction is created.
4. A **2/3 multisignature wallet is created** between the maker, taker and arbitrator. The arbitrator holds the third key, so that they can be summoned in case of disputes. If there is no dispute, the traders will complete the transaction without involving an arbitrator.
5. Both users have their deposit locked in multisig. Now they wait until the funds are spendable (~20 minutes). In the meantime, the XMR buyer can send payment outside Haveno (e.g. send bank transfer to the other trader).
6. When the taker has received the agreed amount with the agreed payment method, they **signal to the maker** that they have received the payment.
7. The maker checks they have received the agreed amount and if everything is ok, they **confirm the completed payment**.
8. When the XMR seller confirms they received the payment outside Haveno, the agreed amount in XMR is sent to the buyer, while the security deposits of both traders are returned to them. **Trade completed**.

This protocol ensures trades on Haveno are non-custodial (Haveno never has access to your funds), peer-to-peer (there is no central entity, people trade among themselves) and safe (thanks to the security deposit and opt-in arbitration).
