#!/bin/bash

# Hashes and tag of our Monero testing binaries at https://github.com/haveno-dex/monero/releases
MONERO_HASH_MAC="a3c55350ee02ec3609b0752a4d8cae7a9664bfebd98330b720882ad70eaa83af"
MONERO_HASH_LINUX="ba1e4145ec16b9acc4c1bc51e932bd2dc5466f7878c5a72dbf7030c7246e4da9"
MONERO_TAG="testing5"
# Hashes and version of bitcoin core: https://bitcoin.org/bin/
BTC_HASH_MAC="1ea5cedb64318e9868a66d3ab65de14516f9ada53143e460d50af428b5aec3c7"
BTC_HASH_LINUX="366eb44a7a0aa5bd342deea215ec19a184a11f2ca22220304ebb20b9c8917e2b"
BTC_VERSION=0.21.1

is_mac() {
    if [[ "$OSTYPE" == "darwin"* ]]; then
        return 0
    else
        return 1
    fi
}

is_linux() {
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        return 0
    else
        return 1
    fi
}

dw_source() {
    if command -v wget &> /dev/null; then
        downloader="wget"
    elif command -v curl &> /dev/null; then
        downloader="curl -L -O"
    else
        echo "! curl or wget are not installed. Please install one of the two"
        exit 1
    fi

    ${downloader} "$1"
}

# Verify Monero hash
check_monero() {
    if is_mac; then
        shasum -a 256 -c <<< ''"${MONERO_HASH_MAC}"' *monero-bins-haveno-'"${platform}"'.tar.gz' || exit 1
    else
        echo "${MONERO_HASH_LINUX} monero-bins-haveno-${platform}.tar.gz" | sha256sum -c || exit 1
    fi

    echo "-> Monero binaries downloaded and verified"
}

# Verify hashes of bitcoind and bitcoin-cli
check_bitcoin() {
    if is_mac; then
        shasum -a 256 -c <<< ''"${BTC_HASH_MAC}"' *bitcoin-'"${BTC_VERSION}"'-'"${btc_platform}"'.tar.gz' || exit 1
    else
        echo "${BTC_HASH_LINUX} bitcoin-${BTC_VERSION}-${btc_platform}.tar.gz" | sha256sum -c || exit 1
    fi

    echo "-> Bitcoin binaries downloaded and verified"
}

# Download Monero bins
dw_monero() {
    if is_mac; then
        platform="mac"
    else
        platform="linux"
    fi

    if [ -f monero-bins-haveno-${platform}.tar.gz ]; then
        check_monero
    else
        dw_source https://github.com/haveno-dex/monero/releases/download/${MONERO_TAG}/monero-bins-haveno-${platform}.tar.gz || { echo "! something went wrong while downloading the Monero binaries. Exiting...";  exit 1; } && \
        check_monero
    fi

    tar -xzf monero-bins-haveno-${platform}.tar.gz && \
    chmod +x {monerod,monero-wallet-rpc} || exit 1
}

# Download Bitcoin bins
dw_bitcoin() {
    if is_mac; then
        btc_platform="osx64"
    else
        btc_platform="x86_64-linux-gnu"
    fi

    if [ -f bitcoin-${BTC_VERSION}-${btc_platform}.tar.gz ]; then
        check_bitcoin
    else
        dw_source https://bitcoin.org/bin/bitcoin-core-${BTC_VERSION}/bitcoin-${BTC_VERSION}-${btc_platform}.tar.gz || { echo "! something went wrong while downloading the Bitcoin binaries. Exiting..."; exit 1; } && \
        check_bitcoin
    fi

    tar -xzf bitcoin-${BTC_VERSION}-${btc_platform}.tar.gz && \
    cp bitcoin-${BTC_VERSION}/bin/{bitcoin-cli,bitcoind} . && \
    rm -r bitcoin-${BTC_VERSION} || exit 1
}


while true; do
    cd .localnet

    if ! is_linux && ! is_mac; then
        bins_deps=("monerod" "monero-wallet-rpc") # "bitcoind" "bitcoin-cli"

        for i in ${bins_deps[@]}; do
            [ -f "$i" ] || { echo "${i} not found."; echo "Dependencies are installed automatically only on Linux and Mac. Please manually install bitcoind, bitcoin-cli, monerod, and monero-wallet-rpc executables into haveno/.localnet/ before running make."; exit 1; }
        done
        exit 0
    fi

    dw_monero
    # dw_bitcoin
    exit 0
done
