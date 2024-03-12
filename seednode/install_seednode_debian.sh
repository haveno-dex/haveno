#!/bin/sh
set -e

echo "[*] Haveno Seednode installation script"

##### change paths if necessary for your system

ROOT_USER=root
ROOT_GROUP=root
ROOT_PKG="build-essential libtool autotools-dev automake pkg-config bsdmainutils python3 git vim screen ufw openjdk-21-jdk"
ROOT_HOME=/root

SYSTEMD_SERVICE_HOME=/etc/systemd/system
SYSTEMD_ENV_HOME=/etc/default

HAVENO_REPO_URL=https://github.com/haveno-dex/haveno
HAVENO_REPO_NAME=haveno
HAVENO_REPO_TAG=master
HAVENO_LATEST_RELEASE=$(curl -s https://api.github.com/repos/haveno-dex/haveno/releases/latest|grep tag_name|head -1|cut -d '"' -f4)
HAVENO_HOME=/haveno
HAVENO_USER=haveno

# by default, this script will not build and setup bitcoin full-node
BITCOIN_INSTALL=false
BITCOIN_REPO_URL=https://github.com/bitcoin/bitcoin
BITCOIN_REPO_NAME=bitcoin
BITCOIN_REPO_TAG=$(curl -s https://api.github.com/repos/bitcoin/bitcoin/releases/latest|grep tag_name|head -1|cut -d '"' -f4)
BITCOIN_HOME=/bitcoin
BITCOIN_USER=bitcoin
BITCOIN_GROUP=bitcoin
BITCOIN_PKG="libevent-dev libboost-system-dev libboost-filesystem-dev libboost-chrono-dev libboost-test-dev libboost-thread-dev libdb-dev libssl-dev"
BITCOIN_P2P_HOST=127.0.0.1
BITCOIN_P2P_PORT=8333
BITCOIN_RPC_HOST=127.0.0.1
BITCOIN_RPC_PORT=8332

# set below settings to use existing bitcoin node
#BITCOIN_INSTALL=false
#BITCOIN_P2P_HOST=192.168.1.1
#BITCOIN_P2P_PORT=8333
#BITCOIN_RPC_HOST=192.168.1.1
#BITCOIN_RPC_PORT=8332
#BITCOIN_RPC_USER=foo
#BITCOIN_RPC_PASS=bar

TOR_PKG="tor deb.torproject.org-keyring"
TOR_USER=debian-tor
TOR_GROUP=debian-tor
TOR_HOME=/etc/tor

#####

echo "[*] Updating apt repo sources"
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get update -q

echo "[*] Upgrading OS packages"
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get upgrade -qq -y

echo "[*] Installing base packages"
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get install -qq -y ${ROOT_PKG}

echo "[*] Cloning Haveno repo"
sudo -H -i -u "${ROOT_USER}" git config --global advice.detachedHead false
sudo -H -i -u "${ROOT_USER}" git clone --branch "${HAVENO_REPO_TAG}" "${HAVENO_REPO_URL}" "${ROOT_HOME}/${HAVENO_REPO_NAME}"

echo "[*] Installing Tor"
sudo -H -i -u "${ROOT_USER}" wget -qO- https://deb.torproject.org/torproject.org/A3C4F0F979CAA22CDBA8F512EE8CBC9E886DDD89.asc | sudo gpg --dearmor | sudo tee /usr/share/keyrings/tor-archive-keyring.gpg >/dev/null
echo "deb [arch=amd64 signed-by=/usr/share/keyrings/tor-archive-keyring.gpg] https://deb.torproject.org/torproject.org focal main" | sudo -H -i -u "${ROOT_USER}" tee /etc/apt/sources.list.d/tor.list
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get update -q
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get install -qq -y ${TOR_PKG}

echo "[*] Installing Tor configuration"
sudo -H -i -u "${ROOT_USER}" install -c -m 644 "${ROOT_HOME}/${HAVENO_REPO_NAME}/seednode/torrc" "${TOR_HOME}/torrc"

if [ "${BITCOIN_INSTALL}" = true ];then

	echo "[*] Creating Bitcoin user with Tor access"
	sudo -H -i -u "${ROOT_USER}" useradd -d "${BITCOIN_HOME}" -G "${TOR_GROUP}" "${BITCOIN_USER}"

	echo "[*] Installing Bitcoin build dependencies"
	sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get install -qq -y ${BITCOIN_PKG}

	echo "[*] Creating Bitcoin homedir"
	sudo -H -i -u "${ROOT_USER}" mkdir -p "${BITCOIN_HOME}"
	sudo -H -i -u "${ROOT_USER}" chown "${BITCOIN_USER}":"${BITCOIN_GROUP}" ${BITCOIN_HOME}
	sudo -H -i -u "${BITCOIN_USER}" ln -s . .bitcoin

	echo "[*] Cloning Bitcoin repo"
	sudo -H -i -u "${BITCOIN_USER}" git config --global advice.detachedHead false
	sudo -H -i -u "${BITCOIN_USER}" git clone --branch "${BITCOIN_REPO_TAG}" "${BITCOIN_REPO_URL}" "${BITCOIN_HOME}/${BITCOIN_REPO_NAME}"

	echo "[*] Building Bitcoin from source"
	sudo -H -i -u "${BITCOIN_USER}" sh -c "cd ${BITCOIN_REPO_NAME} && ./autogen.sh --quiet && ./configure --quiet --disable-wallet --with-incompatible-bdb && make -j9"

	echo "[*] Installing Bitcoin into OS"
	sudo -H -i -u "${ROOT_USER}" sh -c "cd ${BITCOIN_HOME}/${BITCOIN_REPO_NAME} && make install >/dev/null"

	echo "[*] Installing Bitcoin configuration"
	sudo -H -i -u "${ROOT_USER}" install -c -o "${BITCOIN_USER}" -g "${BITCOIN_GROUP}" -m 644 "${ROOT_HOME}/${HAVENO_REPO_NAME}/seednode/bitcoin.conf" "${BITCOIN_HOME}/bitcoin.conf"
	sudo -H -i -u "${ROOT_USER}" install -c -o "${BITCOIN_USER}" -g "${BITCOIN_GROUP}" -m 755 "${ROOT_HOME}/${HAVENO_REPO_NAME}/seednode/blocknotify.sh" "${BITCOIN_HOME}/blocknotify.sh"

	echo "[*] Generating Bitcoin RPC credentials"
	BITCOIN_RPC_USER=$(head -150 /dev/urandom | md5sum | awk '{print $1}')
	sudo sed -i -e "s/__BITCOIN_RPC_USER__/${BITCOIN_RPC_USER}/" "${BITCOIN_HOME}/bitcoin.conf"
	BITCOIN_RPC_PASS=$(head -150 /dev/urandom | md5sum | awk '{print $1}')
	sudo sed -i -e "s/__BITCOIN_RPC_PASS__/${BITCOIN_RPC_PASS}/" "${BITCOIN_HOME}/bitcoin.conf"

	echo "[*] Installing Bitcoin init scripts"
	sudo -H -i -u "${ROOT_USER}" install -c -o "${ROOT_USER}" -g "${ROOT_GROUP}" -m 644 "${ROOT_HOME}/${HAVENO_REPO_NAME}/seednode/bitcoin.service" "${SYSTEMD_SERVICE_HOME}"

fi

echo "[*] Creating Haveno user with Tor access"
sudo -H -i -u "${ROOT_USER}" useradd -d "${HAVENO_HOME}" -G "${TOR_GROUP}" "${HAVENO_USER}"

echo "[*] Creating Haveno homedir"
sudo -H -i -u "${ROOT_USER}" mkdir -p "${HAVENO_HOME}"
sudo -H -i -u "${ROOT_USER}" chown "${HAVENO_USER}":"${HAVENO_GROUP}" ${HAVENO_HOME}

echo "[*] Moving Haveno repo"
sudo -H -i -u "${ROOT_USER}" mv "${ROOT_HOME}/${HAVENO_REPO_NAME}" "${HAVENO_HOME}/${HAVENO_REPO_NAME}"
sudo -H -i -u "${ROOT_USER}" chown -R "${HAVENO_USER}:${HAVENO_GROUP}" "${HAVENO_HOME}/${HAVENO_REPO_NAME}"

echo "[*] Installing Haveno init script"
sudo -H -i -u "${ROOT_USER}" install -c -o "${ROOT_USER}" -g "${ROOT_GROUP}" -m 644 "${HAVENO_HOME}/${HAVENO_REPO_NAME}/seednode/haveno-seednode.service" "${SYSTEMD_SERVICE_HOME}/haveno-seednode.service"
if [ "${BITCOIN_INSTALL}" = true ];then
	sudo sed -i -e "s/#Requires=bitcoin.service/Requires=bitcoin.service/" "${SYSTEMD_SERVICE_HOME}/haveno-seednode.service"
	sudo sed -i -e "s/#BindsTo=bitcoin.service/BindsTo=bitcoin.service/" "${SYSTEMD_SERVICE_HOME}/haveno-seednode.service"
fi
sudo sed -i -e "s/__HAVENO_REPO_NAME__/${HAVENO_REPO_NAME}/" "${SYSTEMD_SERVICE_HOME}/haveno-seednode.service"
sudo sed -i -e "s!__HAVENO_HOME__!${HAVENO_HOME}!" "${SYSTEMD_SERVICE_HOME}/haveno-seednode.service"

echo "[*] Installing Haveno environment file with Bitcoin RPC credentials"
sudo -H -i -u "${ROOT_USER}" install -c -o "${ROOT_USER}" -g "${ROOT_GROUP}" -m 644 "${HAVENO_HOME}/${HAVENO_REPO_NAME}/seednode/haveno.env" "${SYSTEMD_ENV_HOME}/haveno.env"
sudo sed -i -e "s/__BITCOIN_P2P_HOST__/${BITCOIN_P2P_HOST}/" "${SYSTEMD_ENV_HOME}/haveno.env"
sudo sed -i -e "s/__BITCOIN_P2P_PORT__/${BITCOIN_P2P_PORT}/" "${SYSTEMD_ENV_HOME}/haveno.env"
sudo sed -i -e "s/__BITCOIN_RPC_HOST__/${BITCOIN_RPC_HOST}/" "${SYSTEMD_ENV_HOME}/haveno.env"
sudo sed -i -e "s/__BITCOIN_RPC_PORT__/${BITCOIN_RPC_PORT}/" "${SYSTEMD_ENV_HOME}/haveno.env"
sudo sed -i -e "s/__BITCOIN_RPC_USER__/${BITCOIN_RPC_USER}/" "${SYSTEMD_ENV_HOME}/haveno.env"
sudo sed -i -e "s/__BITCOIN_RPC_PASS__/${BITCOIN_RPC_PASS}/" "${SYSTEMD_ENV_HOME}/haveno.env"
sudo sed -i -e "s!__HAVENO_APP_NAME__!${HAVENO_APP_NAME}!" "${SYSTEMD_ENV_HOME}/haveno.env"
sudo sed -i -e "s!__HAVENO_HOME__!${HAVENO_HOME}!" "${SYSTEMD_ENV_HOME}/haveno.env"

echo "[*] Checking out Haveno ${HAVENO_LATEST_RELEASE}"
sudo -H -i -u "${HAVENO_USER}" sh -c "cd ${HAVENO_HOME}/${HAVENO_REPO_NAME} && git checkout ${HAVENO_LATEST_RELEASE}"

echo "[*] Building Haveno from source"
sudo -H -i -u "${HAVENO_USER}" sh -c "cd ${HAVENO_HOME}/${HAVENO_REPO_NAME} && ./gradlew build -x test < /dev/null" # redirect from /dev/null is necessary to workaround gradlew non-interactive shell hanging issue

echo "[*] Updating systemd daemon configuration"
sudo -H -i -u "${ROOT_USER}" systemctl daemon-reload
sudo -H -i -u "${ROOT_USER}" systemctl enable tor.service
sudo -H -i -u "${ROOT_USER}" systemctl enable haveno-seednode.service
if [ "${BITCOIN_INSTALL}" = true ];then
	sudo -H -i -u "${ROOT_USER}" systemctl enable bitcoin.service
fi

echo "[*] Preparing firewall"
sudo -H -i -u "${ROOT_USER}" ufw default deny incoming
sudo -H -i -u "${ROOT_USER}" ufw default allow outgoing

echo "[*] Starting Tor"
sudo -H -i -u "${ROOT_USER}" systemctl start tor

if [ "${BITCOIN_INSTALL}" = true ];then
	echo "[*] Starting Bitcoin"
	sudo -H -i -u "${ROOT_USER}" systemctl start bitcoin
	sudo -H -i -u "${ROOT_USER}" journalctl --no-pager --unit bitcoin
	sudo -H -i -u "${ROOT_USER}" tail "${BITCOIN_HOME}/debug.log"
fi

echo "[*] Adding notes to motd"
sudo -H -i -u "${ROOT_USER}" sh -c 'echo " " >> /etc/motd'
sudo -H -i -u "${ROOT_USER}" sh -c 'echo "Haveno Seednode instructions:" >> /etc/motd'
sudo -H -i -u "${ROOT_USER}" sh -c 'echo "https://github.com/haveno-dex/haveno/tree/master/seednode" >> /etc/motd'
sudo -H -i -u "${ROOT_USER}" sh -c 'echo " " >> /etc/motd'
sudo -H -i -u "${ROOT_USER}" sh -c 'echo "How to check logs for Haveno-Seednode service:" >> /etc/motd'
sudo -H -i -u "${ROOT_USER}" sh -c 'echo "sudo journalctl --no-pager --unit haveno-seednode" >> /etc/motd'
sudo -H -i -u "${ROOT_USER}" sh -c 'echo " " >> /etc/motd'
sudo -H -i -u "${ROOT_USER}" sh -c 'echo "How to restart Haveno-Seednode service:" >> /etc/motd'
sudo -H -i -u "${ROOT_USER}" sh -c 'echo "sudo service haveno-seednode restart" >> /etc/motd'

echo '[*] Done!'

echo '  '
echo '[*] DONT FORGET TO ENABLE FIREWALL!!!11'
echo '[*] Follow all the README instructions!'
echo '  '
