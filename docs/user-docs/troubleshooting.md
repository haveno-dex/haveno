# Troubleshooting

#### "My wallet takes too much time to sync" "My wallet shows an incorrect balance"
If you want to fasten large gaps of wallet syncing or correcting your wallet balance:
- Go in {haveno-path}/xmr_mainnet/wallet/ & search *haveno_XMR*
- Open it with monero-cli sync the wallet & refresh the balance. If the issue persists, rescan the wallet history.

#### "I can't access to Tor" "Tor isn't launching"
- If Tor started but you can't connect to the network, you might need a bridge to connect to the network.
- Tor is slow to start so you might wait some time before haveno launches.
- If you use TailsOS or an existing/external Tor process, follow security.md
