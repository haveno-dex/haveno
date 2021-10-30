# Developer Guide

This document is a guide for Haveno development.

## Installing Haveno

In order to develop for Haveno, first [install and run a local Haveno test network](installing.md).

## Running the UI proof of concept

Follow [instructions](https://github.com/haveno-dex/haveno-ui-poc#run-in-a-browser) to run Haveno's UI proof of concept in a browser.

This proof of concept demonstrates using Haveno's gRPC server with a web frontend (react and typescript) instead of Bisq's JFX application.

## Importing Haveno into development environment

Follow [instructions](import-haveno.md) to import Haveno into a development environment.

## Running end-to-end API tests

Follow [instructions](https://github.com/haveno-dex/haveno-ui-poc#run-tests) to run end-to-end API tests in the UI project.

## Adding new API functions and tests

1. Follow [instructions](https://github.com/haveno-dex/haveno-ui-poc#run-tests) to run Haveno's existing API tests successfully.
2. Define the new service or message in Haveno's [protobuf definition](../proto/src/main/proto/grpc.proto).
3. Clean and build Haveno after modifying the protobuf definition: `make clean && make`
4. Implement the new service in Haveno's backend, following existing patterns.<br>
   For example, the gRPC function to get offers is implemented by [`GrpcServer`](https://github.com/haveno-dex/haveno/blob/master/daemon/src/main/java/bisq/daemon/grpc/GrpcServer.java) > [`GrpcOffersService.getOffers(...)`](https://github.com/haveno-dex/haveno/blob/b761dbfd378faf49d95090c126318b419af7926b/daemon/src/main/java/bisq/daemon/grpc/GrpcOffersService.java#L104) > [`CoreApi.getOffers(...)`](https://github.com/haveno-dex/haveno/blob/b761dbfd378faf49d95090c126318b419af7926b/core/src/main/java/bisq/core/api/CoreApi.java#L128) > [`CoreOffersService.getOffers(...)`](https://github.com/haveno-dex/haveno/blob/b761dbfd378faf49d95090c126318b419af7926b/core/src/main/java/bisq/core/api/CoreOffersService.java#L126) > [`OfferBookService.getOffers()`](https://github.com/haveno-dex/haveno/blob/b761dbfd378faf49d95090c126318b419af7926b/core/src/main/java/bisq/core/offer/OfferBookService.java#L193).
5. Build Haveno: `make`
6. Follow [instructions](https://github.com/haveno-dex/haveno-ui-poc#how-to-update-the-protobuf-client) to update the protobuf client in haveno-ui-poc.
7. Add the corresponding typescript method(s) to [HavenoDaemon.tsx](https://github.com/haveno-dex/haveno-ui-poc/blob/master/src/HavenoDaemon.tsx) with clear and concise documentation.
8. Add clean and comprehensive tests to [HavenoDaemon.test.tsx](https://github.com/haveno-dex/haveno-ui-poc/blob/master/src/HavenoDaemon.test.tsx), following existing patterns.
9. Verify the tests with `npm run test -- -t 'my test'` to run tests by name and `npm test` to run all tests together.
10. Open a pull request for review.

## Trade Protocol

For documentation of the trade protocol, see [trade protocol](trade_protocol/trade-protocol.pdf).