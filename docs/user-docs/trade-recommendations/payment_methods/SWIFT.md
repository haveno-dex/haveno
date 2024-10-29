# SWIFT
 

SWIFT (international wire transfer) is a network that allows financial institutions worldwide to send and receive payments internationally in multiple currencies.

Sender and receiver typically incur fees and payment times can also be slow (up to 7 days), but payments are secure and almost completely irreversible.

See this table for up-to-date trade limits and signing requirements.

**Contents:**
- Background
- Setting up the payment account
- Notes on buying XMR
- Send payment in correct currency
- Put an appropriate reason for payment
- Use the correct fee option
- Notes on selling XMR
- Understanding SWIFT fees

#### Background

Almost all banks have access to the SWIFT network. Some money transfer services also give users access to the SWIFT Network.

In order to set up a SWIFT payment account in Haveno, you will need:

- BIC/SWIFT code
- IBAN code

Also ensure that you are aware of:

- bank fees for sending and receiving SWIFT payments
- which currencies you can accept
- what the exchange rate would be in case conversion is necessary

Policies vary by bank, and sometimes conversion fees are charged unexpectedly, so make sure you are aware of your bank's policies beforehand.

#### Setting up the payment account

When creating a new payment account for SWIFT, keep the following in mind:

- Traders using SWIFT on Haveno could be anywhere in the world, so please fill all fields completely and accurately
- If you are not comfortable sharing your own address, provide the address of your bank

**Note:** By default, Haveno sets up your SWIFT account to make and take offers from any country and any currency. This means SWIFT accounts on Haveno are able to trade on ALL fiat currency markets. The currency for a particular trade will always be specified by the maker in their offer.

**Note:** if you transact in a currency that is not native to your bank account (as either maker OR taker), your bank may make you pay currency exchange fees. It is your responsibility to cover these fees. Please make 100% sure you're aware of what to expect before making or taking an offer.

#### Notes on buying XMR

You can buy XMR with SWIFT on Haveno in 2 waysː

- Make an offer to buy XMR on a currency market of your choosing
- Take an offer to buy XMR on the currency market specified in the offer

#### Send payment in correct currency

The Monero buyer must send payment in the currency of the market on which the offer was made. For example, if you make an offer to buy XMR using SWIFT on the XMR/USD market you should send USD (or ensure your bank converts your local currency into USD). Alternatively, if you take an offer to buy XMR using SWIFT on the XMR/EUR market you must send EUR.

Failure to send payment in the correct currency will result in a penalty for the XMR buyer of either (1) 5% of the trade amount or (2) the amount the receiver is missing in the specified currency as a result of the currency exchange (whichever is greater).

#### Put an appropriate reason for payment

In general, the reason for payment field should be left blank.

Some users have reported instances in which an intermediary bank converts a payment's currency to the their own currency before converting back to the destination bank's local currency, and taking fees for doing this.

If you don't want this to happen, consider making the SWIFT sender put "DO NOT CONVERT" in the comment field for the wire transfer (not the comment field of the Haveno payment account). This will only work if the recipient is able to handle multi-currency incoming SWIFT payments.

#### Use the correct fee option

SWIFT transfers for Haveno trades should use the SHA (SHAred) fee option. More on this below.

#### Notes on selling XMR

You can sell XMR with SWIFT on Haveno in 2 waysː
- Make an offer to sell XMR on a currency market of your choosing
- Take an offer to sell XMR on the currency market specified in the offer

The XMR seller should receive the funds in the currency specified in the offer. For example, if you make an offer to sell XMR using SWIFT on the XMR/USD market, you will receive USD. Alternatively if you take an offer to sell XMR using SWIFT on the XMR/EUR market, you will receive EUR.

Since SWIFT senders must use the SHA (SHAred) fee option for Haveno trades, sellers may incur fees on their end (see more below). Make sure you are aware of these fees before selling XMR using SWIFT.

#### Understanding SWIFT fees

When sending a SWIFT transfer the sender has three fee options to choose from:

- BEN (BENeficiary) – payee (recipient of the payment) incurs all payment transaction fees. Typically, the recipient receives the payment minus the transaction fees. The payer (sender of the payment) does not pay any transaction fees.
- OUR – payer (sender of the payment) incurs all payment transaction fees. Normally you are billed separately for the payment transfer. The payee (recipient of the payment) does not pay any transaction fees. The beneficiary receives the full payment amount.
- SHA (SHAred) – payer (sender of the payment) pays all fees charged by the sending bank, which are billed separately. The payee (recipient of the payment) pays all fees charged by their receiving bank. The recipient receives the payment minus any applicable fees.

Haveno requires buyers to use the SHA (SHAred) fee option for SWIFT transfers. If a XMR buyer sends a payment using the BEN (BENeficiary) fee option, they will likely incur a penalty to compensate the XMR seller.

Please make sure you are aware of your bank's fee schedule for the SHA (SHAred) fee option. Normally it will be $5-25 USD equivalent, but every bank is different. 
