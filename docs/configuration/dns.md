# DNS routing and health checks

Bunny DNS sends each viewer to the nearest healthy node. This page tells you how to configure the routing and the monitors, which records HomeGAP creates and how failover works.

## Routing

`bunny.routing` selects how Bunny chooses a node:

| Value | How Bunny chooses |
| --- | --- |
| `geolocation` (default) | By the `location` (latitude and longitude) of each node. If a node has only a `region`, HomeGAP uses the center of the region. |
| `latency` | By the Bunny latency zones. Each node needs a `latency_zone`. |
| `none` | No smart routing. The records are plain records. |

`bunny.ttl` sets the TTL of the records (default 60 seconds).

## Monitors

`bunny.monitor` sets the monitor on each geo record:

- `http` (we recommend it). Bunny sends a request to `http://<node-ip>/`. The node sends the request through the tunnel to the health path of the front door.
- `ping`.
- `none`.

The records for the management hosts, the speed test of each node and the status page of each node have no monitor.

The health path is the `homegap.healthcheck` of the front door (default `/`). Bunny sends a probe every 30 seconds from three regions. If the origin is down, the probe gets a 502. If the tunnel is down, the probe fails within 5 seconds.

## Failover

Bunny removes the offline members from a record set. The next-nearest node then answers. If all members are offline, Bunny returns all of them (fail-open).

In practice, failover takes approximately 1 to 5 minutes: the health interval, plus the TTL, plus the time that resolvers cache the answer.

!!! note
    A single record cannot fail over. With one node, Bunny continues to answer with that node when it is down.

## The records

| Name | Purpose | Type of record |
| --- | --- | --- |
| `<domain>` | `root` | A geo record set. |
| `*.<domain>` | `wildcard` | A geo record set, only with `homegap.wildcard`. |
| Each public dedicated hostname | `service:<key>` | A geo record set. |
| `pangolin.<domain>` | `client` | In cluster mode, a geo record set with all nodes. |
| `speedtest.<domain>` | `speedtest` | A geo record set, only without a wildcard. |
| `speedtest-<node>.<domain>` | `speedtest` | A plain record for each node. |
| `status.<domain>` | `status` | A geo record set, also with a wildcard. |
| `status-<node>.<domain>` | `status` | A plain record for each node. |
| `pangolin-<node>.<domain>` | `management` | A plain record for each node. |

HomeGAP creates A records, and AAAA records for nodes with `public_ipv6`.

A record set includes only the nodes that serve the service (`homegap.regions`). If a node refuses the wildcard (`terminate` without EE), HomeGAP leaves it out of the wildcard set. When you remove a node, HomeGAP first removes it from DNS.

## Ownership of records

HomeGAP marks each of its records with the comment `managed-by:homegap <purpose> node=<id>`. It does not touch records that it did not create, except at the names that it manages.

!!! danger "Warning"
    At a name that HomeGAP manages, HomeGAP deletes some records that it did not create. The log and the **Config** diff give the reason "collides with a managed record set".

    - HomeGAP deletes an A or AAAA record with the same name and type as a managed record.
    - HomeGAP deletes any CNAME record at that name.
    - HomeGAP adopts a record that has exactly the desired value.

    Before you disable dry run, look for `delete` rows in the diff on the **Config** page.

HomeGAP keeps the records of paused nodes and paused services as they are.

## Reads of the zone

When the desired records do not change, HomeGAP reads the whole zone again every `reconcile.dns_check_interval` (default 5 minutes). Thus it finds edits from the Bunny console. A change to the desired records syncs at once.

## The DNS page

The **DNS** page on the dashboard shows the records, the state of their monitors and if they match the desired state.
