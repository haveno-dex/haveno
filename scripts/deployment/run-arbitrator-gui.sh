#!/bin/bash
#
# Start arbitrator GUI on Monero's stagenet (Haveno testnet)

runArbitrator() {
    ./haveno-desktop --baseCurrencyNetwork=XMR_STAGENET \
    --useLocalhostForP2P=false \
    --useDevPrivilegeKeys=false \
    --nodePort=7777 \
    --appName=haveno-XMR_STAGENET_arbitrator \
    --xmrNode=http://127.0.0.1:38088 \
    --xmrNodeUsername=admin \
    --xmrNodePassword=password
}

cd /home/haveno/haveno && \
runArbitrator