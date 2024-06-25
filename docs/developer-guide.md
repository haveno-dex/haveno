# Developer Guide

This document is a guide for Haveno development.

## Install and test Haveno

[Build Haveno and join the test network or test locally](installing.md).

## Run the UI proof of concept

Follow [instructions](https://github.com/haveno-dex/haveno-ts#run-in-a-browser) to run Haveno's UI proof of concept in a browser.

This proof of concept demonstrates using Haveno's gRPC server with a web frontend (react and typescript) instead of Haveno's JFX application.

## Import Haveno into development environment

VSCode is recommended.

Otherwise follow [instructions](import-haveno.md) to import Haveno into a Eclipse or IntelliJ IDEA.

## Run end-to-end API tests

Follow [instructions](https://github.com/haveno-dex/haveno-ts#run-tests) to run end-to-end API tests in the UI project.

## Add new API functions and tests

1. Follow [instructions](https://github.com/haveno-dex/haveno-ts#run-tests) to run Haveno's existing API tests successfully.
2. Define the new service or message in Haveno's [protobuf definition](../proto/src/main/proto/grpc.proto).
3. Clean and build Haveno after modifying the protobuf definition: `make clean && make`
4. Implement the new service in Haveno's backend, following existing patterns.<br>
   For example, the gRPC function to get offers is implemented by [`GrpcServer`](https://github.com/haveno-dex/haveno/blob/master/daemon/src/main/java/haveno/daemon/grpc/GrpcServer.java) > [`GrpcOffersService.getOffers(...)`](https://github.com/haveno-dex/haveno/blob/b761dbfd378faf49d95090c126318b419af7926b/daemon/src/main/java/haveno/daemon/grpc/GrpcOffersService.java#L104) > [`CoreApi.getOffers(...)`](https://github.com/haveno-dex/haveno/blob/b761dbfd378faf49d95090c126318b419af7926b/core/src/main/java/haveno/core/api/CoreApi.java#L128) > [`CoreOffersService.getOffers(...)`](https://github.com/haveno-dex/haveno/blob/b761dbfd378faf49d95090c126318b419af7926b/core/src/main/java/haveno/core/api/CoreOffersService.java#L126) > [`OfferBookService.getOffers()`](https://github.com/haveno-dex/haveno/blob/b761dbfd378faf49d95090c126318b419af7926b/core/src/main/java/haveno/core/offer/OfferBookService.java#L193).
5. Build Haveno: `make`
6. Update the gRPC client in haveno-ts: `npm install`
7. Add the corresponding typescript method(s) to [haveno.ts](https://github.com/haveno-dex/haveno-ts/blob/master/src/haveno.ts) with clear and concise documentation.
8. Add clean and comprehensive tests to [haveno.test.ts](https://github.com/haveno-dex/haveno-ts/blob/master/src/haveno.test.ts), following existing patterns.
9. Run the tests with `npm run test -- -t 'my test'` to run tests by name and `npm test` to run all tests together. Ensure all tests pass and there are no exception stacktraces in the terminals of Alice, Bob, or the arbitrator.
10. Open pull requests to the haveno and haveno-ts projects for the backend and frontend implementations.

## Release portable Monero binaries for each platform

1. Update the release-v0.18 branch on Haveno's [monero repo](https://github.com/haveno-dex/monero) to the latest release from upstream + any customizations (e.g. a commit to speed up testnet hardforks for local development).
2. `git tag <tag> && git push origin <tag>`
3. Follow instructions to [build portable binaries for each platform](#build-portable-monero-binaries-for-each-platform).
4. Publish a new release at https://github.com/haveno-dex/monero/releases with the updated binaries and hashes.
5. Update the paths and hashes in build.gradle and PR.

### Build portable Monero binaries for each platform

Based on these instructions: https://github.com/monero-project/monero#cross-compiling

> Note:
> If during building you get the prompt "Reversed (or previously applied) patch detected!  Assume -R? [n]" then confirm 'y'.

**Prepare Linux x86_64**

1. Install Ubuntu 20.04 on x86_64.
2. `sudo apt-get update && sudo apt-get upgrade`
3. Install monero dependencies: `sudo apt update && sudo apt install build-essential cmake pkg-config libssl-dev libzmq3-dev libsodium-dev libunwind8-dev liblzma-dev libreadline6-dev libpgm-dev qttools5-dev-tools libhidapi-dev libusb-1.0-0-dev libprotobuf-dev protobuf-compiler libudev-dev libboost-chrono-dev libboost-date-time-dev libboost-filesystem-dev libboost-locale-dev libboost-program-options-dev libboost-regex-dev libboost-serialization-dev libboost-system-dev libboost-thread-dev python3 ccache`
4. `sudo apt install cmake imagemagick libcap-dev librsvg2-bin libz-dev libbz2-dev libtiff-tools python-dev libtinfo5 autoconf libtool libtool-bin gperf git curl`
5. `git clone https://github.com/haveno-dex/monero.git`
6. `cd ./monero` (or rename to haveno-monero: `mv monero/ haveno-monero && cd ./haveno-monero`)
7. `git fetch origin && git reset --hard origin/release-v0.18`
8. `git submodule update --init --force`

**Build for Linux x86_64**

1. `make depends target=x86_64-linux-gnu -j<num cores>`
2. `cd build/x86_64-linux-gnu/release/bin/`
3. `tar -zcvf monero-bins-haveno-linux-x86_64.tar.gz monerod monero-wallet-rpc`
4. Save monero-bins-haveno-linux-x86_64.tar.gz for release.

**Build for Mac**

1. `make depends target=x86_64-apple-darwin11 -j<num cores>`
2. `cd build/x86_64-apple-darwin11/release/bin/`
3. `tar -zcvf monero-bins-haveno-mac.tar.gz monerod monero-wallet-rpc`
4. Save monero-bins-haveno-mac.tar.gz for release.

**Build for Windows**

1. `sudo apt install python3 g++-mingw-w64-x86-64 bc`
2. `sudo update-alternatives --set x86_64-w64-mingw32-g++ /usr/bin/x86_64-w64-mingw32-g++-posix && sudo update-alternatives --set x86_64-w64-mingw32-gcc /usr/bin/x86_64-w64-mingw32-gcc-posix`
3. `make depends target=x86_64-w64-mingw32 -j<num cores>`
4. `cd build/x86_64-w64-mingw32/release/bin/`
5. `zip monero-bins-haveno-windows.zip monerod.exe monero-wallet-rpc.exe`
6. Save monero-bins-haveno-windows.zip for release.

**Prepare Linux aarch64**

1. Install Ubuntu 20.04 on aarch64.
2. `sudo apt-get update && sudo apt-get upgrade`
3. Install monero dependencies: `sudo apt update && sudo apt install build-essential cmake pkg-config libssl-dev libzmq3-dev libsodium-dev libunwind8-dev liblzma-dev libreadline6-dev libpgm-dev qttools5-dev-tools libhidapi-dev libusb-1.0-0-dev libprotobuf-dev protobuf-compiler libudev-dev libboost-chrono-dev libboost-date-time-dev libboost-filesystem-dev libboost-locale-dev libboost-program-options-dev libboost-regex-dev libboost-serialization-dev libboost-system-dev libboost-thread-dev python3 ccache`
4. `sudo apt install cmake imagemagick libcap-dev librsvg2-bin libz-dev libbz2-dev libtiff-tools python-dev libtinfo5 autoconf libtool libtool-bin gperf git curl`
5. `git clone https://github.com/haveno-dex/monero.git`
6. `cd ./monero` (or rename to haveno-monero: `mv monero/ haveno-monero && cd ./haveno-monero`)
7. `git fetch origin && git reset --hard origin/release-v0.18`
8. `git submodule update --init --force`

**Build for Linux aarch64**

1. `make depends target=aarch64-linux-gnu -j<num cores>`
2. `cd build/aarch64-linux-gnu/release/bin/`
3. `tar -zcvf monero-bins-haveno-linux-aarch64.tar.gz monerod monero-wallet-rpc`
4. Save monero-bins-haveno-linux-aarch64.tar.gz for release.

## Build executable installers for each platform

See [instructions](/desktop/package/README.md).

## Rebase and squash your commits

When submitting a pull request for review, please first rebase and squash your commits.

1. Checkout the latest version from master, e.g.: `git checkout master && git pull upstream master`
2. Checkout your feature branch, e.g.: `git checkout your_branch`
3. Optionally make a backup branch just in case something goes wrong, e.g.: `git checkout -b your_branch_bkp && git checkout your_branch`
4. Rebase on master: `git rebase master`
5. Squash your commits: `git reset --soft <last hash before your first commit>`
6. Commit your changes to a single commit: `git commit`
7. Push your local branch to your remote repository: `git push --force`

If you have a PR open on that branch, it'll be updated automatically.

## Trade Protocol

For documentation of the trade protocol, see [trade protocol](trade_protocol/trade-protocol.pdf).