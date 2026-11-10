# Prepare Bunny DNS

HomeGAP uses Bunny DNS to send each viewer to the nearest healthy node. This page tells you how to prepare the DNS zone and the API key.

## What HomeGAP needs

HomeGAP needs two things from Bunny:

- A DNS zone that is equal to `domain` or a parent of it. The config key is `bunny.zone`. Validation fails if the zone is not equal to `domain` or a parent of it.
- The API key of your Bunny account. The orchestrator reads it from an environment variable (default name `BUNNY_API_KEY`, config key `bunny.api_key_env`) or from a file (`bunny.api_key_file`).

HomeGAP sends the key in the `AccessKey` header to `https://api.bunny.net`. It finds the zone by its name and creates all the records itself. You do not create records.

## Prepare the zone and the key

1. Choose a test domain. Use a spare domain, or a subdomain of your real domain, for example `gap.example.net`.
2. Sign in to Bunny (bunny.net). Open the DNS section https://dash.bunny.net/dns/zones
3. Add a DNS zone for the domain or the subdomain.
4. Point the domain at the nameservers of Bunny. Do the step that matches your domain:
    - For a whole domain, go to your registrar. Replace the nameservers with the nameservers that Bunny shows for the zone.
    - For a subdomain, go to the current DNS provider of the parent zone. Add NS records for the subdomain there.
5. Wait until Bunny shows the zone as active. To check the delegation, run this command:
    ```bash
    dig NS gap.example.net +short
    ```
    The command shows the nameservers of Bunny.
6. Copy the API key of your account from the Bunny account settings: https://dash.bunny.net/account/api-key
7. Keep the key for the install. You put it in `.env` as `BUNNY_API_KEY`. See [Install on the homelab](install.md).

## Records at the names that HomeGAP manages

!!! danger "Warning"
    On each pass that is not a dry run, HomeGAP deletes records that collide with its managed record sets. This occurs at each name that HomeGAP manages: the root, `*.<domain>`, the dedicated hostnames, the node management hosts and the other managed names.

    - HomeGAP deletes an A or AAAA record that it did not create, if it has the same name and type as a managed record.
    - HomeGAP deletes any CNAME record at a managed name.
    - HomeGAP adopts an unmanaged record that has exactly the desired value.

    Before you disable dry run, open the **Config** page on the dashboard. Find the `delete` rows in the diff, and write down the records that you want to keep.

HomeGAP never touches records at other names. It marks its own records with the comment `managed-by:homegap <purpose> node=<id>`.

## What HomeGAP creates

| Name | Record | When |
| --- | --- | --- |
| `<domain>` | A geo record set (the front door). | When a front door exists. |
| `*.<domain>` | A geo record set (the front door). | When the front door has `homegap.wildcard: "true"`. |
| A public dedicated hostname | A geo record set. | For each public service with its own hostname. |
| `pangolin-<node>.<domain>` | A plain record (the management host of the node). | For each node. |
| `pangolin.<domain>` | A geo record set. | In cluster mode. |
| `speedtest.<domain>` | A geo record set for the speed test. | With the speed test, and only if there is no wildcard. |
| `speedtest-<node>.<domain>` | A record for each node. | With the speed test. |
| `status.<domain>` | A geo record set. | With the status page. |
| `status-<node>.<domain>` | A record for each node. | With the status page. |

HomeGAP creates A records, and AAAA records for nodes with `public_ipv6`. The TTL comes from `bunny.ttl` (default `60`). The monitor comes from `bunny.monitor` (default `http`). Only geo records get a monitor.

For the routing and the monitors, see [DNS routing and health checks](../configuration/dns.md).
