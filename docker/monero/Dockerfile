# Download Haveno's custom Monero binaries. Managed by docker-compose.

FROM ubuntu:20.04 as downloader

RUN set -ex && \
    apt update && \
    apt --no-install-recommends --yes install \
        wget \
        ca-certificates

# We use the existent haveno_deps.sh script to download and verify the Monero binaries
RUN set -ex && wget https://raw.githubusercontent.com/haveno-dex/haveno/master/scripts/haveno_deps.sh && \
    chmod +x haveno_deps.sh && \
    ./haveno_deps.sh

FROM ubuntu:20.04

# Create monero user
RUN adduser --system --group --disabled-password monero && \
	mkdir -p /home/monero/ && \
	chown -R monero:monero /home/monero/

COPY --chown=monero:monero --from=downloader /monerod /monero-wallet-rpc /home/monero/

USER monero

WORKDIR /home/monero/

ENTRYPOINT ["./monerod", "--stagenet", "--no-igd", "--hide-my-port", "--p2p-bind-ip=127.0.0.1", "--fixed-difficulty=100", "--non-interactive"]
