#!/bin/zsh
## ./haveno-on-qubes/scripts/2.1-haveno-netvm_maker.sh

if [[ $# -ne 1 ]] ; then
    printf "\nNo arguments provided!\n\nThis script requires an argument to be provided:\nIP Address of Haveno AppVM\n\nPlease review documentation and try again.\n\nExiting now ...\n"
    exit 1
fi


HAVENO_APPVM_IP=$1

## Function to print messages in blue:
echo_blue() {
  echo -e "\033[1;34m$1\033[0m"
}


# Function to print error messages in red:
echo_red() {
  echo -e "\033[0;31m$1\033[0m"
}


# Prepare Maker Hidden Service
echo_blue "\nConfiguring Hidden Service (Onion) ..."
printf "\n## Haveno-DEX\nConnectionPadding 1\nHiddenServiceDir /var/lib/tor/haveno-dex/\nHiddenServicePort 9999 $HAVENO_APPVM_IP:9999\n\n" >> /usr/local/etc/torrc.d/50_user.conf


## View & Verify Change
echo_blue "\nReview the following output and be certain in matches documentation!\n"
tail /usr/local/etc/torrc.d/50_user.conf
printf "%s \n" "Press [ENTER] to continue ..."
read ans


## Restart tor
echo_blue "\nRestarting tor Service ..."
systemctl restart tor@default.service


## Display onion address
sleep 3
printf "$(</var/lib/tor/haveno-dex/hostname)\n"
echo_blue "Use this address for <HAVENO_NETVM_ONION_ADDRESS>"
printf "%s \n" "Press [ENTER] after building AppVM ..."
read ans
echo_blue "Haveno NetVM configuration complete."
printf "%s \n" "Press [ENTER] to complete ..."
read ans
#exit
poweroff

