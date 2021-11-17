#!/bin/bash

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
        shasum -a 256 -c <<<'94e8736bda6bd50120b6ce9fdbf0f3b6ae8961cc9e0a546e165f911e51b1afa9 *monero-bins-haveno-'"${platform}"'.tar.gz' || exit 1
    else
        echo "ec46095e55d981304470d5f6a539aecdd64c912d6b2fe5195536b40e4d0e0015 monero-bins-haveno-${platform}.tar.gz" | sha256sum -c || exit 1
    fi

    echo "-> Monero binaries downloaded and verified"
}

# Verify hashes of bitcoind and bitcoin-cli
check_bitcoin() {
    if is_mac; then
        shasum -a 256 -c <<<'1ea5cedb64318e9868a66d3ab65de14516f9ada53143e460d50af428b5aec3c7 *bitcoin-'"${btcversion}"'-'"${btc_platform}"'.tar.gz' || exit 1
    else
        echo "366eb44a7a0aa5bd342deea215ec19a184a11f2ca22220304ebb20b9c8917e2b bitcoin-${btcversion}-${btc_platform}.tar.gz" | sha256sum -c || exit 1
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
        dw_source https://github.com/haveno-dex/monero/releases/download/testing/monero-bins-haveno-${platform}.tar.gz || { echo "! something went wrong while downloading the Monero binaries. Exiting...";  exit 1; } && \
        check_monero
    fi

    tar -xzf monero-bins-haveno-${platform}.tar.gz --strip-components 1 && \
    chmod +x {monerod,monero-wallet-rpc} || exit 1
}

# Download Bitcoin bins
dw_bitcoin() {
    btcversion=0.21.1

    if is_mac; then
        btc_platform="osx64"
    else
        btc_platform="x86_64-linux-gnu"
    fi

    if [ -f bitcoin-${btcversion}-${btc_platform}.tar.gz ]; then
        check_bitcoin
    else
        dw_source https://bitcoin.org/bin/bitcoin-core-${btcversion}/bitcoin-${btcversion}-${btc_platform}.tar.gz || { echo "! something went wrong while downloading the Bitcoin binaries. Exiting..."; exit 1; } && \
        check_bitcoin
    fi

    tar -xzf bitcoin-${btcversion}-${btc_platform}.tar.gz && \
    cp bitcoin-${btcversion}/bin/{bitcoin-cli,bitcoind} . && \
    rm -r bitcoin-${btcversion} || exit 1
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
