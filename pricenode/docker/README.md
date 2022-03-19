Needed software to start a pricenode
==

* docker
* docker-compose

How to start
==

`docker compose up -d`


How to monitor
==

See if it's running: `docker ps`

Check the logs: `docker-compose logs`

Check the tor hostname: `docker exec docker_pricenode_1 cat /var/lib/tor/pricenode/hostname`


How to test
==

Refer to the main pricenode [README](../README.md).