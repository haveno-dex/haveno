# Developer Guide

This document is a guide for Haveno development.

## Installing and testing Haveno

[Build Haveno and join the test network or test locally](installing.md).

## Running the UI proof of concept

Follow [instructions](https://github.com/haveno-dex/haveno-ts#run-in-a-browser) to run Haveno's UI proof of concept in a browser.

This proof of concept demonstrates using Haveno's gRPC server with a web frontend (react and typescript) instead of Haveno's JFX application.

## Importing Haveno into development environment

Follow [instructions](import-haveno.md) to import Haveno into a development environment.

## Running end-to-end API tests

Follow [instructions](https://github.com/haveno-dex/haveno-ts#run-tests) to run end-to-end API tests in the UI project.

## Adding new API functions and tests

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

## How to manually sign accounts as the arbitrator

1. Open legacy UI as the arbitrator.
2. Go to the 'Account' tab.
3. Open Signing tab: `ctrl+i`
    a. Sign payment account: `ctrl+s`, select payment accounts to sign (sourced from disputes).
    b. Sign account age witness: `ctrl+p` then enter <witness hash>,<pub key hash> (from past trade details) and click the "Import unsigned account age witness" button.
    c. Sign unsigned witness pub keys: `ctrl+o`

## How to rebase and squash your commits

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