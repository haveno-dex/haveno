# See docs/installing.md

build: nodes localnet haveno

clean:
	./gradlew clean

clean-localnet:
	rm -rf .localnet

localnet:
	mkdir -p .localnet

nodes: localnet
	./scripts/xmr_btc_deps.sh

haveno:
	./gradlew build

haveno-apps: # quick build desktop and daemon apps without tests, etc
	./gradlew :core:compileJava :desktop:build

deploy:
	# create a new screen session named 'localnet'
	screen -dmS localnet
	# deploy each node in its own named screen window
	for target in \
		seednode \
		alice-desktop \
		bob-desktop \
		arbitrator-desktop; do \
			screen -S localnet -X screen -t $$target; \
			screen -S localnet -p $$target -X stuff "make $$target\n"; \
		done;
	# give bitcoind rpc server time to start
	sleep 5

seednode:
	./haveno-seednode \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=2002 \
		--appName=haveno-XMR_STAGENET_Seed_2002 \

arbitrator-desktop:
	# Arbitrator and mediator need to be registerd in the UI after launching it.
	./haveno-desktop \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=4444 \
		--appName=haveno-XMR_STAGENET_arbitrator \
		--apiPassword=apitest \
		--apiPort=9998

alice-desktop:
	./haveno-desktop \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=5555 \
		--appName=haveno-XMR_STAGENET_Alice \
		--apiPassword=apitest \
		--apiPort=9999 \
		--walletRpcBindPort=38091

alice-daemon:
	./haveno-daemon \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=5555 \
		--appName=haveno-XMR_STAGENET_Alice \
		--apiPassword=apitest \
		--apiPort=9999 \
		--walletRpcBindPort=38091

bob-desktop:
	./haveno-desktop \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=6666 \
		--appName=haveno-XMR_STAGENET_Bob \
		--apiPassword=apitest \
		--apiPort=10000 \
		--walletRpcBindPort=38092

bob-daemon:
	./haveno-daemon \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=6666 \
		--appName=haveno-XMR_STAGENET_Bob \
		--apiPassword=apitest \
		--apiPort=10000 \
		--walletRpcBindPort=38092

monero-shared:
	./.localnet/monerod \
		--stagenet \
		--no-igd \
		--hide-my-port \
		--data-dir .localnet/stagenet \
		--add-exclusive-node 136.244.105.131:38080 \
		--rpc-login superuser:abctesting123 \
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 100

monero-private1:
	./.localnet/monerod \
		--stagenet \
		--no-igd \
		--hide-my-port \
		--data-dir .localnet/stagenet/node1 \
		--p2p-bind-ip 127.0.0.1 \
		--p2p-bind-port 48080 \
		--rpc-bind-port 48081 \
		--zmq-rpc-bind-port 48082 \
		--add-exclusive-node 127.0.0.1:38080 \
		--rpc-login superuser:abctesting123 \
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 100

monero-private2:
	./.localnet/monerod \
		--stagenet \
		--no-igd \
		--hide-my-port \
		--data-dir .localnet/stagenet/node2 \
		--p2p-bind-ip 127.0.0.1 \
		--rpc-bind-ip 0.0.0.0 \
		--confirm-external-bind \
		--add-exclusive-node 127.0.0.1:48080 \
		--rpc-login superuser:abctesting123 \
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 100

bitcoind:
	./.localnet/bitcoind \
		-regtest \
		-peerbloomfilters=1 \
		-datadir=.localnet/ \
		-rpcuser=haveno \
		-rpcpassword=1234

btc-blocks:
	./.localnet/bitcoin-cli \
		-regtest \
		-rpcuser=haveno \
		-rpcpassword=1234 \
		generatetoaddress 101 bcrt1q6j90vywv8x7eyevcnn2tn2wrlg3vsjlsvt46qz

.PHONY: build seednode localnet
