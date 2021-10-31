#!/bin/bash

cd $(dirname $0)/../../../

version="1.6.2"

# Set BISQ_DIR as environment var to the path of your locally synced Haveno data directory e.g. BISQ_DIR=~/Library/Application\ Support/Haveno

dbDir=$BISQ_DIR/btc_mainnet/db
resDir=p2p/src/main/resources

# Only commit new TradeStatistics3Store if you plan to add it to
# https://github.com/haveno-network/haveno/blob/0345c795e2c227d827a1f239a323dda1250f4e69/common/src/main/java/haveno/common/app/Version.java#L40 as well.
cp "$dbDir/TradeStatistics3Store" "$resDir/TradeStatistics3Store_${version}_BTC_MAINNET"
cp "$dbDir/AccountAgeWitnessStore" "$resDir/AccountAgeWitnessStore_${version}_BTC_MAINNET"
cp "$dbDir/DaoStateStore" "$resDir/DaoStateStore_BTC_MAINNET"
cp "$dbDir/SignedWitnessStore" "$resDir/SignedWitnessStore_BTC_MAINNET"
