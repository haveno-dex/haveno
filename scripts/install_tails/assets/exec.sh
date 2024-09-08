#!/bin/bash


# This script serves as the execution entry point for the Haveno application from a desktop menu icon,
# specifically tailored for use in the Tails OS. It is intended to be linked as the 'Exec' command
# in a .desktop file, enabling users to start Haveno directly from the desktop interface.
#
# FUNCTIONAL OVERVIEW:
# - Automatic installation and configuration of Haveno if not already set up.
# - Linking Haveno data directories to persistent storage to preserve user data across sessions.
#
# NOTE:
# This script assumes that Haveno's related utility scripts and files are correctly placed and accessible
# in the specified directories.


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
data_dir="${persistence_dir}/haveno/Data"


# Create data dir
mkdir -p "${data_dir}"


# Check if Haveno is already installed and configured
if [ ! -f "/opt/haveno/bin/Haveno" ] || [ ! -f "/etc/onion-grater.d/haveno.yml" ]; then
  echo_blue "Installing Haveno and configuring system..."
  pkexec "${persistence_dir}/haveno/App/utils/install.sh"
  # Redirect user data to Tails Persistent Storage
  ln -s "${data_dir}" /home/amnesia/.local/share/Haveno
else
  echo_blue "Haveno is already installed and configured."
fi


echo_blue "Starting Haveno..."
/opt/haveno/bin/Haveno --torControlPort 951 --torControlCookieFile=/var/run/tor/control.authcookie --torControlUseSafeCookieAuth --userDataDir=${data_dir} --useTorForXmr=on --socks5ProxyXmrAddress=127.0.0.1:9050
