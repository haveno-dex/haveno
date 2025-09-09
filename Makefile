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

seednode-local:
	./haveno-seednode$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=2002 \
		--appName=haveno-XMR_LOCAL_Seed_2002 \
		--xmrNode=http://localhost:28081 \

seednode2-local:
	./haveno-seednode$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=2003 \
		--appName=haveno-XMR_LOCAL_Seed_2003 \
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
		--passwordRequired=false \
		--useNativeXmrWallet=false \

arbitrator-desktop-local:
	# Arbitrator needs to be registered before making trades
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=4444 \
		--appName=haveno-XMR_LOCAL_arbitrator \
		--apiPassword=apitest \
		--apiPort=9998 \
		--useNativeXmrWallet=false \

arbitrator2-daemon-local:
	# Arbitrator needs to be registered before making trades
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=7777 \
		--appName=haveno-XMR_LOCAL_arbitrator2 \
		--apiPassword=apitest \
		--apiPort=10001 \
		--useNativeXmrWallet=false \

arbitrator2-desktop-local:
	# Arbitrator needs to be registered before making trades
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=7777 \
		--appName=haveno-XMR_LOCAL_arbitrator2 \
		--apiPassword=apitest \
		--apiPort=10001 \
		--useNativeXmrWallet=false \

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
		--passwordRequired=false \
		--useNativeXmrWallet=false \

user1-desktop-local:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=5555 \
		--appName=haveno-XMR_LOCAL_user1 \
		--apiPassword=apitest \
		--apiPort=9999 \
		--walletRpcBindPort=38091 \
		--logLevel=info \
		--useNativeXmrWallet=false \

user2-desktop-local:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=6666 \
		--appName=haveno-XMR_LOCAL_user2 \
		--apiPassword=apitest \
		--apiPort=10000 \
		--walletRpcBindPort=38092 \
		--useNativeXmrWallet=false \

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
		--passwordRequired=false \
		--useNativeXmrWallet=false \

user3-desktop-local:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=7778 \
		--appName=haveno-XMR_LOCAL_user3 \
		--apiPassword=apitest \
		--apiPort=10002 \
		--walletRpcBindPort=38093 \
		--useNativeXmrWallet=false \

user3-daemon-local:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_LOCAL \
		--useLocalhostForP2P=true \
		--useDevPrivilegeKeys=true \
		--nodePort=7778 \
		--appName=haveno-XMR_LOCAL_user3 \
		--apiPassword=apitest \
		--apiPort=10002 \
		--walletRpcBindPort=38093 \
		--passwordRequired=false \
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

seednode-stagenet:
	./haveno-seednode$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=3002 \
		--appName=haveno-XMR_STAGENET_Seed_3002 \
		--xmrNode=http://127.0.0.1:38081 \

seednode2-stagenet:
	./haveno-seednode$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=3003 \
		--appName=haveno-XMR_STAGENET_Seed_3003 \
		--xmrNode=http://127.0.0.1:38081 \

arbitrator-daemon-stagenet:
	# Arbitrator needs to be registered before making trades
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_STAGENET_arbitrator \
		--apiPassword=apitest \
		--apiPort=3200 \
		--passwordRequired=false \
		--xmrNode=http://127.0.0.1:38081 \
		--useNativeXmrWallet=false \

# Arbitrator needs to be registered before making trades
arbitrator-desktop-stagenet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_STAGENET_arbitrator \
		--apiPassword=apitest \
		--apiPort=3200 \
		--xmrNode=http://127.0.0.1:38081 \
		--useNativeXmrWallet=false \

user1-daemon-stagenet:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_STAGENET_user1 \
		--apiPassword=apitest \
		--apiPort=3201 \
		--passwordRequired=false \
		--useNativeXmrWallet=false \

user1-desktop-stagenet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_STAGENET_user1 \
		--apiPassword=apitest \
		--apiPort=3201 \
		--useNativeXmrWallet=false \

user2-daemon-stagenet:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_STAGENET_user2 \
		--apiPassword=apitest \
		--apiPort=3202 \
		--passwordRequired=false \
		--useNativeXmrWallet=false \

user2-desktop-stagenet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_STAGENET_user2 \
		--apiPassword=apitest \
		--apiPort=3202 \
		--useNativeXmrWallet=false \

user3-desktop-stagenet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_STAGENET_user3 \
		--apiPassword=apitest \
		--apiPort=3203 \
		--useNativeXmrWallet=false \

haveno-desktop-stagenet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_STAGENET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=Haveno \
		--apiPassword=apitest \
		--apiPort=3204 \
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

# Mainnet network

monerod:
	./.localnet/monerod \
		--bootstrap-daemon-address auto \
		--rpc-access-control-origins http://localhost:8080 \

seednode:
	./haveno-seednode$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=1002 \
		--appName=haveno-XMR_MAINNET_Seed_1002 \
		--xmrNode=http://127.0.0.1:18081 \

seednode2:
	./haveno-seednode$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=1003 \
		--appName=haveno-XMR_MAINNET_Seed_1003 \
		--xmrNode=http://127.0.0.1:18081 \

arbitrator-daemon-mainnet:
	# Arbitrator needs to be registered before making trades
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_MAINNET_arbitrator \
		--apiPassword=apitest \
		--apiPort=1200 \
		--passwordRequired=false \
		--xmrNode=http://127.0.0.1:18081 \
		--useNativeXmrWallet=false \

arbitrator-desktop-mainnet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_MAINNET_arbitrator \
		--apiPassword=apitest \
		--apiPort=1200 \
		--xmrNode=http://127.0.0.1:18081 \
		--useNativeXmrWallet=false \

arbitrator2-daemon-mainnet:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_MAINNET_arbitrator2 \
		--apiPassword=apitest \
		--apiPort=1205 \
		--passwordRequired=false \
		--xmrNode=http://127.0.0.1:18081 \
		--useNativeXmrWallet=false \

arbitrator2-desktop-mainnet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_MAINNET_arbitrator2 \
		--apiPassword=apitest \
		--apiPort=1205 \
		--xmrNode=http://127.0.0.1:18081 \
		--useNativeXmrWallet=false \

haveno-daemon-mainnet:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=Haveno \
		--apiPassword=apitest \
		--apiPort=1201 \
		--useNativeXmrWallet=false \
		--ignoreLocalXmrNode=false \

haveno-desktop-mainnet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=Haveno \
		--apiPassword=apitest \
		--apiPort=1201 \
		--useNativeXmrWallet=false \
		--ignoreLocalXmrNode=false \

user1-daemon-mainnet:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_MAINNET_user1 \
		--apiPassword=apitest \
		--apiPort=1202 \
		--passwordRequired=false \
		--useNativeXmrWallet=false \
		--ignoreLocalXmrNode=false \

user1-desktop-mainnet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_MAINNET_user1 \
		--apiPassword=apitest \
		--apiPort=1202 \
		--useNativeXmrWallet=false \
		--ignoreLocalXmrNode=false \

user2-daemon-mainnet:
	./haveno-daemon$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_MAINNET_user2 \
		--apiPassword=apitest \
		--apiPort=1203 \
		--passwordRequired=false \
		--useNativeXmrWallet=false \
		--ignoreLocalXmrNode=false \

user2-desktop-mainnet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_MAINNET_user2 \
		--apiPassword=apitest \
		--apiPort=1203 \
		--useNativeXmrWallet=false \
		--ignoreLocalXmrNode=false \

user3-desktop-mainnet:
	./haveno-desktop$(APP_EXT) \
		--baseCurrencyNetwork=XMR_MAINNET \
		--useLocalhostForP2P=false \
		--useDevPrivilegeKeys=false \
		--nodePort=9999 \
		--appName=haveno-XMR_MAINNET_user3 \
		--apiPassword=apitest \
		--apiPort=1204 \
		--useNativeXmrWallet=false \
		--ignoreLocalXmrNode=false \

buyer-wallet-mainnet:
	./.localnet/monero-wallet-rpc \
		--daemon-address http://localhost:18081 \
		--rpc-bind-port 18084 \
		--rpc-login rpc_user:abc123 \
		--rpc-access-control-origins http://localhost:8080 \
		--wallet-dir ./.localnet \

seller-wallet-mainnet:
	./.localnet/monero-wallet-rpc \
		--daemon-address http://localhost:18081 \
		--rpc-bind-port 18085 \
		--rpc-login rpc_user:abc123 \
		--rpc-access-control-origins http://localhost:8080 \
		--wallet-dir ./.localnet \
