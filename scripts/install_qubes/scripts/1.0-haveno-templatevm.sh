#!/bin/zsh
## ./haveno-on-qubes/scripts/1.1-haveno-templatevm_maker.sh

if [[ $# -ne 2 ]] ; then
    printf "\nNo arguments provided!\n\nThis script requires two arguments to by provided:\nBinary URL & PGP Fingerprint\n\nPlease review documentation and try again.\n\nExiting now ...\n"
    exit 1
fi


## Update & Upgrade
apt update && apt upgrade -y


## Install wget
apt install -y wget


## Function to print messages in blue:
echo_blue() {
  echo -e "\033[1;34m$1\033[0m"
}


# Function to print error messages in red:
echo_red() {
  echo -e "\033[0;31m$1\033[0m"
}


## Sweep for old release files
rm *.asc desktop-*-SNAPSHOT-all.jar.SHA-256 haveno*


## Define URL & PGP Fingerprint etc. vars:
user_url=$1
base_url=$(printf ${user_url} | awk -F'/' -v OFS='/' '{$NF=""}1')
expected_fingerprint=$2
binary_filename=$(awk -F'/' '{ print $NF }' <<< "$user_url")
package_filename="haveno.deb"
signature_filename="${binary_filename}.sig"
key_filename="$(printf "$expected_fingerprint" | tr -d ' ' | sed -E 's/.*(................)/\1/' )".asc
wget_flags="--tries=10 --timeout=10 --waitretry=5 --retry-connrefused --show-progress"


## Debug:
printf "\nUser URL=$user_url\n"
printf "\nBase URL=$base_url\n"
printf "\nFingerprint=$expected_fingerprint\n"
printf "\nBinary Name=$binary_filename\n"
printf "\nPackage Name=$package_filename\n"
printf "\nSig Filename=$signature_filename\n"
printf "\nKey Filename=$key_filename\n"


## Configure for tinyproxy:
export https_proxy=http://127.0.0.1:8082


## Download Haveno binary:
echo_blue "Downloading Haveno from URL provided ..."
wget "${wget_flags}" -cq "${user_url}" || { echo_red "Failed to download Haveno binary."; exit 1; }


## Download Haveno signature file:
echo_blue "Downloading Haveno signature ..."
wget "${wget_flags}" -cq "${base_url}""${signature_filename}" || { echo_red "Failed to download Haveno signature."; exit 1; }


## Download the GPG key:
echo_blue "Downloading signing GPG key ..."
wget "${wget_flags}" -cqO "${key_filename}" "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$(echo "$expected_fingerprint" | tr -d ' ')" || { echo_red "Failed to download GPG key."; exit 1; }


## Import the GPG key:
echo_blue "Importing the GPG key ..."
gpg --import "${key_filename}" || { echo_red "Failed to import GPG key."; exit 1; }


## Extract imported fingerprints:
imported_fingerprints=$(gpg --with-colons --fingerprint | grep -A 1 'pub' | grep 'fpr' | cut -d: -f10 | tr -d '\n')


## Remove spaces from the expected fingerprint for comparison:
formatted_expected_fingerprint=$(echo "${expected_fingerprint}" | tr -d ' ')


## Check if the expected fingerprint is in the list of imported fingerprints:
if [[ ! "${imported_fingerprints}" =~ "${formatted_expected_fingerprint}" ]]; then
  echo_red "The imported GPG key fingerprint does not match the expected fingerprint."
  exit 1
fi


## Verify the downloaded binary with the signature:
echo_blue "Verifying the signature of the downloaded file ..."
OUTPUT=$(gpg --digest-algo SHA256 --verify "${signature_filename}" "${binary_filename}" 2>&1)

if ! echo "$OUTPUT" | grep -q "Good signature from"; then
    echo_red "Verification failed: $OUTPUT"
    exit 1;
    else 7z x "${binary_filename}" && mv haveno*.deb "${package_filename}"
fi


echo_blue "Haveno binaries have been successfully verified."


# Install Haveno:
echo_blue "Installing Haveno ..."
apt install -y ./"${package_filename}" || { echo_red "Failed to install Haveno."; exit 1; }


## Adjust permissions:
echo_blue "Adjust permissions ..."
chown -R $(ls /home):$(ls /home) /opt/haveno


## Finalize
echo_blue "Haveno TemplateVM installation and configuration complete."
printf "%s \n" "Press [ENTER] to complete ..."
read ans
exit
#poweroff

