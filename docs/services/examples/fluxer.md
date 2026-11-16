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

<!-- FACT-CHECK: the earlier test results of this page (tracks, bit rate, packet loss, coturn allocations) come from a stack that HomeGAP does not use now. No measurement of the two setups on the mesh is in the repo. -->

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

The homelab forwards the raw ports from the `homegap-mesh` container. Thus `homegap-mesh` must join the Docker network of Fluxer. With the project name `fluxer`, this network is `fluxer_fluxer`. Add it to the `homegap-mesh` service in the `compose.override.yml` of the HomeGAP stack:

```yaml
services:
  homegap-mesh:
    networks:
      fluxer: {}

networks:
  fluxer:
    external: true
    name: fluxer_fluxer
```

Then run this command in the folder of the HomeGAP stack:

```bash
docker compose up -d
```

!!! warning "Caution"
    Compose recreates the `homegap-mesh` container after this change. The mesh drops, and the nodes cannot reach the homelab until the container runs again.

!!! warning "Caution"
    Keep LiveKit (and coturn) on one Docker network only: the network that it shares with `homegap-mesh`. HomeGAP uses the only address of a container that is not on the Traefik network. With two such networks, HomeGAP finds no address, and the ports stay closed. Thus join `homegap-mesh` to the network of Fluxer. Do not add a network to LiveKit.

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

The kernel of the node forwards the two ports over the mesh to LiveKit. LiveKit sees each caller at the address of the `homegap-mesh` container.

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

Every node forwards port 3478 on TCP and UDP to coturn. The relay ports of coturn (49160 to 49200) stay on the Docker network of Fluxer. They need no labels.

## Related pages

- [Raw TCP and UDP](../raw-ports.md)
- [Label reference](../labels.md#port-lists)
- [Services](../index.md#how-homegap-finds-the-container), for the address of a container
