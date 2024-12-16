# docker run -it -p 9050 -p 2002 --restart-policy unless-stopped --name haveno-seednode haveno-seednode
# TODO: image very heavy, but it's hard to significantly reduce the size without bins

FROM openjdk:21-jdk-bullseye

RUN set -ex && \
    apt update && \
    apt --no-install-recommends --yes install \
        make \
        git \
        tor

RUN set -ex && adduser --system --group --disabled-password haveno && \
    mkdir -p /home/haveno && \
    chown -R haveno:haveno /home/haveno

USER haveno

WORKDIR /home/haveno

RUN set -ex && git clone https://github.com/haveno-dex/haveno.git && \
    cd haveno && \
    make skip-tests

WORKDIR /home/haveno/haveno

ENTRYPOINT [ "./haveno-seednode" ]

CMD ["--baseCurrencyNetwork=XMR_STAGENET", "--useLocalhostForP2P=false", "--useDevPrivilegeKeys=false", "--nodePort=2002", "--appName=haveno-XMR_STAGENET_Seed_2002" ]
