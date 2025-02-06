#!/bin/bash

#set -e
APPVM_NAME="haveno"
TPL_ROOT="qvm-run --pass-io -u root -- $APPVM_NAME"

#Options
clean=1
from_source=1
unoffical=1
unhardened=1
HAVENO_REPO="https://github.com/haveno-dex/haveno"
regex='(https?)://[-A-Za-z0-9\+&@#/%?=~_|!:,.;]*[-A-Za-z0-9\+&@#/%=~_|]'
TARGET_DEB="haveno-linux-deb.zip"

print_usage() {
	printf "\nUsage: ./haveno-qubes-install.sh [options]\n\t-c : Reinstall template if already exists\n\t-s : Build haveno from source instead of deb package\n\t-r [git repo url] : Install an unoffical haveno fork hosted at the suppplied web url\n\t-u : Do not harden appVM\n\t-f [compressed haveno deb] : Specify release asset that contains zipped haveno deb (default: haveno-linux-deb.zip)\n\t-n [appVM name] : Name of haveno appvm (default: haveno)\n"
}

log () {
	echo "$@"
}

while getopts 'csauhn:r:f:' flag; do
	case "${flag}" in
		c) clean=0 ;;
		s) from_source=0 ;;
		r) unoffical=0
			# input validation
			HAVENO_REPO=${OPTARG}
			if [[ $HAVENO_REPO =~ $regex ]] ; then
				log "Will attempt to install haveno fork at $HAVENO_REPO"
			else
				log "Invalid url supplied (must be http(s))"
				exit 1 ;
			fi
		      ;;
		u) unhardened=0 ;;
		f) TARGET_DEB=${OPTARG} 
			if [ $from_source -eq 0 ]; then
				log "Building from source but deb file path specified check options"
				exit 1
			elif [ $unoffical -eq 1 ]; then
				log "Deb file path option (-f) only is available when installing an unoffical haveno client"
				exit 1
			fi
			;;
		n) APPVM_NAME=${OPTARG} ;;
		*) print_usage
			exit 1 ;;
	esac
done

#Set build from force true if using haveno main repo
if ! [[ $unoffical ]] ; then
	from_source=0
	log "WARNING : you are installing the main haveno-dex repo but have no enabled build from source, as such this setting has been automatically toggled"
fi

if [ "$(hostname)" != "dom0" ]; then
	echo "This script must be ran on dom0 to function"
	exit 1;
fi
log "Starting installation"

#download debian-12-minimal if not installed
if ! [[ "$(qvm-template list --installed)" =~ "debian-12-minimal" ]]; then
log "debian-12-minimal template not installed, installing now"
sudo qubes-dom0-update --action=install qubes-template-debian-12-minimal

# reinstall debian-12-minimal if clean install set
elif [ $clean -eq 0 ]; then
	log "clean setting specified reinstalling debian-12-minimal"
	sudo qubes-dom0-update --action=reinstall qubes-template-debian-12-minimal
else
	log "debian-12-minimal template already installed"
fi
log "cloning the template"
qvm-clone debian-12-minimal "$APPVM_NAME"
log "removing downloaded template"
sudo dnf remove qubes-template-12-minimal
log "Installing necessary packages on template"
$TPL_ROOT "apt update && apt full-upgrade -y"
$TPL_ROOT "apt-get install --no-install-recommends qubes-core-agent-networking qubes-core-agent-nautilus nautilus zenity curl -y && poweroff" || true
log "Setting $APPVM_NAME network to sys-whonix"
qvm-prefs $APPVM_NAME netvm sys-whonix

#prevents qrexec error by sleeping
sleep 5
SYS_WHONIX_IP="$(qvm-prefs sys-whonix ip)"
$TPL_ROOT "echo 'nameserver $SYS_WHONIX_IP' > /etc/resolv.conf"

log "Testing for successful sys-whonix config"
if [[ "$($TPL_ROOT curl https://check.torproject.org)" =~ "tor-off.png" ]]; then
	log "sys-whonix failed to connect, traffic not being routed through tor"
	exit 1;
else
	log "sys-whonix connection success, traffic is being routed through tor"
fi


version="$($TPL_ROOT curl -Ls -o /dev/null -w %{url_effective} $HAVENO_REPO/releases/latest)"
version=${version##*/}

if [[ $from_source -eq 1 ]]; then
	log "Downloading haveno release version $version"
	$TPL_ROOT "curl -Ls  --remote-name-all $HAVENO_REPO/releases/download/$version/{$TARGET_DEB,$TARGET_DEB.sig,$version-hashes.txt}"
	read -p "Enter url to verify signatures or anything else to skip:" key
	if [[ $key =~ $regex ]]; then
		$TPL_ROOT "apt-get install --no-install-recommends gnupg2 -y"
		$TPL_ROOT "gpg2 --fetch-keys $key"
		if [[ "$($TPL_ROOT 'gpg2 --verify $TARGET_DEB.sig 2>&1')" =~ 'Good signature' ]] ; then
			log "Signature valid, continuing"
		else
			log "Signature invalid, exiting"
			exit 1;
		fi
	fi
		
	log "Verifying SHA-512"
	release_sum=$($TPL_ROOT grep -A 1 "$TARGET_DEB" $version-hashes.txt | tail -n1 | tr -d '\n\r')
	log "sha512sum of $TARGET_DEB according to release: $release_sum"
	formated="$release_sum $TARGET_DEB"
	check=$($TPL_ROOT "echo $formated | sha512sum -c")
	if [[ "$check" =~ "OK" ]]; then
		log "sha512sums match, continuing"
	else
		log "sha512sums don't match, exiting"
		exit 1;
	fi
	# xdg-utils workaround
	$TPL_ROOT "mkdir /usr/share/desktop-directories/"
	$TPL_ROOT "apt-get install --no-install-recommends unzip libpcre3 xdg-utils libxtst6 -y"
	log "Extracting deb package"
	$TPL_ROOT "mkdir out-dir && unzip $TARGET_DEB -d out-dir"
	log "Installing haveno deb"
	$TPL_ROOT "dpkg -i out-dir/*.deb"
	log "Installed haveno deb"
	patched_app_entry="[Desktop Entry]
Name=Haveno
Comment=Haveno through sys-whonix
Exec=/usr/local/sbin/Haveno
Icon=/opt/haveno/lib/Haveno.png
Terminal=false
Type=Application
Categories=Network
MimeType="
	$TPL_ROOT "echo -e '#\x21/bin/sh\n\n/opt/haveno/bin/Haveno --useTorForXmr=OFF --torControlPort=9051 --torControlHost=$SYS_WHONIX_IP' > /usr/local/sbin/Haveno && chmod +x /usr/local/sbin/Haveno"


elif [[ $from_source -eq 0 ]]; then
	log "Installing required packages for build"
	$TPL_ROOT "apt-get install --no-install-recommends make wget git zip unzip libxtst6 qubes-core-agent-passwordless-root -y"
	log "Installing jdk 21"
	$TPL_ROOT "curl -s https://get.sdkman.io | bash"
	$TPL_ROOT "source /root/.sdkman/bin/sdkman-init.sh && sdk install java 21.0.2.fx-librca"
	log "Checking out haveno repo"
	CODE_DIR="$(basename $HAVENO_REPO)"
	$TPL_ROOT "git clone $HAVENO_REPO"
	$TPL_ROOT "source ~/.sdkman/bin/sdkman-init.sh && cd $CODE_DIR && git checkout master"
	log "Making binary"
	$TPL_ROOT "source ~/.sdkman/bin/sdkman-init.sh && cd $CODE_DIR && make skip-tests"
	log "Compilation successful, creating a script to run compiled binary securly"
	$TPL_ROOT "echo -e '#\x21/bin/bash\nsource /root/.sdkman/bin/sdkman-init.sh\n/root/$CODE_DIR/haveno-desktop --torControlPort=9051 --useTorForXmr=OFF --torControlHost=$SYS_WHONIX_IP' > /usr/local/sbin/Haveno && chmod +x /usr/local/sbin/Haveno && chmod u+s /usr/local/sbin/Haveno"
	#Fix icon permissions
	$TPL_ROOT "cp '/root/haveno-reto/desktop/package/linux/haveno.png' /opt/haveno.png && chmod 644 /opt/haveno.png"
	patched_app_entry="[Desktop Entry]
Name=Haveno
Comment=Haveno through sys-whonix
Exec=sudo /usr/local/sbin/Haveno
Icon=/opt/haveno.png
Terminal=false
Type=Application
Categories=Network
MimeType="

fi

# Patch default appmenu entry
$TPL_ROOT "echo '$patched_app_entry' > /usr/share/applications/haveno-Haveno.desktop"
qvm-sync-appmenus $APPVM_NAME
qvm-features $APPVM_NAME menu-items "haveno-Haveno.desktop"

if [ $unhardened ]; then
	log "Skipping hardening of debian-12 template"
else
	log "Starting hardening"
 	log "Installing tirdad to prevent ISN CPU info leak"
	$TPL_ROOT "curl -s -O https://www.whonix.org/patrick.asc"
	$TPL_ROOT "sudo apt-key --keyring /etc/apt/trusted.gpg.d/whonix.gpg add ~/patrick.asc"
	$TPL_ROOT "echo 'deb https://deb.whonix.org bullseye main contrib non-free' | tee /etc/apt/sources.list.d/whonix.list"
	$TPL_ROOT "apt-get update && apt-get install --no-install-recommends tirdad -y"
	# Remove unneeded packages
	log "Removing unneeded packages to lessen attack surface"
	if [ $from_source -eq 0 ]; then
		$TPL_ROOT "apt purge git wget make zip unzip curl -y"
	else
		$TPL_ROOT "apt purge curl unzip gnupg2 -y"
	fi
	#Whonix-gateway hardening
	log "Hardening whonix-gateway template"
	qvm-run --pass-io -u root -- whonix-gateway-17 "echo -e 'Sandbox 1\nConnectionPadding 1\n' > /usr/local/etc/torrc.d/50_user.conf"
	log "Hardening Completed"
	log "Remeber technical controls are only part of the battle, robust security is reliant on how you utilize the system"
fi

log "Enabling onion grater config on sys-whonix"
if [[ "$(qvm-run -u root --pass-io -- whonix-gateway-17 'sudo onion-grater-add 40_haveno')" =~ "OK" ]]; then
	log "Succesfully configured grater on sys-whonix"
else
	log "Failed to configure grater on sys-whonix, updating whonix gateway template and trying again"
	qubes-vm-update -r --targets whonix-gateway-17
	if [[ "$(qvm-run -u root --pass-io -- whonix-gateway-17 'sudo onion-grater-add 40_haveno')" =~ "OK" ]]; then
		log "Succeeded in configuring onion grater"
	else
		log "Failed for unkown reason, exiting"
		exit 1;
	fi
fi
log "Restarting sys-whonix and finishing"
qvm-shutdown sys-whonix
qvm-start sys-whonix
log "Installation complete, launch haveno using application shortcut Enjoy!"




