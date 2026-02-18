#!/bin/bash
HAVENO_BIN=$(find /opt/haveno/bin/ -type f -executable -name "Haveno" 2>/dev/null)
HAVENO_WALLET=$(find ~/.local/share/Haveno -type d -name "wallet" 2>/dev/null)
echo "creating symlink to" $HAVENO_BIN "in" /bin
echo "creating symlink to" $HAVENO_WALLET "in" ~/Monero/wallets/Haveno_wallets/
ln -s $HAVENO_BIN /bin/Haveno
ln -s $HAVENO_WALLET ~/Monero/wallets/Haveno_wallets
