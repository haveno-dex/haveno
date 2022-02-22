#!/usr/bin/env bash
set -e

echo "[*] haveno-pricenode installation script"

##### change as necessary for your system

SYSTEMD_SERVICE_HOME=/etc/systemd/system
SYSTEMD_ENV_HOME=/etc/default

ROOT_USER=root
ROOT_GROUP=root
#ROOT_HOME=/root

HAVENO_USER=pricenode
HAVENO_GROUP=pricenode
HAVENO_HOME=/pricenode

HAVENO_REPO_URL=https://github.com/haveno-dex/haveno
HAVENO_REPO_NAME=haveno
HAVENO_REPO_TAG=master
HAVENO_LATEST_RELEASE=master
HAVENO_TORHS=pricenode

TOR_PKG="tor"
#TOR_USER=debian-tor
TOR_GROUP=debian-tor
TOR_CONF=/etc/tor/torrc
TOR_RESOURCES=/var/lib/tor

#####

echo "[*] Upgrading apt packages"
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get update -q
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get upgrade -qq -y

echo "[*] Installing Git LFS"
sudo -H -i -u "${ROOT_USER}" curl -s https://packagecloud.io/install/repositories/github/git-lfs/script.deb.sh | bash
sudo -H -i -u "${ROOT_USER}" apt-get -y install git-lfs
sudo -H -i -u "${ROOT_USER}" git lfs install

echo "[*] Installing Tor"
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get install -qq -y "${TOR_PKG}"

echo "[*] Adding Tor configuration"
if ! grep "${HAVENO_TORHS}" /etc/tor/torrc >/dev/null 2>&1;then
  sudo -H -i -u "${ROOT_USER}" sh -c "echo HiddenServiceDir ${TOR_RESOURCES}/${HAVENO_TORHS}/ >> ${TOR_CONF}"
  sudo -H -i -u "${ROOT_USER}" sh -c "echo HiddenServicePort 80 127.0.0.1:8078 >> ${TOR_CONF}"
  sudo -H -i -u "${ROOT_USER}" sh -c "echo HiddenServiceVersion 3 >> ${TOR_CONF}"
fi

echo "[*] Creating Haveno user with Tor access"
sudo -H -i -u "${ROOT_USER}" useradd -d "${HAVENO_HOME}" -G "${TOR_GROUP}" "${HAVENO_USER}"

echo "[*] Creating Haveno homedir"
sudo -H -i -u "${ROOT_USER}" mkdir -p "${HAVENO_HOME}"
sudo -H -i -u "${ROOT_USER}" chown "${HAVENO_USER}":"${HAVENO_GROUP}" ${HAVENO_HOME}

echo "[*] Cloning Haveno repo"
sudo -H -i -u "${HAVENO_USER}" git config --global advice.detachedHead false
sudo -H -i -u "${HAVENO_USER}" git clone --branch "${HAVENO_REPO_TAG}" "${HAVENO_REPO_URL}" "${HAVENO_HOME}/${HAVENO_REPO_NAME}"

echo "[*] Installing OpenJDK 11"
sudo -H -i -u "${ROOT_USER}" apt-get install -qq -y openjdk-11-jdk

echo "[*] Checking out Haveno ${HAVENO_LATEST_RELEASE}"
sudo -H -i -u "${HAVENO_USER}" sh -c "cd ${HAVENO_HOME}/${HAVENO_REPO_NAME} && git checkout ${HAVENO_LATEST_RELEASE}"

echo "[*] Performing Git LFS pull"
sudo -H -i -u "${HAVENO_USER}" sh -c "cd ${HAVENO_HOME}/${HAVENO_REPO_NAME} && git lfs pull"

echo "[*] Building Haveno from source"
sudo -H -i -u "${HAVENO_USER}" sh -c "cd ${HAVENO_HOME}/${HAVENO_REPO_NAME} && ./gradlew :pricenode:installDist  -x test < /dev/null" # redirect from /dev/null is necessary to workaround gradlew non-interactive shell hanging issue

echo "[*] Installing haveno-pricenode systemd service"
sudo -H -i -u "${ROOT_USER}" install -c -o "${ROOT_USER}" -g "${ROOT_GROUP}" -m 644 "${HAVENO_HOME}/${HAVENO_REPO_NAME}/pricenode/haveno-pricenode.service" "${SYSTEMD_SERVICE_HOME}"
sudo -H -i -u "${ROOT_USER}" install -c -o "${ROOT_USER}" -g "${ROOT_GROUP}" -m 644 "${HAVENO_HOME}/${HAVENO_REPO_NAME}/pricenode/haveno-pricenode.env" "${SYSTEMD_ENV_HOME}"

echo "[*] Reloading systemd daemon configuration"
sudo -H -i -u "${ROOT_USER}" systemctl daemon-reload

echo "[*] Enabling haveno-pricenode service"
sudo -H -i -u "${ROOT_USER}" systemctl enable haveno-pricenode.service

echo "[*] Starting haveno-pricenode service"
sudo -H -i -u "${ROOT_USER}" systemctl start haveno-pricenode.service
sleep 5
sudo -H -i -u "${ROOT_USER}" journalctl --no-pager --unit haveno-pricenode

echo "[*] Restarting Tor"
sudo -H -i -u "${ROOT_USER}" service tor restart
sleep 5

echo '[*] Done!'
echo -n '[*] Access your pricenode at http://'
cat "${TOR_RESOURCES}/${HAVENO_TORHS}/hostname"

exit 0
