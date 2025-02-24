#!/bin/bash
## ./haveno-on-qubes/scripts/0.1-dom0.sh

## Create Haveno NetVM:
qvm-create --template whonix-gateway-17 --class AppVM --label=orange --property memory=512 --property maxmem=512 --property netvm=sys-firewall sys-haveno && qvm-prefs --set sys-haveno provides_network True

