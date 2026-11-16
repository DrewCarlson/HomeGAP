# DNS routing and health checks

Bunny DNS sends each viewer to a healthy node. This page tells you how to configure the routing and the monitors, which records HomeGAP creates and how failover works.

## Routing

`bunny.routing` selects how Bunny chooses a node:

| Value | How Bunny chooses |
| --- | --- |
| `geolocation` (default) | By the `location` (latitude and longitude) of each node. If a node has only a `region`, HomeGAP uses the center of the region. |
| `latency` | By the Bunny latency zones. Each node needs a `latency_zone`. |
| `none` | No smart routing. The records are plain records. |

`bunny.ttl` sets the TTL of the A and AAAA records (default 60 seconds). The CAA records always have a TTL of 3600 seconds.

## Record sets and records of one node

HomeGAP creates two types of records.

A **record set** is for a name that more than one node serves. It has one A record for each node, and one AAAA record for each node with `public_ipv6`. Each record has the routing of `bunny.routing` and the monitor of `bunny.monitor`. Bunny sends a viewer to one member of the set.

A record of one node is for a name that only that node serves, for example its management name. It has no routing and no monitor.

## Monitors

`bunny.monitor` sets the monitor on each record of a record set:

- `http` (the default, and we recommend it). Bunny sends a request to `http://<node-ip>/`. The answer shows that the path from the node to the homelab works.
- `ping`. Bunny sends a ping to the node. The answer shows only that the node is up.
- `none`. Bunny does no check, and a failed node stays in the answers.

With `http`, the Traefik of the node answers the request on port 80 at the IP address of the node. It sends the request over the mesh to one service of the homelab, with the health path of that service. That service is the front door. On a node that does not serve the front door, it is the first HTTP service of that node. The health path is the `homegap.healthcheck` label of the service (default `/`).

If the service does not answer, the node answers 502, and Bunny shows the node as offline. A node with no HTTP service always answers 502. Thus, with the `http` monitor, Bunny shows such a node as offline.

## Failover

Bunny removes the offline members from a record set. A different node then answers. If all members are offline, Bunny returns all of them.

A resolver keeps an answer for the TTL (`bunny.ttl`). Thus some viewers go to the failed node for that time after Bunny removes it.

!!! note
    A single record cannot fail over. With one node, Bunny continues to answer with that node when it is down.

## The records

| Name | Purpose | Type of record |
| --- | --- | --- |
| `<domain>` | `root` | A record set with each node that serves the front door. |
| `*.<domain>` | `wildcard` | A record set, only with `homegap.wildcard` on the front door. |
| Each public hostname of a service | `service:<key>` | One record set for each hostname, also when several raw services share it. The key is the key of the first service. |
| The name of the sign-in, from `sign_in.url` | `service:<key>` | A record set. The sign-in is a public service of HomeGAP itself. |
| `speedtest.<domain>` | `speedtest` | A record set, only without a wildcard. With a wildcard, the wildcard set covers the name. |
| `speedtest-<node>.<domain>` | `speedtest` | A record of one node, for each node that serves the front door. |
| `status.<domain>` | `status` | A record set, also with a wildcard. |
| `status-<node>.<domain>` | `status` | A record of one node, for each node that serves the front door. |
| `node-<id>.<domain>` | `management` | A record of one node, for each node. |
| `<domain>` | `caa` | Two CAA records (`issue` and `issuewild`), with `acme.caa: true` (the default). See [Certificates](certificates.md). |

The records of the speed test and the status page exist only when a front door exists and the page is enabled.

A record set includes only the nodes that serve the service (`homegap.regions`). For a shared hostname, it includes every node that serves one of its services.

During a certificate order, HomeGAP also creates TXT records at `_acme-challenge` names. It removes them after the order.

## A new node and a removed node

A new node gets no DNS records until its stack is up. Before that, the node answers nothing, so Bunny must not send a viewer to it. After the first successful provisioning, the next pass creates the records of the node. The CAA records belong to no node, and HomeGAP creates them at once.

When you remove a node from the config, HomeGAP deletes its records in the next pass.

## Ownership of records

HomeGAP marks each of its records with the comment `managed-by:homegap <purpose> node=<id>`. For the CAA records, the id is `homelab`. The comment of a challenge record is `managed-by:homegap acme-challenge`. HomeGAP does not touch records that it did not create, except at the names that it manages.

!!! danger "Warning"
    At a name that HomeGAP manages, HomeGAP deletes some records that it did not create. The log and the diff on the **Config** page give the reason "collides with a managed record set".

    - HomeGAP deletes an A or AAAA record with the same name and type as a managed record.
    - With `acme.caa: true`, HomeGAP deletes a CAA record at `domain` that it did not create.
    - HomeGAP deletes each CNAME record at that name.
    - HomeGAP adopts a record that has exactly the desired value.

    Before you disable dry run, look for `delete` rows in the diff on the **Config** page.

## Pauses

HomeGAP does not create, change or delete the records of a paused node or a paused service. You pause a service with the `homegap.pause` label or from the dashboard. While all changes are paused, HomeGAP changes no record.

## Extra domains

Each [extra domain](../services/extra-domains.md) has its own zone, or shares a zone with a different domain. HomeGAP writes each record to the zone of its domain, with the same routing, monitor and TTL. If one zone fails, HomeGAP still syncs the other zones, and the activity log names the zone that failed.

## Reads of the zone

A change to the desired records syncs at once. When the desired records do not change, HomeGAP reads the whole zone again every `reconcile.dns_check_interval` (default 5 minutes). Thus it finds edits from the Bunny console.

After a change, HomeGAP asks the nameservers of the zone for the record. Until each nameserver has it, the record shows "not on every nameserver", and HomeGAP asks again on each pass.

## The DNS page

The **DNS** page on the dashboard shows the records, the state of their monitors and if they match the desired state. To add records of your own, for example a TXT record for a verification, use the **Zone records** tab of that page. It refuses a record that HomeGAP deletes. See [The dashboard](../operations/dashboard.md).
