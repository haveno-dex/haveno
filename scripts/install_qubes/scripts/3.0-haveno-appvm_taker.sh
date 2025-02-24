#!/bin/zsh
## ./haveno-on-qubes/scripts/3.0-haveno-appvm_taker.sh 

## Function to print messages in blue:
echo_blue() {
  echo -e "\033[1;34m$1\033[0m"
}


# Function to print error messages in red:
echo_red() {
  echo -e "\033[0;31m$1\033[0m"
}

## Adjust sdwdate Configuration
mkdir -p /usr/local/etc/sdwdate-gui.d
printf "gateway=sys-haveno\n" > /usr/local/etc/sdwdate-gui.d/50_user.conf


## Prepare Firewall Settings
echo_blue "\nConfiguring FW ..."
printf "\n# Prepare Local FW Settings\nmkdir -p /usr/local/etc/whonix_firewall.d\n" >> /rw/config/rc.local
printf "\n# Poke FW\nprintf \"EXTERNAL_OPEN_PORTS+=\\\\\" 9999 \\\\\"\\\n\" | tee /usr/local/etc/whonix_firewall.d/50_user.conf\n" >> /rw/config/rc.local
printf "\n# Restart FW\nwhonix_firewall\n\n" >> /rw/config/rc.local


### Adjust Desktop Launcher:
echo_blue "Adjustng desktop launcher ..."
mkdir -p /home/$(ls /home)/\.local/share/applications
sed 's|/opt/haveno/bin/Haveno|/opt/haveno/bin/Haveno --torControlPort=9051 --socks5ProxyXmrAddress=127.0.0.1:9050 --useTorForXmr=on|g' /opt/haveno/lib/haveno-Haveno.desktop > /home/$(ls /home)/.local/share/applications/haveno-Haveno.desktop
chown -R $(ls /home):$(ls /home) /home/$(ls /home)/.local/share/applications/haveno-Haveno.desktop
echo_blue "Haveno AppVM configuration complete."
printf "%s \n" "Press [ENTER] to complete ..."
read ans
#exit
poweroff
