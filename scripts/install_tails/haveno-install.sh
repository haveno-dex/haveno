#!/bin/bash

#############################################################################
# Written by BrandyJson, with heavy inspiration from bisq.wiki tails script #
#############################################################################
echo "Installing dpkg from persistent, (1.07-1, if this is out of date change the deb path in the script or manually install after running"
dpkg -i "/home/amnesia/Persistent/haveno_1.0.7-1_amd64.deb"
echo -e "Allowing amnesia to read tor control port cookie, only run this script when you actually want to use haveno\n\n!!! not secure !!!\n"
chmod o+r /var/run/tor/control.authcookie
echo "Updating apparmor-profile"
echo "---
- apparmor-profiles:
    - '/opt/haveno/bin/Haveno'
  users:
    - 'amnesia'
  commands:
    AUTHCHALLENGE:
      - 'SAFECOOKIE .*'
    SETEVENTS:
      - 'CIRC ORCONN INFO NOTICE WARN ERR HS_DESC HS_DESC_CONTENT'
    GETINFO:
      - pattern: 'status/bootstrap-phase'
        response:
          - pattern: '250-status/bootstrap-phase=*'
            replacement: '250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY="Done"'
      - 'net/listeners/socks'
    ADD_ONION:
      - pattern:     'NEW:(\S+) Port=9999,(\S+)'
        replacement: 'NEW:{} Port=9999,{client-address}:{}'
      - pattern:     '(\S+):(\S+) Port=9999,(\S+)'
        replacement: '{}:{} Port=9999,{client-address}:{}'
    DEL_ONION:
      - '.+'
    HSFETCH:
      - '.+'
  events:
    CIRC:
      suppress: true
    ORCONN:
      suppress: true
    INFO:
      suppress: true
    NOTICE:
      suppress: true
    WARN:
      suppress: true
    ERR:
      suppress: true
    HS_DESC:
      response:
        - pattern:     '650 HS_DESC CREATED (\S+) (\S+) (\S+) \S+ (.+)'
          replacement: '650 HS_DESC CREATED {} {} {} redacted {}'
        - pattern:     '650 HS_DESC UPLOAD (\S+) (\S+) .*'
          replacement: '650 HS_DESC UPLOAD {} {} redacted redacted'
        - pattern:     '650 HS_DESC UPLOADED (\S+) (\S+) .+'
          replacement: '650 HS_DESC UPLOADED {} {} redacted'
        - pattern:     '650 HS_DESC REQUESTED (\S+) NO_AUTH'
          replacement: '650 HS_DESC REQUESTED {} NO_AUTH'
        - pattern:     '650 HS_DESC REQUESTED (\S+) NO_AUTH \S+ \S+'
          replacement: '650 HS_DESC REQUESTED {} NO_AUTH redacted redacted'
        - pattern:     '650 HS_DESC RECEIVED (\S+) NO_AUTH \S+ \S+'
          replacement: '650 HS_DESC RECEIVED {} NO_AUTH redacted redacted'
        - pattern:     '.*'
          replacement: ''
    HS_DESC_CONTENT:
      suppress: true" > /etc/onion-grater.d/haveno.yml
echo "Adding rule to iptables to allow for monero-wallet-rpc to work"
iptables -I OUTPUT 2 -p tcp -d 127.0.0.1 -m tcp --dport 18081 -m owner --uid-owner 1855 -j ACCEPT
echo "Updating torsocks to allow for inbound connection"
sed -i 's/#AllowInbound/AllowInbound/g' /etc/tor/torsocks.conf 

echo "Restarting onion-grater service"

systemctl restart onion-grater.service

echo "alias haveno-tails='torsocks /opt/haveno/bin/Haveno --torControlPort 951 --torControlCookieFile=/var/run/tor/control.authcookie --torControlUseSafeCookieAuth --useTorForXmr=ON --userDataDir=/home/amnesia/Persistent/'" >> /home/amnesia/.bashrc
echo -e "Everything is set up just run\n\nsource ~/.bashrc\n\nThen you can start haveno using haveno-tails"