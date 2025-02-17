# qubes-haveno-appvm
dom0 script to create a haveno appvm

# Usage
./haveno-qubes-install.sh [options]

	-c : Reinstall template if already exists
	-s : Build haveno from source instead of deb package
	-r [git repo url] : Install an unoffical haveno fork hosted at the suppplied web url
	-u : Do not harden appVM
	-f [compressed haveno deb] : Specify release asset that contains zipped haveno deb (default: haveno-linux-deb.zip)
	-n [appVM name] : Name of haveno appvm (default: haveno)
`
# Functionality
Allows for automatic creation of a haveno AppVM based on debian-12-minimal template. Ensures tor over tor is not occuring when using haveno, instead utilizing haveno's direct bind address option. Performs hardening and minimzes the attack surface of the vm by default. Allows for install of unoffical haveno clients for easy access to mainnet trading. 

# Changing hidden service address used by haveno
./refresh-haveno-address.sh

    -n [HAVENOVM_NAME] : (optional: default haveno)
    -h : print help message

# Functionality
This script will cause sys-whonix to generate a new hidden service address for haveno to utilize and automatically update the haveno vm to utilize it.
