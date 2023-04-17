# See docs/installing.md

build: localnet haveno

clean:
	./gradlew clean

clean-localnet:
	rm -rf .localnet

localnet:
	mkdir -p .localnet

haveno:
	./gradlew build

# build haveno without tests
skip-tests: localnet
	./gradlew build -x test -x checkstyleMain -x checkstyleTest

# quick build desktop and daemon apps without tests
haveno-apps:
	./gradlew :core:compileJava :desktop:build -x test -x checkstyleMain -x checkstyleTest

refresh-deps:
	./gradlew --write-verification-metadata sha256 && ./gradlew build --refresh-keys --refresh-dependencies -x test -x checkstyleMain -x checkstyleTest

deploy:
	# create a new screen session named 'localnet'
	screen -dmS localnet
	# deploy each node in its own named screen window
	for target in \
		seednode-local \
		user1-desktop-local \
		user2-desktop-local \
		arbitrator-desktop-local; do \
			screen -S localnet -X screen -t $$target; \
			screen -S localnet -p $$target -X stuff "make $$target\n"; \
		done;
	# give bitcoind rpc server time to start
	sleep 5

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

# Local network

monerod-local1:
	./.localnet/monerod \
		--testnet \
		--no-igd \
		--hide-my-port \
		--data-dir .localnet/xmr_local/node1 \
		--p2p-bind-ip 127.0.0.1 \
		--p2p-bind-port 48080 \
		--rpc-bind-port 48081 \
		--no-zmq \
		--add-exclusive-node 127.0.0.1:28080 \
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 400

monerod-local2:
	./.localnet/monerod \
		--testnet \
		--no-igd \
		--hide-my-port \
		--data-dir .localnet/xmr_local/node2 \
		--p2p-bind-ip 127.0.0.1 \
		--rpc-bind-ip 0.0.0.0 \
		--no-zmq \
		--confirm-external-bind \
		--add-exclusive-node 127.0.0.1:48080 \
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 400

funding-wallet-stagenet:
	./.localnet/monero-wallet-rpc \
		--rpc-bind-port 18084 \
		--rpc-login rpc_user:abc123 \
		--rpc-access-control-origins http://localhost:8080 \
		--wallet-dir ./.localnet \
		--daemon-ssl-allow-any-cert \
		--daemon-address http://127.0.0.1:38081

#--proxy 127.0.0.1:49775 \

funding-wallet-local:
	./.localnet/monero-wallet-rpc \
		--testnet \
		--daemon-address http://localhost:28081 \
		--rpc-bind-port 28084 \
		--rpc-login rpc_user:abc123 \
		--rpc-access-control-origins http://localhost:8080 \
		--wallet-dir ./.localnet

# use .bat extension for windows binaries
APP_EXT :=
ifeq ($(OS),Windows_NT)
	APP_EXT := .bat
endif

seednode-local:
	./haveno-seednode$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=2002 \
		--appName=haveno-XMR_LOCAL_Seed_2002 \
		--xmrNode=http://localhost:28081 \

arbitrator-daemon-local:
	# Arbitrator needs to be registered before making trades
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=4444 \
		--appName=haveno-XMR_LOCAL_arbitrator \
		--apiPassword=apitest \
		--apiPort=9998 \
		--passwordRequired=false

arbitrator-desktop-local:
	# Arbitrator needs to be registered before making trades
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=4444 \
		--appName=haveno-XMR_LOCAL_arbitrator \
		--apiPassword=apitest \
		--apiPort=9998

arbitrator-desktop2-local:
	# Arbitrator needs to be registered before making trades
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=7777 \
		--appName=haveno-XMR_LOCAL_arbitrator2 \
		--apiPassword=apitest \
		--apiPort=10001

user1-daemon-local:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=5555 \
		--appName=haveno-XMR_LOCAL_user1 \
		--apiPassword=apitest \
		--apiPort=9999 \
		--walletRpcBindPort=38091 \
		--passwordRequired=false

user1-desktop-local:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=5555 \
		--appName=haveno-XMR_LOCAL_user1 \
		--apiPassword=apitest \
		--apiPort=9999 \
		--walletRpcBindPort=38091

user2-desktop-local:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=6666 \
		--appName=haveno-XMR_LOCAL_user2 \
		--apiPassword=apitest \
		--apiPort=10000 \
		--walletRpcBindPort=38092

user2-daemon-local:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=6666 \
		--appName=haveno-XMR_LOCAL_user2 \
		--apiPassword=apitest \
		--apiPort=10000 \
		--walletRpcBindPort=38092 \
		--passwordRequired=false

# Stagenet network

monerod-stagenet:
	./.localnet/monerod \
		--stagenet \
		--bootstrap-daemon-address auto \
		--rpc-access-control-origins http://localhost:8080 \

monerod-stagenet-custom:
	./.localnet/monerod \
		--stagenet \
		--no-zmq \
		--p2p-bind-port 39080 \
		--rpc-bind-port 39081 \
		--bootstrap-daemon-address auto \
		--rpc-access-control-origins http://localhost:8080 \

seednode-stagenet:
	./haveno-seednode$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=2002 \
		--appName=haveno-XMR_STAGENET_Seed_2002 \

arbitrator-daemon-stagenet:
	# Arbitrator needs to be registered before making trades
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=4444 \
		--appName=haveno-XMR_STAGENET_arbitrator \
		--apiPassword=apitest \
		--apiPort=9998 \
		--passwordRequired=false \
		--xmrNode=http://127.0.0.1:38081

# Arbitrator needs to be registered before making trades
arbitrator-desktop-stagenet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=4444 \
		--appName=haveno-XMR_STAGENET_arbitrator \
		--apiPassword=apitest \
		--apiPort=9998 \
		--xmrNode=http://127.0.0.1:38081

user1-daemon-stagenet:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=5555 \
		--appName=haveno-XMR_STAGENET_user1 \
		--apiPassword=apitest \
		--apiPort=9999 \
		--walletRpcBindPort=38091 \
		--passwordRequired=false

user1-desktop-stagenet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=5555 \
		--appName=haveno-XMR_STAGENET_user1 \
		--apiPassword=apitest \
		--apiPort=9999 \
		--walletRpcBindPort=38091

user2-daemon-stagenet:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=6666 \
		--appName=haveno-XMR_STAGENET_user2 \
		--apiPassword=apitest \
		--apiPort=10000 \
		--walletRpcBindPort=38092 \
		--passwordRequired=false

user2-desktop-stagenet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=6666 \
		--appName=haveno-XMR_STAGENET_user2 \
		--apiPassword=apitest \
		--apiPort=10000 \
		--walletRpcBindPort=38092

user3-desktop-stagenet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=8888 \
		--appName=haveno-XMR_STAGENET_user3 \
		--apiPassword=apitest \
		--apiPort=10002 \
		--walletRpcBindPort=38093