#!/bin/bash


# This script automates the installation and configuration of Haveno on a Tails OS system,
#
# FUNCTIONAL OVERVIEW:
# - Verification of the Haveno installer's presence.
# - Installation of the Haveno application with dpkg.
# - Removal of automatically created desktop icons to clean up after installation.
# - Deployment of Tor configuration for Haveno.
# - Restart of the onion-grater service to apply new configurations.
#
# The script requires administrative privileges to perform system modifications.


# Function to print messages in blue
echo_blue() {
  if [ -t 1 ]; then
    # If File descriptor 1 (stdout) is open and refers to a terminal
    echo -e "\033[1;34m$1\033[0m"
  else
    # If stdout is not a terminal, send a desktop notification
    notify-send -i "/home/amnesia/Persistent/haveno/App/utils/icon.png" "Starting Haveno" "$1"
  fi
}


# Function to print error messages in red
echo_red() {
  if [ -t 1 ]; then
    # If File descriptor 1 (stdout) is open and refers to a terminal
    echo -e "\033[0;31m$1\033[0m"
  else
    # If stdout is not a terminal, send a desktop notification
    notify-send -u critical -i "error" "Staring Haveno" "$1\nExiting..."
  fi
}


# Define file locations
persistence_dir="/home/amnesia/Persistent"
app_dir="${persistence_dir}/haveno/App"
install_dir="${persistence_dir}/haveno/Install"
haveno_installer="${install_dir}/haveno.deb"
haveno_config_file="${app_dir}/utils/haveno.yml"


# Check if the Haveno installer exists
if [ ! -f "${haveno_installer}" ]; then
  echo_red "Haveno installer not found at ${haveno_installer}."
  exit 1
fi


# Install Haveno
echo_blue "Installing Haveno..."
dpkg -i "${haveno_installer}" || { echo_red "Failed to install Haveno."; exit 1; }


# Remove installed desktop menu icon
rm -f /usr/share/applications/haveno-Haveno.desktop


# Change access rights for Tor control cookie
echo_blue "Changing access rights for Tor control cookie..."
chmod o+r /var/run/tor/control.authcookie || { echo_red "Failed to change access rights for Tor control cookie."; exit 1; }


# Copy haveno.yml configuration file
echo_blue "Copying Tor onion-grater configuration to /etc/onion-grater.d/..."
cp "${haveno_config_file}" /etc/onion-grater.d/haveno.yml || { echo_red "Failed to copy haveno.yml."; exit 1; }


# Restart onion-grater service
echo_blue "Restarting onion-grater service..."
systemctl restart onion-grater.service || { echo_red "Failed to restart onion-grater service."; exit 1; }


echo_blue "Haveno installation and configuration complete."
