# Haveno build orchestration — see docs/installing.md and `make help`
SHELL := /bin/bash
GRADLE := ./gradlew
# Skip slow checks for dev-iteration targets
SKIP_CHECKS := -x test -x checkstyleMain -x checkstyleTest
# Extra Gradle flags, e.g. make skip-tests GRADLE_EXTRA='--rerun-tasks'
GRADLE_EXTRA ?=

.PHONY: help build clean clean-all haveno skip-tests daemon desktop seednode-jar package test check-java \
        update-dependencies refresh-deps haveno-apps localnet clean-localnet deploy-screen deploy-tmux

.DEFAULT_GOAL := build

help:
	@echo "Haveno build targets (Java 21, Gradle 8.14):"
	@echo ""
	@echo "  make / make build     Full build with tests (downloads monero bins via Gradle)"
	@echo "  make skip-tests       Build without tests or checkstyle (fast dev iteration)"
	@echo "  make haveno-apps      Compile core + build desktop + daemon shadow JAR (no tests)"
	@echo "  make daemon           Build daemon fat JAR (:daemon:shadowJar)"
	@echo "  make desktop          Build desktop fat JAR (:desktop:shadowJar)"
	@echo "  make seednode-jar     Build seednode fat JAR (:seednode:shadowJar)"
	@echo "  make package          Build platform installers (:desktop:packageInstallers)"
	@echo "  make test             Run unit tests only"
	@echo "  make clean            Gradle clean + remove root haveno-* launchers"
	@echo "  make clean-all        clean + remove .localnet monero cache"
	@echo "  make check-java       Verify Java 21 is active"
	@echo "  make update-dependencies  Refresh dependency lock metadata"
	@echo "  make refresh-deps     Refresh verification metadata and rebuild (no tests)"
	@echo ""
	@echo "Local network runtime targets are listed in the Makefile below this section."

check-java:
	@java -version 2>&1 | grep -qE 'version "21(\.|")' || { echo "ERROR: Java 21 is required (see docs/installing.md)"; exit 1; }
	@echo "Java 21 OK"

build: check-java localnet haveno

clean:
	$(GRADLE) clean $(GRADLE_EXTRA)

clean-all: clean clean-localnet

clean-localnet:
	rm -rf .localnet

localnet:
	mkdir -p .localnet

haveno:
	$(GRADLE) build $(GRADLE_EXTRA)

update-dependencies:
	$(GRADLE) --refresh-dependencies $(GRADLE_EXTRA)
	$(GRADLE) --write-verification-metadata sha256 $(GRADLE_EXTRA)

daemon: localnet
	$(GRADLE) :daemon:shadowJar $(SKIP_CHECKS) $(GRADLE_EXTRA)

desktop: localnet
	$(GRADLE) :desktop:shadowJar $(SKIP_CHECKS) $(GRADLE_EXTRA)

seednode-jar: localnet
	$(GRADLE) :seednode:shadowJar $(SKIP_CHECKS) $(GRADLE_EXTRA)

package: localnet
	$(GRADLE) :desktop:packageInstallers $(GRADLE_EXTRA)

# build haveno without tests
skip-tests: check-java localnet
	$(GRADLE) build $(SKIP_CHECKS) $(GRADLE_EXTRA)

# quick build desktop and daemon apps without tests
haveno-apps: localnet
	$(GRADLE) :core:compileJava :desktop:build :daemon:shadowJar $(SKIP_CHECKS) $(GRADLE_EXTRA)

test: localnet
	$(GRADLE) test $(GRADLE_EXTRA)

refresh-deps:
	$(GRADLE) --write-verification-metadata sha256 $(GRADLE_EXTRA)
	$(GRADLE) build --refresh-keys --refresh-dependencies $(SKIP_CHECKS) $(GRADLE_EXTRA)

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
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 500 \
		--disable-rpc-ban \
		--rpc-max-connections 1000 \
		--max-connections-per-ip 10 \
		--rpc-max-connections-per-private-ip 1000 \

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
		--add-exclusive-node 127.0.0.1:28080 \
		--add-exclusive-node 127.0.0.1:58080 \
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 500 \
		--disable-rpc-ban \
		--rpc-max-connections 1000 \
		--max-connections-per-ip 10 \
		--rpc-max-connections-per-private-ip 1000 \

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
		--add-exclusive-node 127.0.0.1:28080 \
		--add-exclusive-node 127.0.0.1:48080 \
		--rpc-access-control-origins http://localhost:8080 \
		--fixed-difficulty 500 \
		--disable-rpc-ban \
		--rpc-max-connections 1000 \
		--max-connections-per-ip 10 \
		--rpc-max-connections-per-private-ip 1000 \

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
	    --xmrNode=http://127.0.0.1:48081 \

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
	    --xmrNode=http://127.0.0.1:48081 \

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
	    --xmrNode=http://127.0.0.1:48081 \

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
	    --xmrNode=http://127.0.0.1:48081 \

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
		--rpc-max-connections 1000 \
		--max-connections-per-ip 10 \
		--rpc-max-connections-per-private-ip 1000 \

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
