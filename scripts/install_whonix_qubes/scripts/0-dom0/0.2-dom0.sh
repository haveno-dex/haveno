#!/bin/bash
## ./haveno-on-qubes/scripts/0.2-dom0.sh

## Create Haveno AppVM:
qvm-create --template haveno-template --class AppVM --label=orange --property memory=2048 --property maxmem=4096 --property netvm=sys-haveno haveno
printf 'haveno-Haveno.desktop' | qvm-appmenus --set-whitelist - haveno

