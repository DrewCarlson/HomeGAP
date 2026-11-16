# Extra domains

HomeGAP serves one base domain, the `domain` of the config file. The management host of each node, the status page, the speed test and the dashboard are always under that domain. This page tells you how to publish services under more domains.

## Before you start

- Each extra domain needs a DNS zone in the same Bunny account as the zone of `domain`. HomeGAP uses the same API key for all zones.
- The registrar of each extra domain must use the Bunny nameservers.
- An extra domain cannot be inside `domain`, and `domain` cannot be inside an extra domain. Two extra domains cannot be inside each other.

## Add an extra domain

1. Create the zone of the domain in Bunny DNS.
2. Add the domain to the config file:

    ```yaml
    domain: example.net
    bunny:
      zone: example.net

    extra_domains:
      - domain: example.org
      - domain: apps.example.io
        zone: example.io
    ```

    The `zone` key is necessary only if the zone is a parent of the domain.

3. Save the config file. HomeGAP reads it in the next pass.

A new domain does not change the stack of a node. HomeGAP writes the new routes to the routes file of each node, and the Traefik of the node loads them without a restart.

### Check the result

1. Open the **DNS** page of the dashboard.
2. Find the domain in the **Domains** table. The **State** column shows `healthy` when HomeGAP can sync the zone.
3. Open the **Certificates** tab.
4. Find the CAA records of the domain in **Who can issue**.

If the **State** column shows a warning, the usual cause is that the Bunny account has no zone with that name.

## Publish a service under an extra domain

Use the same labels as for the base domain. No label names the domain:

```yaml
labels:
  homegap.expose: shop.example.org
  homegap.port: "8080"
```

A private service uses the same labels too:

```yaml
labels:
  homegap.expose: notes.example.org
  homegap.access: private
  homegap.port: "8080"
```

HomeGAP refuses a hostname that is outside all the domains of the config file. The label error shows the domains that it knows.

## A front door for an extra domain

Each domain can have its own **front door**. The front door of a domain is the public service with the root of that domain in `homegap.expose`. With `homegap.wildcard: "true"`, it also takes every name one label under the domain that has no service of its own.

Each front door is its own container. In this example, one Traefik is the front door of the base domain. A second Traefik is the front door of an extra domain:

```yaml
services:
  traefik:
    labels:
      homegap.expose: example.net
      homegap.wildcard: "true"
      homegap.protocol: https
      homegap.tls: passthrough

  other-traefik:
    labels:
      homegap.expose: example.org
      homegap.wildcard: "true"
      homegap.protocol: https
      homegap.tls: passthrough
```

The front door of an extra domain has these differences from the front door of the base domain:

- It carries only the names of its own domain.
- It does not carry the status page or the speed test. Those names stay under the base domain.
- The health request of Bunny uses the front door of the base domain, if the node serves one.

A domain without a front door serves only the services that name a hostname in it.

## Use only the subdomains of a domain

The root of a domain can belong to a service that has no relation to HomeGAP, for example a website at a different host. HomeGAP can still publish services under the subdomains of that domain.

1. Add the domain to `extra_domains`, with `caa: false`:
    ```yaml
    extra_domains:
      - domain: example.org
        caa: false
    ```
2. Do not give the domain a front door. No service has `homegap.expose: "example.org"`.
3. Publish each service under a subdomain, for example `app.example.org`. See [Publish a service under an extra domain](#publish-a-service-under-an-extra-domain).
4. Add the records of the root on the **Zone records** tab of the DNS page, for example an A record for `@`.

HomeGAP then writes records only for the subdomains that a service uses. It writes no record at the root and no wildcard record, and it does not change the records that you add there.

With `caa: false`, HomeGAP publishes no CAA records for the domain. Without it, the CAA records of HomeGAP at the root let only Let's Encrypt issue certificates for the domain, and only with the DNS challenge. That stops the certificate of a root service that uses a different authority or a different challenge.

## What HomeGAP does for each extra domain

| Part | What HomeGAP does |
| --- | --- |
| DNS | Writes the records of each name to the zone of its domain. Each zone gets the same geo routing and monitors. |
| CAA records | Publishes the CAA records at the root of each domain, if `acme.caa` is on. |
| Certificate | Adds each name for which the nodes end TLS to the one certificate of the nodes. A certificate holds 100 names at most. |
| Routes | Writes the routes of each name to the routes file of each node that serves it. |

## Remove an extra domain

1. Remove the labels of each service that uses the domain, or change them to a different domain.
2. Remove the domain from `extra_domains`.
3. Save the config file.

!!! warning "Caution"
    HomeGAP does not read the zone of a domain that is not in the config file. Records that HomeGAP wrote there stay. Remove the services first, so that HomeGAP deletes their records, and only then remove the domain.
