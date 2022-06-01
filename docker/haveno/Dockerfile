# Build haveno-seednode and haveno-daemon. Managed by docker-compose.

FROM openjdk:11

RUN set -ex && \
    apt update && \
    apt --no-install-recommends --yes install \
        make \
        git 

RUN set -ex && adduser --system --group --disabled-password haveno && \
    mkdir -p /home/haveno && \
    chown -R haveno:haveno /home/haveno

USER haveno

WORKDIR /home/haveno
        
RUN set -ex && git clone https://github.com/haveno-dex/haveno.git && \
    cd haveno && \
    make skip-tests

WORKDIR /home/haveno/haveno

ENTRYPOINT [ "./haveno-daemon" ]

# Will be overwritten for each container
CMD ["--baseCurrencyNetwork=XMR_STAGENET", "--useLocalhostForP2P=true", "--useDevPrivilegeKeys=true", "--nodePort=5555", "--appName=haveno-XMR_STAGENET_Alice", "--apiPassword=apitest", "--apiPort=9999", "--walletRpcBindPort=38091", "--passwordRequired=false" ]
