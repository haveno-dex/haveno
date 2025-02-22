#!/bin/bash
## ./haveno-on-qubes/scripts/0.0-dom0.sh

## Create & Start Haveno TemplateVM:
qvm-clone whonix-workstation-17 haveno-template && qvm-start haveno-template

