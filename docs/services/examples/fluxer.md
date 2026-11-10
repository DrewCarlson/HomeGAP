# Fluxer (chat and voice)

[Fluxer](https://fluxer.com/selfhost) is a self-hosted chat app. Its voice and video calls use a bundled LiveKit server. This page tells you how to publish Fluxer through HomeGAP, with the call media on raw ports.

## How Fluxer uses the network

Fluxer uses one domain (`FLUXER_DOMAIN`). The web app, the API, the gateway and the voice signalling share this domain by path (`/api/`, `/gateway` and `/livekit`). Thus the front door publishes all of them, through your homelab Traefik.

The call media does not use the domain. It goes directly to LiveKit on TCP 7881 and UDP 7882 (`FLUXER_LIVEKIT_TCP_PORT` and `FLUXER_LIVEKIT_UDP_PORT`). LiveKit tells the clients a literal address (`FLUXER_LIVEKIT_NODE_IP`, with `FLUXER_LIVEKIT_USE_EXTERNAL_IP=false`). The public port must be the same as the container port.

This page gives two setups:

| Setup | Calls | Failover for calls |
| --- | --- | --- |
| Direct media through one node | LiveKit advertises the address of one node. | No. |
| TURN (recommended for several nodes) | Callers reach a TURN server by name, on the nearest healthy node. | Yes. |

## What the tests checked

Both setups pass the end-to-end tests in the HomeGAP harness, through each of two nodes. The tests check the web app, the signalling and the media path.

The tests do not check a call between two users of the Fluxer app. The API of Fluxer disconnects LiveKit participants that do not join through Fluxer ("participant_joined without usable metadata"). Thus the tests use plain LiveKit clients, and run LiveKit without the webhook of Fluxer.

## The web app

1. Put Fluxer in proxy mode. In the `.env` of Fluxer, set this value:
    ```text
    COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml
    ```
    In proxy mode, the edge of Fluxer listens on plain HTTP port 8080 for your proxy. `FLUXER_EDGE_BIND` sets the address on the host.
2. Attach the `edge` service of Fluxer to the Traefik network, in a compose override of Fluxer:
    ```yaml
    services:
      edge:
        networks: [fluxer, traefik]

    networks:
      traefik:
        external: true
    ```
3. Route `chat.example.net` from your homelab Traefik to the edge, for example in the dynamic config:
    ```yaml
    http:
      routers:
        fluxer:
          rule: "Host(`chat.example.net`)"
          entryPoints: [websecure]
          service: fluxer
          tls: {}
      services:
        fluxer:
          loadBalancer:
            servers:
              - url: "http://fluxer-edge-1:8080"
    ```
    Replace `fluxer-edge-1` with the name of the edge container.

The front door publishes `chat.example.net`. The web app needs no HomeGAP labels.

The proxy requirements of Fluxer apply to your Traefik as usual: WebSocket upgrades, long timeouts and bodies up to 512 MB. See the [reverse proxy guide of Fluxer](https://docs.fluxer.com/operator/reverse-proxy/).

## The network of LiveKit

The `tunnels` container must join the Docker network of Fluxer. With the project name `fluxer`, this network is `fluxer_fluxer`. Add it to the `tunnels` service in the homelab compose file:

```yaml
services:
  tunnels:
    networks: [traefik, fluxer]

networks:
  fluxer:
    external: true
    name: fluxer_fluxer
```

You can also use `docker network connect fluxer_fluxer <tunnels container>`.

!!! warning "Caution"
    LiveKit must be on one Docker network only: the network that it shares with `tunnels` (or with coturn). On two networks, LiveKit answers from its address on one network, while the packets of the tunnel arrive at the other. Then calls fail with "could not connect". The order of the interfaces can change on each restart. Thus join `tunnels` to the network of Fluxer. Do not add a network to LiveKit.

## Setup 1: direct media through one node

In this setup, all calls go through one node. It is the simpler setup, but calls have no failover.

1. In a compose override of Fluxer, remove the published `ports` of the `livekit` service, and add the labels:
    ```yaml
    services:
      livekit:
        ports: !reset []
        labels:
          homegap.expose: "voice.example.net"
          homegap.port: "7881/tcp, 7882/udp"
          homegap.regions: "na1"
    ```
2. In the `.env` of Fluxer, set the public IPv4 address of that node:
    ```text
    FLUXER_LIVEKIT_NODE_IP=203.0.113.10
    FLUXER_LIVEKIT_USE_EXTERNAL_IP=false
    ```
3. Restart Fluxer.

We recommend `homegap.regions` with the advertised node, so that HomeGAP publishes the ports only there. With all nodes, calls still use only the advertised node.

In the test, a call has one audio publisher, one video publisher and two subscribers. All 4 of 4 tracks arrive, with approximately 1.2 Mbps of video each and 0% packet loss, in three runs out of three. The selected ICE pair of LiveKit is the node address on 7882/udp, with the remote end at the `tunnels` container.

## Setup 2: TURN

In this setup, calls reach a TURN server by name. DNS sends each caller to the nearest healthy node, so failover also works for calls. We recommend it when you have several nodes.

1. Add coturn on the network of Fluxer, in a compose override of Fluxer:
    ```yaml
    services:
      turn:
        image: docker.io/coturn/coturn:4.7.0
        command:
          - --listening-port=3478
          - --realm=example.net
          - --use-auth-secret
          - --static-auth-secret=<secret>
          - --min-port=49160
          - --max-port=49200
          - --allowed-peer-ip=<the Docker network range of LiveKit>
          - --no-tls
          - --no-dtls
          - --no-cli
          - --fingerprint
        networks: [fluxer]
        labels:
          homegap.expose: "turn.example.net"
          homegap.port: "3478/tcp+udp"
    ```
2. In the `.env` of Fluxer, set the TURN servers. Use the same secret as coturn:
    ```text
    FLUXER_LIVEKIT_TURN_SERVERS='[{"host":"turn.example.net","port":3478,"protocol":"udp","secret":"<secret>"},{"host":"turn.example.net","port":3478,"protocol":"tcp","secret":"<secret>"}]'
    ```
3. In the same file, leave the node address empty:
    ```text
    FLUXER_LIVEKIT_NODE_IP=
    FLUXER_LIVEKIT_USE_EXTERNAL_IP=false
    ```
4. Make sure that the `livekit` service has no HomeGAP labels and no published ports.
5. Restart Fluxer.

LiveKit then advertises its container address, and only coturn can reach it. With a `secret`, coturn checks short-lived credentials (`use-auth-secret`). The `host` of a TURN server can be a hostname.

In the test, each of the two nodes gives 4 of 4 tracks with 0% loss. coturn logs 24 successful allocations and 168 channel binds, all from the address of the `tunnels` container.

## Related pages

- [Raw TCP and UDP](../raw-ports.md)
- [Label reference](../labels.md#port-lists)
- [Performance](../../operations/performance.md), for `tunnels.mtu`
