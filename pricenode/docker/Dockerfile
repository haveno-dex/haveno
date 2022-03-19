###
# Haveno pricenode dockerfile
###

# pull base image
FROM openjdk:11-jdk

# install tor
RUN apt-get update && apt-get install -y --no-install-recommends \
    tor && rm -rf /var/lib/apt/lists/*

# copy tor configuration file
COPY torrc /etc/tor/
# give proper permissions for tor configuration file
RUN chown debian-tor:debian-tor /etc/tor/torrc
# add haveno user
RUN useradd -d /haveno -G debian-tor haveno
# make haveno directory
RUN mkdir -p /haveno
# give haveno user proper permissions
RUN chown haveno:haveno /haveno
# clone haveno repository
RUN git clone https://github.com/haveno-dex/haveno.git /haveno/haveno
# build pricenode
WORKDIR /haveno/haveno
RUN ./gradlew :pricenode:installDist -x test
# set proper java options
ENV JAVA_OPTS=""
# expose ports
EXPOSE 80
EXPOSE 8078
# set launch command (tor and pricenode)
CMD tor && /haveno/haveno/haveno-pricenode 2
