#!/bin/bash


# This script facilitates the setup and installation of the Haveno application on Tails OS.
#
# FUNCTIONAL OVERVIEW:
# - Creating necessary persistent directories and copying utility files.
# - Downloading Haveno binary, signature file, and GPG key for verification.
# - Importing and verifying the GPG key to ensure the authenticity of the download.
# - Setting up desktop icons in both local and persistent directories.


# Function to print messages in blue
echo_blue() {
  echo -e "\033[1;34m$1\033[0m"
}


# Function to print error messages in red
echo_red() {
  echo -e "\033[0;31m$1\033[0m"
}


# Define version and file locations
user_url=$1
base_url=$(printf ${user_url} | awk -F'/' -v OFS='/' '{$NF=""}1')
expected_fingerprint=$2
binary_filename=$(awk -F'/' '{ print $NF }' <<< "$user_url")
package_filename="haveno.deb"
signature_filename="${binary_filename}.sig"
key_filename="$(printf "$expected_fingerprint" | tr -d ' ' | sed -E 's/.*(................)/\1/' )".asc
assets_dir="/tmp/assets"
persistence_dir="/home/amnesia/Persistent"
app_dir="${persistence_dir}/haveno/App"
data_dir="${persistence_dir}/haveno/Data"
install_dir="${persistence_dir}/haveno/Install"
dotfiles_dir="/live/persistence/TailsData_unlocked/dotfiles"
persistent_desktop_dir="$dotfiles_dir/.local/share/applications"
local_desktop_dir="/home/amnesia/.local/share/applications"
wget_flags="--tries=10 --timeout=10 --waitretry=5 --retry-connrefused --show-progress"


# Create temp location for downloads
echo_blue "Creating temporary directory for Haveno resources ..."
mkdir -p "${assets_dir}" || { echo_red "Failed to create directory ${assets_dir}"; exit 1; }


# Download resources
echo_blue "Downloading resources for Haveno on Tails ..."
wget "${wget_flags}" -cqP "${assets_dir}" https://github.com/haveno-dex/haveno/raw/master/scripts/install_tails/assets/exec.sh || { echo_red "Failed to download resource (exec.sh)."; exit 1; }
wget "${wget_flags}" -cqP "${assets_dir}" https://github.com/haveno-dex/haveno/raw/master/scripts/install_tails/assets/install.sh || { echo_red "Failed to download resource (install.sh)."; exit 1; }
wget "${wget_flags}" -cqP "${assets_dir}" https://github.com/haveno-dex/haveno/raw/master/scripts/install_tails/assets/haveno.desktop || { echo_red "Failed to resource (haveno.desktop)."; exit 1; }
wget "${wget_flags}" -cqP "${assets_dir}" https://raw.githubusercontent.com/haveno-dex/haveno/master/scripts/install_tails/assets/icon.png || { echo_red "Failed to download resource (icon.png)."; exit 1; }
wget "${wget_flags}" -cqP "${assets_dir}" https://github.com/haveno-dex/haveno/raw/master/scripts/install_tails/assets/haveno.yml || { echo_red "Failed to download resource (haveno.yml)."; exit 1; }


# Create persistent directory
echo_blue "Creating persistent directory for Haveno ..."
mkdir -p "${app_dir}" || { echo_red "Failed to create directory ${app_dir}"; exit 1; }


# Copy utility files to persistent storage and make scripts executable
echo_blue "Copying haveno utility files to persistent storage ..."
rsync -av "${assets_dir}/" "${app_dir}/utils/" || { echo_red "Failed to rsync files to ${app_dir}/utils/"; exit 1; }
find "${app_dir}/utils/" -type f -name "*.sh" -exec chmod +x {} \; || { echo_red "Failed to make scripts executable"; exit 1; }


echo_blue "Creating desktop menu icon ..."
# Create desktop directories
mkdir -p "${local_desktop_dir}"
mkdir -p "${persistent_desktop_dir}"


# Copy .desktop file to persistent directory
cp "${assets_dir}/haveno.desktop" "${persistent_desktop_dir}"  || { echo_red "Failed to copy .desktop file to persistent directory $persistent_desktop_dir"; exit 1; }


# Create a symbolic link to it in the local .desktop directory, if it doesn't exist
if [ ! -L "${local_desktop_dir}/haveno.desktop" ]; then
    ln -s "${persistent_desktop_dir}/haveno.desktop" "${local_desktop_dir}/haveno.desktop" || { echo_red "Failed to create symbolic link for .desktop file"; exit 1; }
fi


# Download Haveno binary
echo_blue "Downloading Haveno from URL provided ..."
wget "${wget_flags}" -cq "${user_url}" || { echo_red "Failed to download Haveno binary."; exit 1; }


# Download Haveno signature file
echo_blue "Downloading Haveno signature ..."
wget "${wget_flags}" -cq "${base_url}""${signature_filename}" || { echo_red "Failed to download Haveno signature."; exit 1; }


# Download the GPG key
echo_blue "Downloading signing GPG key ..."
wget "${wget_flags}" -cqO "${key_filename}" "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$(echo "$expected_fingerprint" | tr -d ' ')" || { echo_red "Failed to download GPG key."; exit 1; }


# Import the GPG key
echo_blue "Importing the GPG key ..."
gpg --import "${key_filename}" || { echo_red "Failed to import GPG key."; exit 1; }


# Extract imported fingerprints
imported_fingerprints=$(gpg --with-colons --fingerprint | grep -A 1 'pub' | grep 'fpr' | cut -d: -f10 | tr -d '\n')


# Remove spaces from the expected fingerprint for comparison
formatted_expected_fingerprint=$(echo "${expected_fingerprint}" | tr -d ' ')


# Check if the expected fingerprint is in the list of imported fingerprints
if [[ ! "${imported_fingerprints}" =~ "${formatted_expected_fingerprint}" ]]; then
  echo_red "The imported GPG key fingerprint does not match the expected fingerprint."
  exit 1
fi


# Verify the downloaded binary with the signature
echo_blue "Verifying the signature of the downloaded file ..."
OUTPUT=$(gpg --digest-algo SHA256 --verify "${signature_filename}" "${binary_filename}" 2>&1)

if ! echo "$OUTPUT" | grep -q "Good signature from"; then
    echo_red "Verification failed: $OUTPUT"
    exit 1;
    else mv -f "${binary_filename}" "${package_filename}"
fi

echo_blue "Haveno binaries have been successfully verified."


# Move the binary and its signature to the persistent directory
mkdir -p "${install_dir}"


# Delete old Haveno binaries
#rm -f "${install_dir}/"*.deb*
mv "${package_filename}" "${key_filename}" "${signature_filename}" "${install_dir}"
echo_blue "Files moved to persistent directory ${install_dir}"


# Remove stale resources
rm -rf "${assets_dir}"


# Completed confirmation
echo_blue "Haveno installation setup completed successfully."
