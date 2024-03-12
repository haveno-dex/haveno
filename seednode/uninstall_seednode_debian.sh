#!/bin/sh
echo "[*] Uninstalling Bitcoin and Haveno, will delete all data!!"
sleep 10
sudo rm -rf /root/haveno
sudo systemctl stop bitcoin
sudo systemctl stop haveno-seednode
sudo systemctl disable bitcoin
sudo systemctl disable haveno-seednode
sudo userdel -f -r haveno
sudo userdel -f -r bitcoin
echo "[*] Done!"
