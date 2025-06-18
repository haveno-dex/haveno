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

update-dependencies:
	./gradlew --refresh-dependencies && ./gradlew --write-verification-metadata sha256

# build haveno without tests
skip-tests: localnet
	./gradlew build -x test -x checkstyleMain -x checkstyleTest

# quick build desktop and daemon apps without tests
haveno-apps:
	./gradlew :core:compileJava :desktop:build -x test -x checkstyleMain -x checkstyleTest

refresh-deps:
	./gradlew --write-verification-metadata sha256 && ./gradlew build --refresh-keys --refresh-dependencies -x test -x checkstyleMain -x checkstyleTest

deploy-screen:
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
	# give time to start
	sleep 5

deploy-tmux:
	# Start a new tmux session named 'localnet' (detached)
	tmux new-session -d -s localnet -n main "make seednode-local"
	# Split the window into panes and run each node in its own pane
	tmux split-window -h -t localnet "make user1-desktop-local"  # Split horizontally for user1
	tmux split-window -v -t localnet:0.0 "make user2-desktop-local"  # Split vertically on the left for user2
	tmux split-window -v -t localnet:0.1 "make arbitrator-desktop-local"  # Split vertically on the right for arbitrator
	tmux select-layout -t localnet tiled
	# give time to start
	sleep 5
	# Attach to the tmux session
	tmux attach-session -t localnet

.PHONY: build seednode localnet

# Local network

monerod1-local:
	./.localnet/monerod \
		--testnet \
		--no-igd \
		--hide-my-port \
		--data-dir .localnet/xmr_local/node1 \
		--p2p-bind-ip 127.0.0.1 \
		--log-level 0 \
		--add-exclusive-node 127.0.0.1:48080 \
		--add-exclusive-node 127.0.0.1:58080 \
		--max-connections-per-ip 10 \
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 500 \
		--disable-rpc-ban \
		--rpc-max-connections-per-private-ip 100 \

monerod2-local:
	./.localnet/monerod \
		--testnet \
		--no-igd \
		--hide-my-port \
		--data-dir .localnet/xmr_local/node2 \
		--p2p-bind-ip 127.0.0.1 \
		--p2p-bind-port 48080 \
		--rpc-bind-port 48081 \
		--zmq-rpc-bind-port 48082 \
		--log-level 0 \
		--confirm-external-bind \
		--add-exclusive-node 127.0.0.1:28080 \
		--add-exclusive-node 127.0.0.1:58080 \
		--max-connections-per-ip 10 \
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 500 \
		--disable-rpc-ban \
		--rpc-max-connections-per-private-ip 100 \

monerod3-local:
	./.localnet/monerod \
		--testnet \
		--no-igd \
		--hide-my-port \
		--data-dir .localnet/xmr_local/node3 \
		--p2p-bind-ip 127.0.0.1 \
		--p2p-bind-port 58080 \
		--rpc-bind-port 58081 \
		--zmq-rpc-bind-port 58082 \
		--log-level 0 \
		--confirm-external-bind \
		--add-exclusive-node 127.0.0.1:28080 \
		--add-exclusive-node 127.0.0.1:48080 \
		--max-connections-per-ip 10 \
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 500 \
		--disable-rpc-ban \
		--rpc-max-connections-per-private-ip 100 \

#--proxy 127.0.0.1:49775 \

funding-wallet-local:
	./.localnet/monero-wallet-rpc \
		--testnet \
		--daemon-address http://localhost:28081 \
		--rpc-bind-port 28084 \
		--rpc-login rpc_user:abc123 \
		--rpc-access-control-origins http://localhost:8080 \
		--wallet-dir ./.localnet \

funding-wallet-stagenet:
	./.localnet/monero-wallet-rpc \
		--stagenet \
		--rpc-bind-port 38084 \
		--rpc-login rpc_user:abc123 \
		--rpc-access-control-origins http://localhost:8080 \
		--wallet-dir ./.localnet \
		--daemon-ssl-allow-any-cert \
		--daemon-address http://127.0.0.1:38081 \

funding-wallet-mainnet:
	./.localnet/monero-wallet-rpc \
		--rpc-bind-port 18084 \
		--rpc-login rpc_user:abc123 \
		--rpc-access-control-origins http://localhost:8080 \
		--wallet-dir ./.localnet \

# use .bat extension for windows binaries
APP_EXT :=
ifeq ($(OS),Windows_NT)
	APP_EXT := .bat
endif

seednode-local: NODE_PORT=2002
seednode-local: seednode-local-real

seednode2-local: NODE_PORT=2003
seednode2-local: seednode-local-real

seednode-local-real:
	./haveno-seednode$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=$(NODE_PORT) \
		--appName=haveno-XMR_LOCAL_Seed_$(NODE_PORT) \
		--xmrNode=http://localhost:28081 \

# Arbitrator needs to be registered before making trades
arbitrator-daemon-local: NODE_PORT=4444
arbitrator-daemon-local: APP_NAME=haveno-XMR_LOCAL_arbitrator
arbitrator-daemon-local: API_PORT=9998
arbitrator-daemon-local: APPEND=--passwordRequired=false
arbitrator-daemon-local: arbitrator-daemon-local-real

# Arbitrator needs to be registered before making trades
arbitrator-desktop-local: NODE_PORT=4444
arbitrator-desktop-local: APP_NAME=haveno-XMR_LOCAL_arbitrator
arbitrator-desktop-local: API_PORT=9998
arbitrator-desktop-local: arbitrator-desktop-local-real

# Arbitrator needs to be registered before making trades
arbitrator2-daemon-local: NODE_PORT=7777
arbitrator2-daemon-local: APP_NAME=haveno-XMR_LOCAL_arbitrator2
arbitrator2-daemon-local: API_PORT=10001
arbitrator2-daemon-local: arbitrator-daemon-local-real

# Arbitrator needs to be registered before making trades
arbitrator2-desktop-local: NODE_PORT=7777
arbitrator2-desktop-local: APP_NAME=haveno-XMR_LOCAL_arbitrator2
arbitrator2-desktop-local: API_PORT=10001
arbitrator2-desktop-local: arbitrator-desktop-local-real

user1-daemon-local:       NODE_PORT=5555
user1-daemon-local:       APP_NAME=haveno-XMR_LOCAL_user1
user1-daemon-local:       API_PORT=9999
user1-daemon-local:       APPEND=--walletRpcBindPort=38091 --passwordRequired=false
user1-daemon-local:       arbitrator-daemon-local-real

user1-desktop-local:       NODE_PORT=5555
user1-desktop-local:       APP_NAME=haveno-XMR_LOCAL_user1
user1-desktop-local:       API_PORT=9999
user1-desktop-local:       APPEND=--walletRpcBindPort=38091 --logLevel=info
user1-desktop-local:       arbitrator-desktop-local-real

user2-daemon-local:       NODE_PORT=6666
user2-daemon-local:       APP_NAME=haveno-XMR_LOCAL_user2
user2-daemon-local:       API_PORT=10000
user2-daemon-local:       APPEND=--walletRpcBindPort=38092 --passwordRequired=false
user2-daemon-local:       arbitrator-daemon-local-real

user2-desktop-local:       NODE_PORT=6666
user2-desktop-local:       APP_NAME=haveno-XMR_LOCAL_user2
user2-desktop-local:       API_PORT=10000
user2-desktop-local:       APPEND=--walletRpcBindPort=38092
user2-desktop-local:       arbitrator-desktop-local-real

user3-daemon-local:       NODE_PORT=7778
user3-daemon-local:       APP_NAME=haveno-XMR_LOCAL_user3
user3-daemon-local:       API_PORT=10002
user3-daemon-local:       APPEND=--walletRpcBindPort=38093 --passwordRequired=false
user3-daemon-local:       arbitrator-daemon-local-real

user3-desktop-local:       NODE_PORT=7778
user3-desktop-local:       APP_NAME=haveno-XMR_LOCAL_user3
user3-desktop-local:       API_PORT=10002
user3-desktop-local:       APPEND=--walletRpcBindPort=38093
user3-desktop-local:       arbitrator-desktop-local-real

arbitrator-daemon-local-real:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=$(NODE_PORT) \
		--appName=$(APP_NAME) \
		--apiPassword=apitest \
		--apiPort=$(API_PORT) \
		$(APPEND) \
		--useNativeXmrWallet=false \

arbitrator-desktop-local-real:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=$(NODE_PORT) \
		--appName=$(APP_NAME) \
		--apiPassword=apitest \
		--apiPort=$(API_PORT) \
		$(APPEND) \
		--useNativeXmrWallet=false \

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

seednode-stagenet:  NODE_PORT=3002
seednode-stagenet:  seednode-stagenet-real

seednode2-stagenet: NODE_PORT=3003
seednode2-stagenet: seednode-stagenet-real

seednode-stagenet-real:
	./haveno-seednode$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=$(NODE_PORT) \
		--appName=haveno-XMR_STAGENET_Seed_$(NODE_PORT) \
		--xmrNode=http://127.0.0.1:38081 \

arbitrator-daemon-stagenet: APP_NAME=haveno-XMR_STAGENET_arbitrator
arbitrator-daemon-stagenet: API_PORT=3200
arbitrator-daemon-stagenet: APPEND=--xmrNode=http://127.0.0.1:38081
arbitrator-daemon-stagenet: haveno-daemon-stagenet-real

arbitrator-desktop-stagenet: APP_NAME=haveno-XMR_STAGENET_arbitrator
arbitrator-desktop-stagenet: API_PORT=3200
arbitrator-desktop-stagenet: APPEND=--xmrNode=http://127.0.0.1:38081
arbitrator-desktop-stagenet: haveno-desktop-stagenet-real

user1-daemon-stagenet:      APP_NAME=haveno-XMR_STAGENET_user1
user1-daemon-stagenet:      API_PORT=3201
user1-daemon-stagenet:      haveno-daemon-stagenet-real

user1-desktop-stagenet:      APP_NAME=haveno-XMR_STAGENET_user1
user1-desktop-stagenet:      API_PORT=3201
user1-desktop-stagenet:      haveno-desktop-stagenet-real

user2-daemon-stagenet:      APP_NAME=haveno-XMR_STAGENET_user2
user2-daemon-stagenet:      API_PORT=3202
user2-daemon-stagenet:      haveno-daemon-stagenet-real

user2-desktop-stagenet:      APP_NAME=haveno-XMR_STAGENET_user2
user2-desktop-stagenet:      API_PORT=3202
user2-desktop-stagenet:      haveno-desktop-stagenet-real

user3-desktop-stagenet:      APP_NAME=haveno-XMR_STAGENET_user3
user3-desktop-stagenet:      API_PORT=3203
user3-desktop-stagenet:      haveno-desktop-stagenet-real

haveno-desktop-stagenet:     APP_NAME=Haveno
haveno-desktop-stagenet:     API_PORT=3204
haveno-desktop-stagenet:     haveno-desktop-stagenet-real

haveno-daemon-stagenet-real:
	# Arbitrator needs to be registered before making trades
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=$(APP_NAME) \
		--apiPassword=apitest \
		--apiPort=$(API_PORT) \
		--passwordRequired=false \
		$(APPEND) \
		--useNativeXmrWallet=false \

haveno-daemon-stagenet:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=Haveno \
		--apiPassword=apitest \
		--apiPort=3204 \
		--useNativeXmrWallet=false \

# Arbitrator needs to be registered before making trades
haveno-desktop-stagenet-real:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=$(APP_NAME) \
		--apiPassword=apitest \
		--apiPort=$(API_PORT) \
		$(APPEND) \
		--useNativeXmrWallet=false \

# Mainnet network

monerod:
	./.localnet/monerod \
		--bootstrap-daemon-address auto \
		--rpc-access-control-origins http://localhost:8080 \

seednode: NODE_PORT=1002
seednode: seednode-real

seednode2: NODE_PORT=1003
seednode2: seednode-real

seednode-real:
	./haveno-seednode$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=$(NODE_PORT) \
		--appName=haveno-XMR_MAINNET_Seed_$(NODE_PORT) \
		--xmrNode=http://127.0.0.1:18081 \

arbitrator-daemon-mainnet:   NAME=arbitrator
arbitrator-daemon-mainnet:   API_PORT=1200
arbitrator-daemon-mainnet:   arbitrator-daemon-mainnet-real

arbitrator-desktop-mainnet:  NAME=arbitrator
arbitrator-desktop-mainnet:  API_PORT=1200
arbitrator-desktop-mainnet:  arbitrator-desktop-mainnet-real

arbitrator2-daemon-mainnet:  NAME=arbitrator2
arbitrator2-daemon-mainnet:  API_PORT=1205
arbitrator2-daemon-mainnet:  arbitrator-daemon-mainnet-real

arbitrator2-desktop-mainnet: NAME=arbitrator2
arbitrator2-desktop-mainnet: API_PORT=1205
arbitrator2-desktop-mainnet: arbitrator-desktop-mainnet-real

arbitrator-daemon-mainnet-real:
	# Arbitrator needs to be registered before making trades
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_MAINNET_$(NAME) \
		--apiPassword=apitest \
		--apiPort=$(API_PORT) \
		--passwordRequired=false \
		--xmrNode=http://127.0.0.1:18081 \
		--useNativeXmrWallet=false \

arbitrator-desktop-mainnet-real:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_MAINNET_$(NAME) \
		--apiPassword=apitest \
		--apiPort=$(API_PORT) \
		--xmrNode=http://127.0.0.1:18081 \
		--useNativeXmrWallet=false \

haveno-daemon-mainnet: APP_NAME=Haveno
haveno-daemon-mainnet: API_PORT=1201
haveno-daemon-mainnet: haveno-daemon-mainnet-real

user1-daemon-mainnet:  APP_NAME=haveno-XMR_MAINNET_user1
user1-daemon-mainnet:  API_PORT=1202
user1-daemon-mainnet:  APPEND=--passwordRequired=false
user1-daemon-mainnet:  haveno-daemon-mainnet-real

user2-daemon-mainnet:  APP_NAME=haveno-XMR_MAINNET_user2
user2-daemon-mainnet:  API_PORT=1203
user2-daemon-mainnet:  APPEND=--passwordRequired=false
user2-daemon-mainnet:  haveno-daemon-mainnet-real

haveno-daemon-mainnet-real:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=$(APP_NAME) \
		--apiPassword=apitest \
		--apiPort=$(API_PORT) \
		$(APPEND) \
		--useNativeXmrWallet=false \
		--ignoreLocalXmrNode=false \

haveno-desktop-mainnet: APP_NAME=Haveno
haveno-desktop-mainnet: API_PORT=1201
haveno-desktop-mainnet: haveno-desktop-mainnet-real

user1-desktop-mainnet:  APP_NAME=haveno-XMR_MAINNET_user1
user1-desktop-mainnet:  API_PORT=1202
user1-desktop-mainnet:  haveno-desktop-mainnet-real

user2-desktop-mainnet:  APP_NAME=haveno-XMR_MAINNET_user2
user2-desktop-mainnet:  API_PORT=1203
user2-desktop-mainnet:  haveno-desktop-mainnet-real

user3-desktop-mainnet:  APP_NAME=haveno-XMR_MAINNET_user3
user3-desktop-mainnet:  API_PORT=1204
user3-desktop-mainnet:  haveno-desktop-mainnet-real

haveno-desktop-mainnet-real:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=$(APP_NAME) \
		--apiPassword=apitest \
		--apiPort=$(API_PORT) \
		--useNativeXmrWallet=false \
		--ignoreLocalXmrNode=false \
