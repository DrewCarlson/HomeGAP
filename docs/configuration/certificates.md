# Certificates

The nodes need certificates only for the names that they terminate or serve themselves. This page tells you which names need a certificate, and how to give the nodes the certificate of your homelab.

## Which names need a certificate on the node

For passthrough names, the node reads only the SNI. The homelab serves these names with its own certificates.

The node needs a certificate for these names:

- The resources in `terminate` mode: the root, the wildcard and dedicated hostnames.
- Its management host.
- In cluster mode, the client address.
- The shared name of the status page.

## The certificate source

Set `certificates.source` to one of these values:

| Value | What the nodes use |
| --- | --- |
| `none` (default) | Their own ACME HTTP-01 certificates, from the Traefik of Pangolin (resolver `letsencrypt`, email `pangolin.acme_email`). HTTP-01 cannot issue a wildcard certificate. |
| `files` | A PEM chain (`cert_file`) and a private key (`key_file`) that you mount in the orchestrator. |
| `acme_json` | A certificate from the ACME store of the homelab Traefik. Mount the store read-only, for example at `/homelab/traefik/acme.json`. |

With `files` or `acme_json`, HomeGAP sends the certificate to every node as `homegap.crt` and `homegap.key` for Traefik. Thus `terminate` mode works at once, without a new issuance on the node. HomeGAP also tells Pangolin to prefer the wildcard certificate.

### How HomeGAP selects a certificate from acme.json

HomeGAP looks for a certificate whose main name or SANs include `*.<domain>`. It prefers a certificate that also covers `<domain>`. It searches only the resolver in `certificates.resolver`, or every resolver if that key is not set.

The certificate must come from a DNS-01 challenge, for example with the `bunny` provider of Traefik. If no certificate covers the wildcard, HomeGAP gives the error `No certificate covering *.<domain> in <path>`.

## Use the ACME store of the homelab Traefik

1. Find the path of the store on the host. This command shows the mounts of the Traefik container:
    ```bash
    {% raw %}docker inspect traefik --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'{% endraw %}
    ```
2. List the resolvers and the domains that their certificates cover. The command prints no keys:
    ```bash
    sudo jq -r 'to_entries[] | .key as $r | (.value.Certificates // [])[] | "\($r): \(.domain.main) \(.domain.sans // [] | join(" "))"' /path/to/acme.json
    ```
3. Find a line with `*.example.net`. The text before the colon is the resolver name.
4. Mount the file read-only in the orchestrator:
    ```yaml
    volumes:
      - /path/to/traefik/acme.json:/homelab/traefik/acme.json:ro
    ```
5. Set the source in the config:
    ```yaml
    certificates:
      source: acme_json
      acme_json: /homelab/traefik/acme.json
      resolver: bunny
    ```
    Replace `bunny` with the resolver name from step 3.

## Expiry and failures

The **Nodes** page on the dashboard shows the expiry of the certificate on each node.

If the issuance of a certificate fails on a node, that node cannot serve TLS for those names. To correct this, send the wildcard certificate with `certificates`, or repair ACME on the node.
