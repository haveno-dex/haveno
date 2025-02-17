#!/bin/bash

set -e
APPVM_NAME="haveno"

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
if ! [[ $unoffical -eq 0 ]] ; then
	from_source=0
	log "WARNING : you are installing the main haveno-dex repo but have no enabled build from source, as such this setting has been automatically toggled"
fi

TPL_ROOT="qvm-run --pass-io -u root -- $APPVM_NAME"

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
log "Installing necessary packages on template"
$TPL_ROOT "apt-get update && apt-get full-upgrade -y"
$TPL_ROOT "apt-get install --no-install-recommends qubes-core-agent-networking qubes-core-agent-passwordless-root qubes-core-agent-nautilus nautilus zenity curl -y && poweroff" || true
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

log "Cleaning any previous haveno hidden service on sys-whonix"
qvm-run --pass-io -u root -- sys-whonix "rm -rf /var/lib/tor/haveno_service"
log "Creating a hidden service for haveno on whonix gateway"
read -p "Warning by default 50_user.conf on sys-whonix will be overwritten (baseline is empty
Enter any character to append instead (may require cleaning if you reinstall haveno later" -n 1 cont
if ! [[ "$key" = "" ]];then
	log "Appending to 50_user.conf instead of overwriting"
	out=">>"
else
	log "Overwriting 50_user.conf"
	out=">"
fi
qvm-run --pass-io -u root -- sys-whonix "echo -e 'ConnectionPadding 1\nHiddenServiceDir /var/lib/tor/haveno_service/\nHiddenServicePort 9999 $(qvm-prefs $APPVM_NAME ip):9999' $out /usr/local/etc/torrc.d/50_user.conf && service tor@default reload"

log "Open port 9999 on $APPVM_NAME to allow incoming peer data"
$TPL_ROOT "echo -e 'nft add rule ip qubes input tcp dport 9999 counter accept\necho nameserver $SYS_WHONIX_IP > /etc/resolv.conf' > /rw/config/rc.local"

sleep 1
SERVICE="$(qvm-run --pass-io -u root -- sys-whonix 'cat /var/lib/tor/haveno_service/hostname')"

$TPL_ROOT "echo $SERVICE > /root/haveno-service-address"

version="$($TPL_ROOT curl -Ls -o /dev/null -w %{url_effective} $HAVENO_REPO/releases/latest)"
version=${version##*/}

if [[ $from_source -eq 1 ]]; then
	log "Downloading haveno release version $version"
	$TPL_ROOT "curl -L  --remote-name-all $HAVENO_REPO/releases/download/$version/{$TARGET_DEB,$TARGET_DEB.sig,$version-hashes.txt}"
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
Exec=sudo /bin/Haveno
Icon=/opt/haveno/lib/Haveno.png
Terminal=false
Type=Application
Categories=Network
MimeType="
$TPL_ROOT "echo -e '#\x21/bin/sh\n\n#Proxying to gateway (anon-ws-disable-stacked-tor)\nsocat TCP-LISTEN:9050,fork,bind=127.0.0.1 TCP:$SYS_WHONIX_IP:9050 &\nPID=\x24\x21\nSERVICE=\x24\x28cat /root/haveno-service-address\x29\n\n/opt/haveno/bin/Haveno --useTorForXmr=OFF --nodePort=9999 --hiddenServiceAddress=\x24SERVICE\nkill \x24PID' > /bin/Haveno && chmod +x /bin/Haveno && chmod u+s /bin/Haveno"

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
	$TPL_ROOT "echo -e '#\x21/bin/sh\n\n#Proxying to gateway (anon-ws-disable-stacked-tor)\nsocat TCP-LISTEN:9050,fork,bind=127.0.0.1 TCP:$SYS_WHONIX_IP:9050 &\nPID=\x24\x21\nSERVICE=\x24\x28cat /root/haveno-service-address\x29\n\nsource /root/.sdkman/bin/sdkman-init.sh\n/root/$CODE_DIR/haveno-desktop --useTorForXmr=OFF --nodePort=9999 --hiddenServiceAddress=\x24SERVICE\nkill \x24PID' > /bin/Haveno && chmod +x /bin/Haveno && chmod u+s /bin/Haveno"

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
	$TPL_ROOT "echo -e '#\x21/bin/bash\nsource /root/.sdkman/bin/sdkman-init.sh\n/root/$CODE_DIR/haveno-desktop --nodePort=9999 --useTorForXmr=OFF --hiddenServiceAddress=$SERVICE' > /usr/sbin/Haveno && chmod +x /usr/sbin/Haveno && chmod u+s /usr/sbin/Haveno"
	#Fix icon permissions
	$TPL_ROOT "cp '/root/$CODE_DIR/desktop/package/linux/haveno.png' /opt/haveno.png && chmod 644 /opt/haveno.png"
	patched_app_entry="[Desktop Entry]
Name=Haveno
Comment=Haveno through sys-whonix
Exec=sudo /usr/sbin/Haveno
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
	log "Hardening Completed"
	log "Remeber technical controls are only part of the battle, robust security is reliant on how you utilize the system"
fi



log "Restarting sys-whonix and $APPVM_NAME"
qvm-shutdown --wait $APPVM_NAME
qvm-shutdown --wait sys-whonix
qvm-start sys-whonix
log "Installation complete, launch haveno using application shortcut Enjoy!"




