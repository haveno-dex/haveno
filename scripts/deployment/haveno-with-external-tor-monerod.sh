#!/bin/sh
HAVENOHOME=$(find $HOME/.local/share -type d -name Haveno*)
TORHOME="$HAVENOHOME/xmr_mainnet/tor"

# Running Tor for monerod
tor --RunAsDaemon 1 --DisableNetwork 0 --PidFile $HAVENOHOME/tor-monerod-pid --SOCKSPort 9056
$HAVENOHOME/monerod --rpc-bind-port 18081 --tx-proxy tor,127.0.0.1:9056 --detach --pidfile $HAVENOHOME/monerod-pid

# Running Tor for Haveno and binding Haveno to external Tor + monerod
tor -f $TORHOME/torrc --ControlPortWriteToFile $TORHOME/.tor/control.port --DisableNetwork 0
/opt/haveno/bin/Haveno --torControlUseSafeCookieAuth --torControlCookieFile=$TORHOME/.tor/control_auth_cookie --torControlPort=$(cat $TORHOME/.tor/control.port | sed 's/.*:\([0-9]\+\)/\1/') --xmrNode=127.0.0.1:18081
kill $(cat $TORHOME/pid) && kill $(cat $HAVENOHOME/tor-monerod-pid) && kill $(cat $HAVENOHOME/monerod-pid)
