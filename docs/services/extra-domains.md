# Extra domains

HomeGAP serves one base domain, the `domain` of the config file. The names of the platform itself are always under that domain: the Pangolin of each node, the status page and the speed test. This page tells you how to publish services under more domains.

## Before you start

- Each extra domain needs a DNS zone in the same Bunny account as the zone of `domain`. HomeGAP uses the same API key for all zones.
- The registrar of each extra domain must point to the Bunny nameservers.
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

HomeGAP then adds the domain to the Pangolin config of each node. This is a stack update, so the nodes update one at a time. See [Node updates and rollback](../operations/node-updates.md).

### What you see during the change

The change takes some minutes. During that time, these results are usual:

- If a service already uses the domain, the activity log shows `Domain not found for full-domain` for its resources. The error stops when the nodes have the new config.
- HomeGAP orders the certificate of the nodes again, with the new names. Until the nodes have the new certificate, a browser shows a certificate warning for a name of the extra domain.
- HomeGAP does not send viewers to a node for a name until that node has the resource of the name.

To avoid the errors, add the domain first. Add the labels of the services after the nodes have the new stack.

### Check the result

1. Open the **DNS** page of the dashboard.
2. Find the domain in the **Domains** table. The **State** column shows `healthy` when HomeGAP can sync the zone.
3. Open the **Certificates** tab.
4. Find the CAA records of the domain in **Who can issue**. Each record starts with its domain.

If the **State** column shows an error, the usual cause is that the Bunny account has no zone with that name.

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
- The health resource that Bunny probes uses the front door of the base domain, if there is one.

A domain without a front door serves only the services that name a hostname in it.

## What HomeGAP does for each extra domain

| Part | What HomeGAP does |
| --- | --- |
| DNS | Writes the records of each name to the zone of its domain. Each zone gets the same geo routing and monitors. |
| CAA records | Publishes the CAA records at the root of each domain, if `acme.caa` is on. |
| Certificate | Adds each name for which the nodes end TLS to the one certificate of the nodes. A certificate holds 100 names at most. |
| Pangolin | Adds the domain to the Pangolin config of each node. |

## Remove an extra domain

1. Remove the labels of each service that uses the domain, or change them to a different domain.
2. Remove the domain from `extra_domains`.
3. Save the config file.

!!! warning "Caution"
    HomeGAP does not read the zone of a domain that is no longer in the config file. Records that HomeGAP wrote there stay. Remove the services first, so that HomeGAP deletes their records, and only then remove the domain.
