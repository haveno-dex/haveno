#!/bin/bash
FOLDER_PATH="$HOME/.local/share/Haveno"

echo "This script will launch your Tor system binaries to use Haveno"

tor --RunAsDaemon 1 \
--AvoidDiskWrites 1 \
--PidFile "$FOLDER_PATH/xmr_mainnet/tor/pid" \
--DataDirectory "$FOLDER_PATH/xmr_mainnet/tor/" \
--GeoIPFile "$FOLDER_PATH/xmr_mainnet/tor/geoip" \
--GeoIPv6File "$FOLDER_PATH/xmr_mainnet/tor/geoip6" \
--CookieAuthFile "$FOLDER_PATH/xmr_mainnet/tor/.tor/control_auth_cookie" \
--ControlPort 9051 \
--CookieAuthentication 1 \
--SOCKSPort 9050\
TORPID=$(cat "$FOLDER_PATH/xmr_mainnet/tor/pid")

/opt/haveno/bin/Haveno --logLevel=ERROR \
--torControlUseSafeCookieAuth=1 \
--torStreamIsolation=1 \
--useTorForXmr=ON \
--torControlCookieFile="$FOLDER_PATH/xmr_mainnet/tor/.tor/control_auth_cookie" \
--torControlPort=9051 \
--socks5ProxyXmrAddress=127.0.0.1:9050 \

kill $TORPID
