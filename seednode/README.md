# Haveno Seednode

Currently a seednode can be deployed using the Dockerfile in the `docker/` folder.

Make sure you have Tor installed in your host environment (`apt install tor`), then navigate to the `docker` folder and from there build the Docker image:

```
docker build -t haveno-seednode .
```

Then create a container from it:

```
docker run -it -p 9050 -p 2002 --restart unless-stopped --name haveno-seednode haveno-seednode
```

After the seednode is deployed, you'll see a message similar to this in the log:

```
[TorControlParser] INFO  o.b.n.tor.Tor: Hidden Service 3jrnfkgkoh463zic54csvntz5w62dm2zno54c3c6jgvusafosqrgmnqd.onion has been announced to the Tor network.
```

Note the onion address. It will be needed by the Haveno instances wanting to connect to your seednode.