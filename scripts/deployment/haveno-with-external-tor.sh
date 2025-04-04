#!/bin/sh
TORHOME="$HOME/.local/share/Haveno/xmr_mainnet/tor"
tor -f $TORHOME/torrc --ControlPortWriteToFile $TORHOME/.tor/control.port --DisableNetwork 0
/opt/haveno/bin/Haveno --torControlUseSafeCookieAuth --torControlCookieFile=$TORHOME/.tor/control_auth_cookie --torControlPort=$(cat $TORHOME/.tor/control.port | sed 's/.*:\([0-9]\+\)/\1/')
kill $(cat $TORHOME/pid)
