# See docs/installing.md

build: nodes localnet build-haveno  

clean: clean-localnet clean-build 

clean-build:
	./gradlew clean

clean-localnet:
	rm -rf .localnet

localnet:
	mkdir -p .localnet 

nodes: localnet
	./scripts/xmr-btc_deps.sh

build-haveno:
	./gradlew build

deploy:
# create a new screen session named 'localnet'
	screen -dmS localnet
  # deploy each node in its own named screen window
	for target in \
	  seednode \
    alice \
    bob \
    arbitrator; do \
  	  screen -S localnet -X screen -t $$target; \
      screen -S localnet -p $$target -X stuff "make $$target\n"; \
    done;
  # give bitcoind rpc server time to start
	sleep 5

seednode: build-haveno
	./haveno-seednode \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=2002 \
		--appName=haveno-XMR_STAGENET_Seed_2002 \
		--daoActivated=false

arbitrator: build-haveno
  # Arbitrator and mediator need to be registerd in the UI after launching it.
	./haveno-desktop \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=4444 \
		--appName=haveno-XMR_STAGENET_arbitrator \
		--daoActivated=false \
		--apiPassword=apitest \
		--apiPort=9998

alice: build-haveno
	./haveno-desktop \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=5555 \
		--appName=haveno-XMR_STAGENET_Alice \
		--daoActivated=false \
		--apiPassword=apitest \
		--apiPort=9999

bob: build-haveno
	./haveno-desktop \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=6666 \
		--appName=haveno-XMR_STAGENET_Bob \
		--daoActivated=false \
		--apiPassword=apitest \
		--apiPort=10000

monero-shared:
	./.localnet/monero-bins-haveno-linux/monerod \
		--stagenet \
		--no-igd \
		--hide-my-port \
		--data-dir .localnet/stagenet \
		--add-exclusive-node 136.244.105.131:38080 \
		--rpc-login superuser:abctesting123 \
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 10

monero-private1:
	./.localnet/monero-bins-haveno-linux/monerod \
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
		--fixed-difficulty 10

monero-private2:
	./.localnet/monero-bins-haveno-linux/monerod \
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
		--fixed-difficulty 10

bitcoind:
	./.localnet/bitcoind \
		-regtest \
		-peerbloomfilters=1 \
		-datadir=.localnet/bitcoin-node \
		-rpcuser=haveno \
		-rpcpassword=1234

btc-blocks:
	./.localnet/bitcoin-cli \
		-regtest \
		-rpcuser=haveno \
		-rpcpassword=1234 \
		generatetoaddress 101 bcrt1q6j90vywv8x7eyevcnn2tn2wrlg3vsjlsvt46qz

.PHONY: build seednode localnet
