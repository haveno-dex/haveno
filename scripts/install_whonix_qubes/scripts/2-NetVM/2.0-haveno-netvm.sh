#!/bin/zsh
## ./haveno-on-qubes/scripts/2.0-haveno-netvm_taker.sh

## Function to print messages in blue:
echo_blue() {
  echo -e "\033[1;34m$1\033[0m"
}


# Function to print error messages in red:
echo_red() {
  echo -e "\033[0;31m$1\033[0m"
}


## onion-grater
# Add onion-grater Profile
echo_blue "\nAdding onion-grater Profile ..."
onion-grater-add 40_haveno


# Restart onion-grater
echo_blue "\nRestarting onion-grater Service ..."
systemctl restart onion-grater.service
echo_blue "Haveno NetVM configuration complete."
printf "%s \n" "Press [ENTER] to complete ..."
read ans
#exit
poweroff

