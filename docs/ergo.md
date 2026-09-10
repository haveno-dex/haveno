# Ergo (ERG) payments

ERG is a payment currency for exchanging Ergo against XMR. ERG is sent from an
external Ergo wallet. Haveno continues to use its existing Monero escrow and
arbitration protocol.

## Payment accounts

Create a cryptocurrency payment account, select **Ergo (ERG)**, and enter the
receiving address from your Ergo wallet. Saving the account also adds ERG to
the preferred currencies used by the offer book.

This integration supports ordinary mainnet **P2PK** wallet addresses. It does
not accept testnet addresses or P2S/P2SH script addresses. Those are other Ergo
address formats; the restriction is a payment-account policy. The form explains
the supported format when an unsupported address is entered.

## Prices and payment amounts

Haveno represents cryptocurrency prices and volumes with eight decimal places.
For ERG, its smallest trading increment is **0.00000001 ERG**, equivalent to
**10 nanoERG**. Ergo itself has nine decimal places. An entered price or volume
with a nonzero ninth decimal is rejected rather than silently rounded.

Calculated volumes follow Haveno's existing integer conversion to the trading
increment. Send the exact ERG amount displayed in the trade's payment details;
the amount displayed and copied uses all eight trading decimal places. Wallet
transaction fees are separate from the amount sent to the other trader.

Fixed-price offers work without an external ERG market quote. Market-based
offers require a recent external ERG quote from the configured Haveno price
provider. Missing or stale quotes do not produce a usable market-based price.
Adding the asset does not add an ERG quote to a network's price providers.

To sell ERG, use an offer to buy XMR with ERG. Follow Haveno's payment steps,
send ERG from your external wallet, and use the existing payment confirmation
and dispute process. There is no embedded Ergo wallet or ERG payment QR code.

Availability to traders depends on a Haveno network distributing the integration
and enabling the asset. It also requires counterparties offering XMR for ERG.

## Validation

The address validator checks raw Base58 encoding, the network/type prefix, the
four-byte Blake2b-256 checksum, the exact P2PK envelope length, and the compressed
secp256k1 public key. It rejects malformed input before a payment account can be
accepted.

Relevant existing test classes cover the asset registry and address validation,
account and offer serialization, payment-account matching, exact payment
formatting, and fixed or market-based prices:

```sh
./gradlew :core:test --tests haveno.core.payment.validation.CryptoAddressValidatorTest --tests haveno.core.locale.CurrencyUtilTest --tests haveno.core.payment.PaymentAccountsTest --tests haveno.core.monetary.PriceTest --tests haveno.core.offer.OfferTest :assets:test :assets:checkstyleMain :core:checkstyleTest
```

On Windows, use `gradlew.bat` with the same arguments.
