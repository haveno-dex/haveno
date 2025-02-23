#!/bin/bash
## ./haveno-on-qubes/scripts/0.3-dom0.sh

## Remove Haveno GuestVMs
qvm-shutdown --force haveno haveno-template sys-haveno ; qvm-remove haveno haveno-template sys-haveno

