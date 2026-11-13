# Publish a service

This page tells you how to send the first real traffic through HomeGAP, and how to check the result.

## Publish through the front door

The front door claims the root domain and `*.<domain>`. Thus HomeGAP publishes the root and each name one label under the domain that your homelab Traefik serves, and you add no more labels.

To publish a service, add a normal Traefik router at home, for example with the rule ``Host(`whoami.example.net`)``.

A service that needs its own path gets its own `homegap.*` labels. This applies to a dedicated hostname, a raw TCP or UDP service and a private service. See the [Label reference](../services/labels.md). A Docker event starts a pass at once, so the change starts without a wait for the next resync.

## Check the service

Run these commands from any network:

```bash
dig +short example.net
dig +short whoami.example.net
curl -sv https://whoami.example.net/ 2>&1 | grep -E 'subject:|HTTP/'
curl -s -o /dev/null -w '%{http_code}\n' http://203.0.113.10/
```

The results are as follows:

- The two `dig` commands return node addresses. The wildcard record covers `whoami`.
- The `curl` command to `https://` shows the certificate of your homelab. In passthrough mode, the homelab Traefik ends TLS.
- The `curl` command to `http://<node ip>/` sends the same request as the Bunny monitor. The request goes through the tunnel to the health check path of the front door. It returns 502 when the origin is down.

On the dashboard, the **Overview** and **Services** pages show the health in each region. The **DNS** page shows the records and the state of their monitors.

!!! note
    One node gives no failover. With a single record for each name, Bunny continues to answer with that node when it is down. For failover, you need a second node.

## Label errors

The dashboard and the log show label errors for each container. An error in the labels of one container never stops the other services. These are examples of errors:

- An unknown protocol.
- Pangolin sign-in (`homegap.auth`) without `homegap.tls: terminate`.
- A reserved public port.
- A hostname that two services claim.

An unknown `homegap.*` label gives a warning, and HomeGAP ignores it.
