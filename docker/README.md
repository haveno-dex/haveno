# Running a test network using Docker

We provide a docker-compose.yml file and 2 Dockerfiles: one for the Monero nodes and one for the Haveno binaries. This is all you need to run a test instance using the haveno daemons and docker containers.

## 1. Install docker and docker-compose

First make sure docker and docker-compose are installed on your system. See the official instructions for [installing Docker on Ubuntu](https://docs.docker.com/engine/install/ubuntu/). Installing docker compose is usually as simple as running `sudo apt install docker-compose` on Ubuntu/Debian.

## 2. Build images

We use a docker-compose.yml file to set up the test environment. To build all the necessary images run from this location: `docker-compose build` and wait until it's done. Docker will build:

- 2 monero instances (for building and interacting with a local stagenet)
- Haveno seednode
- Haveno arbitrator
- Haveno user 1 (Alice)
- Haveno user 2 (Bob)

## 3. Run instances

Run all instances using `docker-compose up`.

Now open another terminal window, that we will use for sending commands to the containers.

## 4. Mine first 130 blocks

In this window run: `docker ps`. It will give you the details of the running containers. Copy the `CONTAINER ID` of the `IMAGE` `docker_node2`.

Now mine the first 130 blocks to a random address before using, so wallets only use the latest output type. In the same window run:

`docker exec CONTAINER_ID ./monerod --rpc-bind-port=38081 start_mining 56k9Yra1pxwcTYzqKcnLip8mymSQdEfA6V7476W9XhSiHPp1hAboo1F6na7kxTxwvXU6JjDQtu8VJdGj9FEcjkxGJfsyyah 1`

To stop mining:

`docker exec CONTAINER_ID ./monerod --rpc-bind-port=38081 stop_mining`

## 5. Fund your wallets

When launching Alice and Bob, their wallet address will be displayed on the terminal. Follow the steps above, but replacing the random address with Alice and Bob's.