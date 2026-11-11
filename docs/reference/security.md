# Security model

This page tells you where HomeGAP keeps its secrets, what each part can reach and what the public can see.

## The orchestrator is the trust center

The orchestrator holds every SSH key and every API key. Thus:

- Run it on a minimal host.
- Mount the secrets. Do not put them in the image.
- Keep the dashboard private, and set a token with `dashboard.auth_token_env`.

!!! danger "Warning"
    If somebody gets access to the dashboard or to the orchestrator host, that person has control of every node.

## Secrets

HomeGAP creates the secrets of each node from the CSPRNG of the kernel. These are the Pangolin server secret, the admin credentials, the setup token and the CrowdSec bouncer key. It sends them over SSH to `<install_dir>/secrets/node.json` (mode 0600). This file is the source of truth. The local state file `/data/state.json` (mode 0600) is only a cache. If you lose it, HomeGAP reads the credentials from the node again.

The add-node wizard keeps a setup password in memory for the job only. It never stores it. HomeGAP writes generated node keys to the data directory with mode 0600.

See [Secrets](../configuration/secrets.md).

## Access to the nodes

On each node, the Pangolin API key of the orchestrator is a root key, restricted to the actions that HomeGAP needs. HomeGAP retires its older keys by itself.

The Pangolin ports 3000, 3002 and 3003 on the node listen only on loopback. The orchestrator reaches them only through SSH port forwarding.

`ufw` on the node opens only the ports in [Ports and firewall](ports.md). HomeGAP adds new rules before it removes its old rules. It never touches rules that it did not add.

## The homelab

The orchestrator mounts the Docker socket read-only, and it only reads labels. The tunnels run in a separate container. Thus the orchestrator never carries traffic.

HomeGAP never publishes the IP address of the homelab. Every tunnel goes outbound from home. In cluster mode, the homelab dials the mesh. On the homelab end of the mesh, HomeGAP drops inbound traffic, except replies, ping and the ports of the cluster database.

## Traffic

With `passthrough`, the nodes never see plaintext. `pangolin.crowdsec` gives abuse mitigation on the edge for terminated traffic only. It cannot examine passthrough streams.

## Calls to outside services

The software that HomeGAP runs makes no calls home of its own. HomeGAP turns off:

- **Pangolin:** its usage data, the product news and newest-version notices on its dashboard, and the download of its AI model catalog. It reads an empty catalog from its config instead.
- **Traefik on the nodes:** its version check and its usage data.

Some calls have no setting. In the container that makes the call, HomeGAP resolves the name to `127.0.0.1`, so the call fails on the machine itself:

- **Newt** asks `api.fossorial.io` for the newest version each time it starts. This affects the tunnels container on the homelab and each node's exit connector.
- **Pangolin** looks up its public address at `checkip.amazonaws.com`, `ifconfig.io` and `api.ipify.org` each time it starts. The address only fills in a hint on the DNS records page of its dashboard, and HomeGAP publishes the DNS records of each node itself.

The calls that remain are needed for the system to work:

- If you use a Pangolin EE license key, the nodes check it at `api.fossorial.io`.
- Traefik on the nodes downloads its plugins (Pangolin's `badger`, and the CrowdSec bouncer if enabled) from the Traefik plugin catalog when it starts.
- The nodes get certificates from Let's Encrypt.
- With `pangolin.crowdsec`, CrowdSec talks to its central API for the community blocklists.

Your own Traefik on the homelab is configured by you. Set `global.checkNewVersion` and `global.sendAnonymousUsage` to `false` in its static configuration to turn off the same calls there.

## What the public can see

- The public status page never shows addresses or hostnames. It shows the homelab only as its region. See [Status page](../operations/status-page.md).
- The token of a speed test link for a device is in the fragment of the URL. The browser never sends it to a server, so no log contains it. See [Speed tests](../operations/speed-tests.md).
- The nodes end TLS for the shared names `status.<domain>` and `speedtest.<domain>`, which serve only these HomeGAP pages. A node then sends each request back into its own port 443 under its own name, and the front door carries it home still encrypted. On that inner connection the node does not check the certificate, because the connection does not leave the node before it enters the tunnel.

## Report a security issue

To report a security issue, open a private security advisory on GitHub at `https://github.com/DrewCarlson/HomeGAP/security/advisories/new`. Do not open a public issue.
