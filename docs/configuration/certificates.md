# Certificates

A node holds a certificate only for a name that it terminates. This page tells you which names those are, how HomeGAP orders the certificate, and why a node cannot get other certificates.

## The risk of a certificate on a node

A browser accepts each valid certificate for a name. All traffic to your domain goes through a node. Thus a person with root on a node can read the traffic of each name on the certificate of that node. That person ends TLS on the node, reads or changes the request, and sends the request to the homelab. The browser and the homelab see no difference.

For this reason, HomeGAP gives a node no certificate for a passthrough name.

## Which names are on the certificate

| Name | On the certificate of the nodes |
| --- | --- |
| The management host of each node, `pangolin-<id>.<domain>`. | Yes. |
| In cluster mode, the client address `pangolin.<domain>`. | Yes. |
| The shared names of the status page and the speed test. | Yes. |
| A service with `homegap.tls: terminate`. | Yes. |
| A service with `homegap.tls: passthrough`. | No. |
| The front door with `passthrough`, with or without `homegap.wildcard`. | No. |
| The front door with `terminate`. | Yes: the root name, and `*.<domain>` if the front door has `homegap.wildcard`. |

The activity log shows each order, with the number of names. An order can take some minutes, because it waits for DNS. The reconcile pass does not wait for the order. When the order is complete, the next pass gives the new certificate to the nodes.

### The front door in terminate mode with a wildcard

This is the only setup that puts a wildcard certificate on the nodes. The nodes then end TLS for each name one label under your domain. A person with root on a node can read all of that traffic.

!!! danger "Warning"
    With `homegap.tls: terminate` and `homegap.wildcard: "true"` on the front door, the nodes hold a certificate for `*.<domain>`. Use this setup only if you trust each node and its provider with all web traffic of the domain.

In this setup, HomeGAP refuses a service with `homegap.tls: passthrough` on a hostname one label under the domain. The wildcard on the nodes covers that hostname, so passthrough protects nothing. The error is `homegap.tls=passthrough is refused for <hostname>`. Set `homegap.tls: terminate` on that service, or use `passthrough` on the front door.

HomeGAP does not refuse a raw TCP or UDP service. A certificate has no effect on a protocol that does not use TLS. But a raw TCP service that uses TLS for a name under the domain has the same risk as a web service.

## How HomeGAP orders the certificate

The orchestrator on the homelab orders one certificate from Let's Encrypt. The certificate lists each name from the table above. Each node gets the same certificate and the same key.

HomeGAP uses the DNS challenge:

1. The orchestrator starts Lego, an ACME client in the `homegap` image.
2. For each name, Lego asks the orchestrator for a TXT record `_acme-challenge.<name>`. Lego calls an endpoint on the loopback address of the orchestrator.
3. The orchestrator writes the record with its own Bunny client. Lego never gets the Bunny key.
4. Let's Encrypt reads the records and issues the certificate.
5. The orchestrator removes the records and sends the certificate to each node.

A node orders no certificate. The Traefik on a node has no certificate resolver.

HomeGAP orders a new certificate, with a new key, in these cases:

- The list of names changes.
- One third of the lifetime of the certificate remains.
- The ACME directory in the config changes.

If a name leaves the list, HomeGAP also revokes the old certificate, because the nodes have its key. This occurs when you remove a node, or when you change a service from `terminate` to `passthrough`.

A new certificate does not restart the stack of a node. Traefik loads it in a few seconds.

### Limits

- One certificate has at most 100 names. With more names, the order fails, and the activity log shows the error.
- Let's Encrypt has a limit of approximately 50 new certificates for a domain in a week. HomeGAP uses one for each change of the list of names.

## The CAA records

HomeGAP publishes two CAA records for `domain`, with the tags `issue` and `issuewild`. Each has this value:

```text
letsencrypt.org; validationmethods=dns-01
```

With these records, only Let's Encrypt can issue a certificate for the domain, and only with the DNS challenge. A node has no DNS key. Thus a compromised node cannot order a certificate for one of your names, although the traffic for that name goes through it.

HomeGAP removes other CAA records for `domain`, because they can give more permissions.

!!! warning "Caution"
    The CAA records also apply to the Traefik of your homelab. It must order its certificates from Let's Encrypt with the DNS challenge. If it uses a different certificate authority or a different challenge, its renewals fail. In that case, set `acme.caa: false`, or set `acme.caa_issuer` to the CAA name of your certificate authority.

## Config

```yaml
acme:
  email: ops@example.net
```

| Key | Default | What it does |
| --- | --- | --- |
| `email` | `pangolin.acme_email`, then the admin email. | The contact address of the ACME account. |
| `staging` | `false` | Uses the staging directory of Let's Encrypt. Browsers do not trust its certificates. Use it for tests. |
| `directory` | The directory of Let's Encrypt. | The address of a different ACME directory. |
| `caa` | `true` | Publishes the CAA records. |
| `caa_issuer` | `letsencrypt.org` | The certificate authority in the CAA records. |

## Where HomeGAP keeps the certificate

The ACME account key, the certificate and its key are in `acme/` in the data directory of the orchestrator. Include this folder in your backup of the data directory. If you lose it, HomeGAP makes a new account and orders a new certificate.

On a node, the certificate is `config/traefik/certs/homegap.crt` and the key is `homegap.key`, with mode 0600.

## Expiry and failures

The **Nodes** page on the dashboard shows the expiry of the certificate on each node.

If an order fails, the nodes keep the certificate that they have. HomeGAP writes the error in the activity log and tries again after one hour. A node without a certificate answers its names with the default certificate of Traefik, and browsers show a warning.

These are possible causes of a failed order:

- The Bunny API key is not set, or it cannot change the zone.
- The homelab cannot reach Let's Encrypt.
- A CAA record that HomeGAP does not manage, on a name above `domain`, refuses Let's Encrypt.
