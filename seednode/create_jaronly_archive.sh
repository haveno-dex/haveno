#!/bin/sh
set -x

./gradlew build -x test

tar zvcf haveno-seednode-jaronly.tgz  \
seednode/build/app/lib/assets.jar \
seednode/build/app/lib/common.jar \
seednode/build/app/lib/core.jar \
seednode/build/app/lib/p2p.jar \
seednode/build/app/lib/seednode.jar

ls -la haveno-seednode-jaronly.tgz

exit 0
