#!/bin/zsh
## ./haveno-on-qubes/scripts/3.1-haveno-appvm_maker.sh

if [[ $# -ne 1 ]] ; then
    printf "\nNo arguments provided!\n\nThis script requires an argument to be provided:\nOnion Address of Haveno NetVM\n\nPlease review documentation and try again.\n\nExiting now ...\n"
    exit 1
fi


## Function to print messages in blue:
echo_blue() {
  echo -e "\033[1;34m$1\033[0m"
}


# Function to print error messages in red:
echo_red() {
  echo -e "\033[0;31m$1\033[0m"
}


ONION=$1


## Adjust sdwdate Configuration
mkdir -p /usr/local/etc/sdwdate-gui.d
printf "gateway=sys-haveno\n" > /usr/local/etc/sdwdate-gui.d/50_user.conf


## Prepare Firewall Settings
echo_blue "\nConfiguring FW ..."
printf "\n# Prepare Local FW Settings\nmkdir -p /usr/local/etc/whonix_firewall.d\n" >> /rw/config/rc.local
printf "\n# Poke FW\nprintf \"EXTERNAL_OPEN_PORTS+=\\\\\" 9999 \\\\\"\\\n\" | tee /usr/local/etc/whonix_firewall.d/50_user.conf\n" >> /rw/config/rc.local
printf "\n# Restart FW\nwhonix_firewall\n\n" >> /rw/config/rc.local


## View & Verify Change
echo_blue "\nReview the following output and be certain in matches documentation!\n"
tail /rw/config/rc.local
printf "%s \n" "Press [ENTER] to continue ..."
read ans
:


## Restart FW
echo_blue "\nRestarting Whonix FW ..."
whonix_firewall


### Create Desktop Launcher:
echo_blue "Creating desktop launcher ..."
mkdir -p /home/$(ls /home)/\.local/share/applications
sed "s|/opt/haveno/bin/Haveno|/opt/haveno/bin/Haveno --socks5ProxyXmrAddress=127.0.0.1:9050 --useTorForXmr=on --nodePort=9999 --hiddenServiceAddress=$ONION|g" /opt/haveno/lib/haveno-Haveno.desktop > /home/$(ls /home)/.local/share/applications/haveno-Haveno.desktop
chown -R $(ls /home):$(ls /home) /home/$(ls /home)/.local/share/applications


## View & Verify Change
echo_blue "\nReview the following output and be certain in matches documentation!\n"
tail /home/$(ls /home)/.local/share/applications/haveno-Haveno.desktop
printf "%s \n" "Press [ENTER] to continue ..."
read ans
:


echo_blue "Haveno AppVM configuration complete."
echo_blue "Refresh applications via Qubes Manager GUI now."
printf "%s \n" "Press [ENTER] to complete ..."
read ans
#exit
poweroff

